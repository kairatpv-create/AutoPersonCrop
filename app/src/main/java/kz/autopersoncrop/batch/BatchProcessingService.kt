package kz.autopersoncrop.batch

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
                // A redelivered intent after process death must continue, not reset the whole batch again.
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
                store.write(s); publish(s)
            }
            CMD_RESUME -> {
                paused.set(false)
                val s = store.read().copy(paused = false, message = "Обработка…")
                store.write(s); publish(s)
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
        store.write(state); publish(state)
        try {
            val scanner = DocumentTreeScanner(this)
            val (cropRoot, photos) = scanner.scan(treeUri)
            db.sync(folderKey, photos)
            if (forceReprocess) db.resetFolder(folderKey)

            val initial = db.counts(folderKey)
            val statusMap = db.statuses(folderKey)
            state = state.copy(
                total = photos.size,
                completed = initial.done,
                noPeople = initial.noPeople,
                skipped = initial.existing,
                errors = 0,
                message = if (forceReprocess) "Повторная обработка ${photos.size} JPEG" else "Найдено ${photos.size} JPEG",
            )
            store.write(state); publish(state)

            if (photos.isEmpty()) {
                finishState(state.copy(running = false, message = "JPEG-файлы не найдены"))
                return
            }

            val outputSettings = OutputSettingsStore(this).read()
            YoloLiteRtPersonDetector(this).use { detector ->
                state = state.copy(message = "Обработка • ${detector.accelerator} • ${outputSettings.quality.label}")
                store.write(state); publish(state)

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
                    val prior = statusMap[uriKey]
                    if (prior == BatchDatabase.DONE || prior == BatchDatabase.NO_PEOPLE || prior == BatchDatabase.EXISTING) continue
                    if (stopRequested.get()) {
                        finishState(state.copy(running = false, paused = false, currentName = "", message = "Остановлено. Можно запустить снова — готовые файлы будут пропущены."))
                        return
                    }
                    while (paused.get() && !stopRequested.get()) Thread.sleep(150)
                    if (stopRequested.get()) continue

                    state = state.copy(currentName = photo.name, paused = false, message = "Обработка • ${detector.accelerator}")
                    store.write(state); publish(state)
                    try {
                        val overwriteOutput = forceReprocess || prior == BatchDatabase.ERROR || prior == BatchDatabase.PROCESSING
                        db.mark(folderKey, uriKey, BatchDatabase.PROCESSING)
                        statusMap[uriKey] = BatchDatabase.PROCESSING
                        val outDir = scanner.ensureOutputDir(cropRoot, photo.relativeDir)
                        val existingOutput = scanner.existingOutputUri(outDir, photo.name)
                        when (processor.process(
                            photo = photo,
                            outputDir = outDir,
                            existingOutputUri = existingOutput,
                            overwriteExisting = overwriteOutput,
                        )) {
                            ProcessResult.Cropped, ProcessResult.CopiedFull -> {
                                db.mark(folderKey, uriKey, BatchDatabase.DONE)
                                statusMap[uriKey] = BatchDatabase.DONE
                                state = state.copy(completed = state.completed + 1)
                            }
                            ProcessResult.NoPeopleCopied -> {
                                db.mark(folderKey, uriKey, BatchDatabase.NO_PEOPLE)
                                statusMap[uriKey] = BatchDatabase.NO_PEOPLE
                                state = state.copy(noPeople = state.noPeople + 1)
                            }
                            ProcessResult.AlreadyExists -> {
                                db.mark(folderKey, uriKey, BatchDatabase.EXISTING)
                                statusMap[uriKey] = BatchDatabase.EXISTING
                                state = state.copy(skipped = state.skipped + 1)
                            }
                        }
                    } catch (t: Throwable) {
                        db.mark(folderKey, uriKey, BatchDatabase.ERROR, t.message ?: t.javaClass.simpleName)
                        statusMap[uriKey] = BatchDatabase.ERROR
                        state = state.copy(errors = state.errors + 1, message = "Ошибка: ${t.message ?: t.javaClass.simpleName}")
                    }
                    store.write(state); publish(state)
                }
            }
            finishState(state.copy(running = false, paused = false, currentName = "", message = "Готово"))
        } catch (t: Throwable) {
            finishState(state.copy(running = false, paused = false, currentName = "", message = "Остановлено: ${t.message ?: t.javaClass.simpleName}"))
        } finally {
            running.set(false)
        }
    }

    private fun finishState(s: BatchState) {
        store.write(s, sync = true); publish(s)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish(s: BatchState) {
        val done = s.completed + s.noPeople + s.skipped + s.errors
        val n = notification(
            if (s.paused) "Пауза" else s.message.ifBlank { "Обработка…" }, done, s.total
        )
        if (s.running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName))
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("Auto Person Crop")
            .setContentText(if (total > 0) "$text — $done / $total" else text)
            .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopRequested.set(true)
        val s = store.read().copy(running = false, paused = false, message = "Системный лимит фоновой обработки. Запустите снова — прогресс сохранён.")
        store.write(s, sync = true)
        publish(s)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:batch").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        serviceActive = false
        executor.shutdownNow()
        releaseWakeLock()
        db.close()
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
