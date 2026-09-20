package kz.autopersoncrop.batch

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kz.autopersoncrop.R
import kz.autopersoncrop.io.DocumentTreeScanner
import kz.autopersoncrop.jpeg.LosslessJpegTransformer
import kz.autopersoncrop.ml.YoloLiteRtPersonDetector
import kz.autopersoncrop.settings.OutputSettingsStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BatchProcessingService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private lateinit var store: BatchStateStore
    private lateinit var db: BatchDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPublishAt = 0L

    override fun onCreate() {
        super.onCreate()
        serviceActive = true
        store = BatchStateStore(this)
        db = BatchDatabase(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CMD_START -> {
                val tree = intent.getStringExtra(EXTRA_TREE_URI) ?: return START_NOT_STICKY
                val sw = intent.getIntExtra(EXTRA_SCREEN_W, 1080)
                val sh = intent.getIntExtra(EXTRA_SCREEN_H, 2400)
                val forceRequested = intent.getBooleanExtra(EXTRA_FORCE_REPROCESS, false)
                val forceReprocess = forceRequested && (flags and START_FLAG_REDELIVERY == 0)
                startForegroundCompat(notification("Подготовка…", 0, 0))
                if (running.compareAndSet(false, true)) {
                    stopRequested.set(false)
                    paused.set(false)
                    acquireWakeLock()
                    executor.execute { runBatch(Uri.parse(tree), sw, sh, forceReprocess) }
                }
            }
            CMD_PAUSE -> {
                paused.set(true)
                val s = store.read().copy(paused = true, message = "Пауза")
                store.write(s); publish(s, force = true)
            }
            CMD_RESUME -> {
                paused.set(false)
                val s = store.read().copy(paused = false, message = "Обработка…")
                store.write(s); publish(s, force = true)
            }
            CMD_STOP -> {
                stopRequested.set(true)
                paused.set(false)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun runBatch(treeUri: Uri, screenW: Int, screenH: Int, forceReprocess: Boolean) {
        val folderKey = treeUri.toString()
        var state = BatchState(
            folderUri = folderKey,
            running = true,
            message = if (forceReprocess) "Подготовка повторной обработки…" else "Сканирование папки…",
        )
        store.write(state, sync = true)
        publish(state, force = true)

        try {
            val scanner = DocumentTreeScanner(this)
            val (cropRoot, photos) = scanner.scan(treeUri)
            db.sync(folderKey, photos)
            if (forceReprocess) db.resetFolder(folderKey)

            val statusMap = db.statuses(folderKey)
            state = state.withCounts(db.counts(folderKey)).copy(
                total = photos.size,
                message = if (forceReprocess) "Повторная обработка ${photos.size} JPEG" else "Найдено ${photos.size} JPEG",
            )
            store.write(state)
            publish(state, force = true)

            if (photos.isEmpty()) {
                finishState(state.copy(running = false, message = "JPEG-файлы не найдены"))
                return
            }

            val outputSettings = OutputSettingsStore(this).read()
            YoloLiteRtPersonDetector(
                this,
                confidence = 0.18f,
                iouThreshold = 0.65f,
            ).use { detector ->
                state = state.copy(message = "Обработка • ${detector.accelerator} • ${outputSettings.quality.label}")
                store.write(state)
                publish(state, force = true)

                val transformer = LosslessJpegTransformer(this)
                val processor = PhotoProcessor(
                    this,
                    detector,
                    transformer,
                    screenW,
                    screenH,
                    outputSettings,
                )

                for (photo in photos) {
                    val uriKey = photo.uri.toString()
                    val prior = statusMap[uriKey] ?: BatchDatabase.PENDING
                    // NO_PEOPLE is intentionally retried: newer recovery detection may now find a
                    // wrestler that an older build missed. DONE and EXISTING stay untouched.
                    if (prior == BatchDatabase.DONE || prior == BatchDatabase.EXISTING) continue

                    if (stopRequested.get()) {
                        finishState(
                            state.withCounts(db.counts(folderKey)).copy(
                                running = false,
                                paused = false,
                                currentName = "",
                                message = "Остановлено. Нажмите «Возобновить» — готовые файлы будут пропущены.",
                            )
                        )
                        return
                    }

                    while (paused.get() && !stopRequested.get()) Thread.sleep(100)
                    if (stopRequested.get()) continue

                    state = state.copy(
                        currentName = photo.name,
                        paused = false,
                        message = "Обработка в фоне • ${detector.accelerator}",
                    )
                    store.write(state)
                    publish(state)

                    db.mark(folderKey, uriKey, BatchDatabase.PROCESSING)
                    statusMap[uriKey] = BatchDatabase.PROCESSING
                    val outDir = scanner.ensureOutputDir(cropRoot, photo.relativeDir)

                    try {
                        val result = processWithRetry(
                            scanner = scanner,
                            processor = processor,
                            photo = photo,
                            outDir = outDir,
                            overwriteFromStart = forceReprocess || prior == BatchDatabase.NO_PEOPLE || prior == BatchDatabase.ERROR || prior == BatchDatabase.PROCESSING,
                        )
                        val newStatus = when (result) {
                            ProcessResult.Cropped, ProcessResult.CopiedFull -> BatchDatabase.DONE
                            ProcessResult.NoPeopleCopied -> BatchDatabase.NO_PEOPLE
                            ProcessResult.AlreadyExists -> BatchDatabase.EXISTING
                        }
                        db.mark(folderKey, uriKey, newStatus)
                        statusMap[uriKey] = newStatus
                        state = state.transitionCount(prior, newStatus)
                    } catch (t: Throwable) {
                        db.mark(folderKey, uriKey, BatchDatabase.ERROR, t.message ?: t.javaClass.simpleName)
                        statusMap[uriKey] = BatchDatabase.ERROR
                        state = state.transitionCount(prior, BatchDatabase.ERROR)
                    }

                    // Do not GROUP BY the entire SQLite queue after every photo. The durable row
                    // status is still written for every file; only the UI counters are maintained
                    // locally and reconciled with SQLite at stop/final/error boundaries.
                    store.write(state)
                    publish(state)
                }
            }

            val finalCounts = db.counts(folderKey)
            state = state.withCounts(finalCounts)
            val finalMessage = when {
                finalCounts.errors > 0 -> "Завершено. Ошибок: ${finalCounts.errors}. Нажмите «Возобновить» для повторной попытки."
                finalCounts.pending + finalCounts.processing > 0 -> "Есть необработанные файлы. Нажмите «Возобновить»."
                else -> "Готово"
            }
            finishState(state.copy(running = false, paused = false, currentName = "", message = finalMessage))
        } catch (t: Throwable) {
            val counts = runCatching { db.counts(folderKey) }.getOrNull()
            if (counts != null) state = state.withCounts(counts)
            finishState(
                state.copy(
                    running = false,
                    paused = false,
                    currentName = "",
                    message = "Остановлено: ${t.message ?: t.javaClass.simpleName}. Можно возобновить.",
                )
            )
        } finally {
            running.set(false)
        }
    }

    private fun processWithRetry(
        scanner: DocumentTreeScanner,
        processor: PhotoProcessor,
        photo: kz.autopersoncrop.io.SourcePhoto,
        outDir: androidx.documentfile.provider.DocumentFile,
        overwriteFromStart: Boolean,
    ): ProcessResult {
        var last: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                if (attempt > 1) scanner.invalidateOutputIndex(outDir)
                val existing = scanner.existingOutputUri(outDir, photo.name)
                return processor.process(
                    photo = photo,
                    outputDir = outDir,
                    existingOutputUri = existing,
                    overwriteExisting = overwriteFromStart || attempt > 1,
                )
            } catch (t: Throwable) {
                last = t
                if (attempt < MAX_ATTEMPTS) {
                    scanner.invalidateOutputIndex(outDir)
                    Thread.sleep(120)
                }
            }
        }
        throw last ?: IllegalStateException("Неизвестная ошибка обработки")
    }

    private fun BatchState.withCounts(c: BatchDatabase.Counts): BatchState = copy(
        completed = c.done,
        noPeople = c.noPeople,
        skipped = c.existing,
        errors = c.errors,
    )

    private fun BatchState.transitionCount(oldStatus: Int, newStatus: Int): BatchState {
        if (oldStatus == newStatus) return this
        var done = completed
        var noPeopleCount = noPeople
        var existing = skipped
        var errorCount = errors

        when (oldStatus) {
            BatchDatabase.DONE -> done = (done - 1).coerceAtLeast(0)
            BatchDatabase.NO_PEOPLE -> noPeopleCount = (noPeopleCount - 1).coerceAtLeast(0)
            BatchDatabase.EXISTING -> existing = (existing - 1).coerceAtLeast(0)
            BatchDatabase.ERROR -> errorCount = (errorCount - 1).coerceAtLeast(0)
        }
        when (newStatus) {
            BatchDatabase.DONE -> done++
            BatchDatabase.NO_PEOPLE -> noPeopleCount++
            BatchDatabase.EXISTING -> existing++
            BatchDatabase.ERROR -> errorCount++
        }
        return copy(completed = done, noPeople = noPeopleCount, skipped = existing, errors = errorCount)
    }

    private fun finishState(s: BatchState) {
        store.write(s, sync = true)
        publish(s, force = true)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish(s: BatchState, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishAt < PUBLISH_INTERVAL_MS) return
        lastPublishAt = now

        val done = s.completed + s.noPeople + s.skipped
        val n = notification(
            if (s.paused) "Пауза" else s.message.ifBlank { "Обработка…" }, done, s.total
        )
        if (s.running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName))
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("Auto Person Crop")
            .setContentText(if (total > 0) "$text — $done / $total" else text)
            .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(pi)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Фоновая обработка фотографий"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (running.get()) {
            val s = store.read()
            store.write(s.copy(message = "Обработка продолжается в фоне"), sync = true)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Обработка продолжается в фоне", s.completed + s.noPeople + s.skipped, s.total),
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopRequested.set(true)
        val s = store.read().copy(
            running = false,
            paused = false,
            currentName = "",
            message = "Системный лимит Android для mediaProcessing достигнут. Прогресс сохранён — откройте приложение и нажмите «Возобновить».",
        )
        store.write(s, sync = true)
        publish(s, force = true)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:batch").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        if (::store.isInitialized) {
            val s = store.read()
            if (s.running && !stopRequested.get()) {
                store.write(
                    s.copy(
                        running = false,
                        paused = false,
                        currentName = "",
                        message = "Фоновая обработка была прервана системой. Нажмите «Возобновить».",
                    ),
                    sync = true,
                )
            }
        }
        serviceActive = false
        executor.shutdownNow()
        releaseWakeLock()
        if (::db.isInitialized) db.close()
        super.onDestroy()
    }

    companion object {
        @Volatile var serviceActive: Boolean = false
            private set

        const val ACTION_PROGRESS = "kz.autopersoncrop.PROGRESS"
        const val CMD_START = "kz.autopersoncrop.START"
        const val CMD_PAUSE = "kz.autopersoncrop.PAUSE"
        const val CMD_RESUME = "kz.autopersoncrop.RESUME"
        const val CMD_STOP = "kz.autopersoncrop.STOP"
        private const val EXTRA_TREE_URI = "tree_uri"
        private const val EXTRA_SCREEN_W = "screen_w"
        private const val EXTRA_SCREEN_H = "screen_h"
        private const val EXTRA_FORCE_REPROCESS = "force_reprocess"
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 77
        private const val MAX_ATTEMPTS = 2
        private const val PUBLISH_INTERVAL_MS = 900L

        fun command(
            context: Context,
            action: String,
            treeUri: String? = null,
            screenW: Int = 0,
            screenH: Int = 0,
            forceReprocess: Boolean = false,
        ) {
            val i = Intent(context, BatchProcessingService::class.java).setAction(action)
            if (treeUri != null) i.putExtra(EXTRA_TREE_URI, treeUri)
            if (screenW > 0) i.putExtra(EXTRA_SCREEN_W, screenW)
            if (screenH > 0) i.putExtra(EXTRA_SCREEN_H, screenH)
            if (forceReprocess) i.putExtra(EXTRA_FORCE_REPROCESS, true)
            if (action == CMD_START) context.startForegroundService(i) else context.startService(i)
        }
    }
}
