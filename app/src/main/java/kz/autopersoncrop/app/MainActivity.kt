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
import android.view.View
import android.widget.*
import kz.autopersoncrop.R
import kz.autopersoncrop.batch.BatchProcessingService
import kz.autopersoncrop.batch.BatchStateStore
import kz.autopersoncrop.settings.OutputQuality
import kz.autopersoncrop.settings.OutputResolution
import kz.autopersoncrop.settings.OutputSettings
import kz.autopersoncrop.settings.OutputSettingsStore

class MainActivity : Activity() {
    private lateinit var folderText: TextView
    private lateinit var stateText: TextView
    private lateinit var settingsText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var resumeButton: Button
    private lateinit var pauseButton: Button
    private var treeUri: Uri? = null
    private var mainScreenVisible = false

    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (mainScreenVisible) refreshState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.getString("tree_uri", null)?.let { treeUri = Uri.parse(it) }
        buildMainUi()
        refreshFolder()
        refreshSettings()
        repairStaleRunState()
        refreshState()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BatchProcessingService.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        if (mainScreenVisible) refreshState()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    @Deprecated("Legacy result API intentionally used to keep the app dependency-light.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return

        if (resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            treeUri = uri
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            BatchStateStore(this).resetForNewFolder(uri.toString())
            buildMainUi()
            refreshFolder()
            refreshSettings()
            refreshState()
        } else {
            showFolderSelectionScreen()
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
                    message = "Предыдущая обработка прервана. Можно продолжить с оставшихся фото.",
                )
            )
        }
    }

    private fun showFolderSelectionScreen() {
        mainScreenVisible = false
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        val title = TextView(this).apply {
            text = "Выбор папки"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        val current = TextView(this).apply {
            text = treeUri?.let { "Текущая папка:\n$it" } ?: "Папка пока не выбрана"
            textSize = 14f
            setPadding(0, dp(14), 0, dp(14))
        }
        val open = Button(this).apply {
            text = "Открыть системный выбор папки"
            setOnClickListener { launchSystemFolderPicker() }
        }
        val back = Button(this).apply {
            text = "← Назад"
            setOnClickListener {
                buildMainUi()
                refreshFolder()
                refreshSettings()
                refreshState()
            }
        }
        val hint = TextView(this).apply {
            text = "В системном окне Android также можно вернуться стрелкой ←. Приложение получает доступ только к выбранной вами папке."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(14), 0, 0)
        }
        listOf(title, current, open, back, hint).forEach {
            root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        setContentView(root)
    }

    private fun launchSystemFolderPicker() {
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
        startBatchFor(uri)
    }

    private fun resumeBatch() {
        val saved = BatchStateStore(this).read()
        val uri = treeUri ?: saved.folderUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return toast("Не удалось восстановить выбранную папку")
        treeUri = uri
        prefs.edit().putString("tree_uri", uri.toString()).apply()
        startBatchFor(uri)
    }

    private fun startBatchFor(uri: Uri) {
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

    private fun showSettings() {
        val store = OutputSettingsStore(this)
        val current = store.read()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val resolutionLabel = TextView(this).apply {
            text = "Разрешение обработанного фото"
            setTypeface(typeface, Typeface.BOLD)
        }
        val resolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                OutputResolution.entries.map { it.label }
            )
            setSelection(OutputResolution.entries.indexOf(current.resolution))
        }

        val qualityLabel = TextView(this).apply {
            text = "Качество JPEG"
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        }
        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                OutputQuality.entries.map { it.label }
            )
            setSelection(OutputQuality.entries.indexOf(current.quality))
        }

        val info = TextView(this).apply {
            text = "Высокое + Оригинальное = lossless JPEG без пересжатия. Среднее/Низкое или уменьшенное разрешение создают новый JPEG. Фото без найденных людей копируются без изменений."
            textSize = 13f
            alpha = 0.78f
            setPadding(0, dp(16), 0, 0)
        }

        box.addView(resolutionLabel)
        box.addView(resolutionSpinner)
        box.addView(qualityLabel)
        box.addView(qualitySpinner)
        box.addView(info)

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(box)
            .setNegativeButton("Назад", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val settings = OutputSettings(
                    quality = OutputQuality.entries[qualitySpinner.selectedItemPosition],
                    resolution = OutputResolution.entries[resolutionSpinner.selectedItemPosition],
                )
                store.write(settings)
                refreshSettings()
                toast("Настройки сохранены. Они применятся к новым результатам.")
            }
            .show()
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

    private fun buildMainUi() {
        mainScreenVisible = true
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
            text = "Офлайн • массовая обработка • работа в фоне"
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
            setOnClickListener { showFolderSelectionScreen() }
        }
        settingsText = TextView(this).apply {
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(4), 0, dp(4))
        }
        val settings = Button(this).apply {
            text = "Настройки"
            setOnClickListener { showSettings() }
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
        resumeButton = Button(this).apply {
            text = "▶ Возобновить"
            visibility = View.GONE
            setOnClickListener { resumeBatch() }
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
            text = "Оригиналы не изменяются. Результат сохраняется в CROP. Высокое + Оригинальное сохраняет lossless JPEG-кроп; другие режимы используют выбранное качество и разрешение."
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(18), 0, 0)
        }
        val privacy = Button(this).apply {
            text = "Конфиденциальность"
            setOnClickListener { showPrivacyPolicy() }
        }
        listOf(title, subtitle, developer, folderText, choose, settingsText, settings, progress, stateText, startButton, resumeButton, pauseButton, stop, note, privacy).forEach {
            root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        setContentView(root)
    }

    private fun refreshFolder() {
        if (!mainScreenVisible) return
        folderText.text = treeUri?.let { "Папка: $it" } ?: "Папка не выбрана"
        startButton.isEnabled = treeUri != null
    }

    private fun refreshSettings() {
        if (!mainScreenVisible) return
        val s = OutputSettingsStore(this).read()
        settingsText.text = "Разрешение: ${s.resolution.label}   •   Качество: ${s.quality.label}"
    }

    private fun refreshState() {
        if (!mainScreenVisible) return
        val s = BatchStateStore(this).read()
        val done = s.completed + s.skipped + s.errors + s.noPeople
        val pct = if (s.total > 0) done.toDouble() / s.total else 0.0
        progress.progress = (pct * 1000).toInt().coerceIn(0, 1000)
        stateText.text = buildString {
            append("Найдено: ${s.total}\n")
            append("Готово: ${s.completed}   Без людей: ${s.noPeople}\n")
            append("Пропущено: ${s.skipped}   Ошибки: ${s.errors}\n")
            if (s.currentName.isNotBlank()) append("Сейчас: ${s.currentName}\n")
            append(if (s.running) if (s.paused) "Пауза" else s.message.ifBlank { "Обработка…" } else s.message.ifBlank { "Готов к запуску" })
        }

        val recoverable = !s.running && s.folderUri.isNotBlank() && s.total > 0 && done < s.total
        resumeButton.visibility = if (recoverable) View.VISIBLE else View.GONE
        resumeButton.isEnabled = recoverable
        startButton.isEnabled = !s.running && treeUri != null

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
