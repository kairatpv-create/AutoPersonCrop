package kz.autopersoncrop.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kz.autopersoncrop.R
import kz.autopersoncrop.batch.BatchDatabase
import kz.autopersoncrop.batch.BatchProcessingService
import kz.autopersoncrop.batch.BatchStateStore
import kz.autopersoncrop.settings.OutputQuality
import kz.autopersoncrop.settings.OutputSettings
import kz.autopersoncrop.settings.OutputSettingsStore
import kz.autopersoncrop.settings.ThemeMode
import kz.autopersoncrop.settings.ThemeSettingsStore
import kz.autopersoncrop.settings.applyStoredTheme

class MainActivity : AppCompatActivity() {
    private lateinit var folderText: TextView
    private lateinit var stateText: TextView
    private lateinit var settingsText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var resumeButton: Button
    private lateinit var reprocessButton: Button
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
        applyStoredTheme()
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

        repairStaleRunState()
        if (mainScreenVisible) {
            refreshSettings()
            refreshState()
        }
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
                    message = "Предыдущая обработка прервана. Нажмите «Возобновить» — готовые фото будут пропущены.",
                ),
                sync = true,
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
                repairStaleRunState()
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

    private fun confirmReprocess() {
        val uri = treeUri ?: return toast("Сначала выберите папку")
        if (BatchStateStore(this).read().running) return toast("Сначала остановите текущую обработку")
        AlertDialog.Builder(this)
            .setTitle("Переработать заново?")
            .setMessage(
                "Все фотографии выбранной папки будут снова пропущены через текущую логику кадрирования. " +
                    "Готовые файлы в CROP будут заменены. Оригинальные фотографии не изменяются."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Переработать") { _, _ -> startBatchFor(uri, forceReprocess = true) }
            .show()
    }

    private fun startBatchFor(uri: Uri, forceReprocess: Boolean = false) {
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds else {
            @Suppress("DEPRECATION") android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        BatchProcessingService.command(
            this,
            BatchProcessingService.CMD_START,
            uri.toString(),
            bounds.width(),
            bounds.height(),
            forceReprocess = forceReprocess,
        )
    }

    private fun togglePause() {
        val state = BatchStateStore(this).read()
        val cmd = if (state.paused) BatchProcessingService.CMD_RESUME else BatchProcessingService.CMD_PAUSE
        BatchProcessingService.command(this, cmd)
    }

    private fun showSettings() {
        val themeStore = ThemeSettingsStore(this)
        val outputStore = OutputSettingsStore(this)
        val currentTheme = themeStore.read()
        val currentOutput = outputStore.read()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        val themeLabel = TextView(this).apply {
            text = "Оформление"
            setTypeface(typeface, Typeface.BOLD)
        }
        val themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ThemeMode.entries.map { it.label }
            )
            setSelection(ThemeMode.entries.indexOf(currentTheme))
        }

        val qualityLabel = TextView(this).apply {
            text = "Качество изображения"
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, 0)
        }
        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                OutputQuality.entries.map { it.label }
            )
            setSelection(OutputQuality.entries.indexOf(currentOutput.quality))
        }
        val qualityInfo = TextView(this).apply {
            text = "Размер изображения не уменьшается. «Оригинал» использует lossless JPEG crop без пересжатия; остальные варианты пересжимают только результат в выбранном качестве. Оригинал фотографии никогда не изменяется."
            textSize = 13f
            alpha = 0.78f
            setPadding(0, dp(6), 0, 0)
        }

        val backgroundLabel = TextView(this).apply {
            text = "Фоновая работа"
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, 0)
        }
        val pm = getSystemService(PowerManager::class.java)
        val batteryInfo = TextView(this).apply {
            text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
                "Системная оптимизация батареи для приложения отключена. Обработка может продолжаться при погашенном экране."
            } else {
                "Оптимизация батареи включена. На Honor рекомендуется разрешить AutoPersonCrop работу без ограничений/в фоне, иначе MagicOS может остановить длительную обработку."
            }
            textSize = 13f
            alpha = 0.78f
            setPadding(0, dp(6), 0, dp(6))
        }
        val batteryButton = Button(this).apply {
            text = "Настройки фоновой работы"
            setOnClickListener { openBatterySettings() }
        }

        box.addView(themeLabel)
        box.addView(themeSpinner)
        box.addView(qualityLabel)
        box.addView(qualitySpinner)
        box.addView(qualityInfo)
        box.addView(backgroundLabel)
        box.addView(batteryInfo)
        box.addView(batteryButton)

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(box)
            .setNegativeButton("Назад", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val selectedTheme = ThemeMode.entries[themeSpinner.selectedItemPosition]
                val selectedQuality = OutputQuality.entries[qualitySpinner.selectedItemPosition]
                themeStore.write(selectedTheme)
                outputStore.write(OutputSettings(quality = selectedQuality))
                if (selectedTheme != currentTheme) {
                    recreate()
                } else {
                    refreshSettings()
                    toast("Настройки сохранены")
                }
            }
            .show()
    }

    private fun openBatterySettings() {
        val primary = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(primary) }.onFailure { startActivity(fallback) }
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
            text = "Офлайн • автоцентрирование людей • работа в фоне"
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
        reprocessButton = Button(this).apply {
            text = "↻ Переработать заново"
            setOnClickListener { confirmReprocess() }
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
            text = "Оригиналы не изменяются. Результаты сохраняются в CROP. При «Оригинал • без пересжатия» используется lossless JPEG crop. После остановки используйте «Возобновить» — готовые фото будут пропущены."
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(18), 0, 0)
        }
        val privacy = Button(this).apply {
            text = "Конфиденциальность"
            setOnClickListener { showPrivacyPolicy() }
        }
        listOf(
            title, subtitle, developer, folderText, choose, settingsText, settings,
            progress, stateText, startButton, resumeButton, reprocessButton, pauseButton, stop, note, privacy
        ).forEach {
            root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    private fun refreshFolder() {
        if (!mainScreenVisible) return
        folderText.text = treeUri?.let { "Папка: $it" } ?: "Папка не выбрана"
        startButton.isEnabled = treeUri != null
        reprocessButton.isEnabled = treeUri != null
    }

    private fun refreshSettings() {
        if (!mainScreenVisible) return
        val theme = ThemeSettingsStore(this).read()
        val output = OutputSettingsStore(this).read()
        settingsText.text = "Фото: ${output.quality.label}   •   Тема: ${theme.label}"
    }

    private fun refreshState() {
        if (!mainScreenVisible) return
        val s = BatchStateStore(this).read()
        val done = s.completed + s.skipped + s.noPeople
        val pct = if (s.total > 0) done.toDouble() / s.total else 0.0
        progress.progress = (pct * 1000).toInt().coerceIn(0, 1000)
        stateText.text = buildString {
            append("Найдено: ${s.total}\n")
            append("Готово: ${s.completed}   Без людей: ${s.noPeople}\n")
            append("Пропущено готовых: ${s.skipped}   Ошибки: ${s.errors}\n")
            if (s.currentName.isNotBlank()) append("Сейчас: ${s.currentName}\n")
            append(if (s.running) if (s.paused) "Пауза" else s.message.ifBlank { "Обработка…" } else s.message.ifBlank { "Готов к запуску" })
        }

        val recoverableFromQueue = if (!s.running && s.folderUri.isNotBlank()) {
            runCatching {
                BatchDatabase(this).use { it.hasRecoverable(s.folderUri) }
            }.getOrDefault(false)
        } else false

        val interruptedByState = !s.running && s.folderUri.isNotBlank() && (
            s.message.contains("останов", ignoreCase = true) ||
                s.message.contains("прерван", ignoreCase = true) ||
                s.message.contains("возобнов", ignoreCase = true) ||
                s.errors > 0 ||
                (s.total > 0 && done + s.errors < s.total)
            )
        val recoverable = recoverableFromQueue || interruptedByState

        resumeButton.visibility = if (recoverable) View.VISIBLE else View.GONE
        resumeButton.isEnabled = recoverable
        startButton.isEnabled = !s.running && treeUri != null
        reprocessButton.isEnabled = !s.running && treeUri != null

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
