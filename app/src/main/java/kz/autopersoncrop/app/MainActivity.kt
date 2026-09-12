package kz.autopersoncrop.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import kz.autopersoncrop.R
import kz.autopersoncrop.batch.BatchProcessingService
import kz.autopersoncrop.batch.BatchStateStore

class MainActivity : Activity() {
    private lateinit var folderText: TextView
    private lateinit var stateText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private var treeUri: Uri? = null

    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        prefs.getString("tree_uri", null)?.let { treeUri = Uri.parse(it) }
        refreshFolder()
        repairStaleRunState()
        refreshState()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BatchProcessingService.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    @Deprecated("Legacy result API intentionally used to keep the app dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            treeUri = uri
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            BatchStateStore(this).resetForNewFolder(uri.toString())
            refreshFolder()
            refreshState()
        }
    }

    private fun repairStaleRunState() {
        val store = BatchStateStore(this)
        val s = store.read()
        if (s.running && !BatchProcessingService.serviceActive) {
            store.write(
                s.copy(
                    running = false,
                    paused = false,
                    currentName = "",
                    message = "Предыдущая обработка была прервана. Нажмите «Обработать всё» — готовые фото будут пропущены.",
                )
            )
        }
    }

    private fun chooseFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_TREE)
    }

    private fun startBatch() {
        val uri = treeUri ?: return toast("Сначала выберите папку")
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds else {
            @Suppress("DEPRECATION") android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        BatchProcessingService.command(
            this, BatchProcessingService.CMD_START, uri.toString(), bounds.width(), bounds.height()
        )
    }

    private fun togglePause() {
        val state = BatchStateStore(this).read()
        val cmd = if (state.paused) BatchProcessingService.CMD_RESUME else BatchProcessingService.CMD_PAUSE
        BatchProcessingService.command(this, cmd)
    }

    private fun showPrivacyPolicy() {
        AlertDialog.Builder(this)
            .setTitle("Конфиденциальность")
            .setMessage(
                "Auto Person Crop обрабатывает фотографии только на вашем устройстве.\n\n" +
                    "• Приложение не запрашивает доступ к Интернету и не отправляет фотографии, имена файлов или результаты обработки на серверы.\n" +
                    "• Доступ предоставляется только к папке, которую вы сами выбираете через системное окно Android.\n" +
                    "• Состояние очереди, выбранная папка и прогресс обработки сохраняются локально на устройстве для продолжения после прерывания.\n" +
                    "• Оригиналы фотографий не изменяются. Результаты сохраняются в папке CROP.\n" +
                    "• В приложении нет рекламы, аналитики, регистрации и стороннего облачного хранилища.\n" +
                    "• Резервное копирование данных приложения средствами Android отключено.\n\n" +
                    getString(R.string.developer_label)
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Офлайн • массовая обработка • без ухудшения JPEG"
            textSize = 15f
            setPadding(0, dp(6), 0, dp(18))
        }
        val developer = TextView(this).apply {
            text = getString(R.string.developer_label)
            textSize = 13f
            alpha = 0.72f
            setPadding(0, 0, 0, dp(14))
        }
        folderText = TextView(this).apply { textSize = 15f }
        val choose = Button(this).apply {
            text = "Выбрать папку"
            setOnClickListener { chooseFolder() }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        stateText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(10), 0, dp(10))
        }
        startButton = Button(this).apply {
            text = "Обработать всё"
            setOnClickListener { startBatch() }
        }
        pauseButton = Button(this).apply {
            text = "Пауза"
            setOnClickListener { togglePause() }
        }
        val stop = Button(this).apply {
            text = "Остановить"
            setOnClickListener { BatchProcessingService.command(this@MainActivity, BatchProcessingService.CMD_STOP) }
        }
        val note = TextView(this).apply {
            text = "Оригиналы не изменяются. Результат сохраняется в CROP. Если строгая lossless-обрезка невозможна, файл не пересжимается и отмечается ошибкой."
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(18), 0, 0)
        }
        val privacy = Button(this).apply {
            text = "Конфиденциальность"
            setOnClickListener { showPrivacyPolicy() }
        }
        listOf(title, subtitle, developer, folderText, choose, progress, stateText, startButton, pauseButton, stop, note, privacy).forEach {
            root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        setContentView(root)
    }

    private fun refreshFolder() {
        folderText.text = treeUri?.let { "Папка: $it" } ?: "Папка не выбрана"
        startButton.isEnabled = treeUri != null
    }

    private fun refreshState() {
        val s = BatchStateStore(this).read()
        val done = s.completed + s.skipped + s.errors + s.noPeople
        val pct = if (s.total > 0) done.toDouble() / s.total else 0.0
        progress.progress = (pct * 1000).toInt().coerceIn(0, 1000)
        stateText.text = buildString {
            append("Найдено: ${s.total}\n")
            append("Готово: ${s.completed}   Без людей: ${s.noPeople}\n")
            append("Пропущено: ${s.skipped}   Ошибки: ${s.errors}\n")
            if (s.currentName.isNotBlank()) append("Сейчас: ${s.currentName}\n")
            append(if (s.running) if (s.paused) "Пауза" else "Обработка…" else s.message.ifBlank { "Готов к запуску" })
        }
        pauseButton.text = if (s.paused) "Продолжить" else "Пауза"
        pauseButton.isEnabled = s.running
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_TREE = 1001
        private const val REQ_NOTIFICATIONS = 1002
    }
}
