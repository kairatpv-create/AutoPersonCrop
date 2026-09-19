package kz.autopersoncrop.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kz.autopersoncrop.BuildConfig
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun appButton(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        minHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
    }

    /**
     * Android 15/16 draw apps edge-to-edge. Apply the actual status/navigation/cutout insets
     * around every full-screen page so controls never disappear under system UI.
     */
    private fun setSafeScrollableContent(content: View) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun repairStaleRunState() {
        val store = BatchStateStore(this)
        val state = store.read()
        if (state.running && !BatchProcessingService.serviceActive) {
            store.write(
                state.copy(
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val title = TextView(this).apply {
            text = "Выбор папки"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        val current = TextView(this).apply {
            text = treeUri?.let { "Текущая папка: ${displayFolderName(it)}" } ?: "Папка пока не выбрана"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        val open = appButton("Открыть выбор папки").apply {
            setOnClickListener { launchSystemFolderPicker() }
        }
        val back = appButton("← Назад").apply {
            setOnClickListener {
                buildMainUi()
                refreshFolder()
                refreshSettings()
                repairStaleRunState()
                refreshState()
            }
        }
        val hint = TextView(this).apply {
            text = "Приложение получает доступ только к выбранной вами папке."
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(10), 0, 0)
        }
        listOf(title, current, open, back, hint).forEach {
            root.addView(
                it,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            )
        }
        setSafeScrollableContent(root)
    }

    private fun launchSystemFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        @Suppress("DEPRECATION") startActivityForResult(intent, REQ_TREE)
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
            @Suppress("DEPRECATION") android.graphics.Rect(
                0,
                0,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
            )
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
        val command = if (state.paused) BatchProcessingService.CMD_RESUME else BatchProcessingService.CMD_PAUSE
        BatchProcessingService.command(this, command)
    }

    private fun showSettings() {
        val themeStore = ThemeSettingsStore(this)
        val outputStore = OutputSettingsStore(this)
        val currentTheme = themeStore.read()
        val currentOutput = outputStore.read()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val themeLabel = TextView(this).apply {
            text = "Тема"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                ThemeMode.entries.map { it.label },
            )
            setSelection(ThemeMode.entries.indexOf(currentTheme))
        }

        val qualityLabel = TextView(this).apply {
            text = "Качество изображения"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        }
        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                OutputQuality.entries.map { it.label },
            )
            setSelection(OutputQuality.entries.indexOf(currentOutput.quality))
        }
        val qualityInfo = TextView(this).apply {
            text = "«Оригинал» сохраняет lossless JPEG crop без пересжатия. Остальные варианты пересжимают только готовый результат."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(5), 0, 0)
        }

        val backgroundLabel = TextView(this).apply {
            text = "Работа в фоне"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        }
        val powerManager = getSystemService(PowerManager::class.java)
        val batteryInfo = TextView(this).apply {
            text = if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                "Ограничение батареи отключено — обработка может продолжаться при погашенном экране."
            } else {
                "Для длительной обработки на Honor разрешите приложению работу без ограничений."
            }
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(5), 0, dp(5))
        }
        val batteryButton = appButton("Настройки фоновой работы").apply {
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

        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(scroll)
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

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("О приложении")
            .setMessage(
                "${getString(R.string.app_name)}  ${BuildConfig.VERSION_NAME}\n\n" +
                    "Автоматическая обрезка фотографий с распознаванием людей. Работает полностью офлайн. " +
                    "Оригиналы не изменяются, готовые фотографии сохраняются в папке CROP.\n\n" +
                    getString(R.string.developer_label)
            )
            .setNeutralButton("Конфиденциальность") { _, _ -> showPrivacyPolicy() }
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun showPrivacyPolicy() {
        AlertDialog.Builder(this)
            .setTitle("Конфиденциальность")
            .setMessage(
                "Auto Person Crop обрабатывает фотографии только на вашем устройстве.\n\n" +
                    "• Интернет не используется, фотографии и результаты никуда не отправляются.\n" +
                    "• Доступ предоставляется только к выбранной вами папке.\n" +
                    "• Очередь и прогресс сохраняются локально для продолжения после прерывания.\n" +
                    "• Оригиналы не изменяются. Результаты сохраняются в CROP.\n" +
                    "• В приложении нет рекламы, аналитики, регистрации и облачного хранилища."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun buildMainUi() {
        mainScreenVisible = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(18))
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Автокадрирование людей • полностью офлайн"
            textSize = 13f
            alpha = 0.78f
            setPadding(0, dp(3), 0, dp(12))
        }

        folderText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(5))
        }
        val choose = appButton("Выбрать папку").apply {
            setOnClickListener { showFolderSelectionScreen() }
        }

        val settingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        settingsText = TextView(this).apply {
            textSize = 13f
            alpha = 0.78f
        }
        val settings = appButton("⚙ Настройки").apply {
            textSize = 14f
            setOnClickListener { showSettings() }
        }
        settingsRow.addView(
            settingsText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        settingsRow.addView(
            settings,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        stateText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
        }

        startButton = appButton("Обработать всё").apply {
            setOnClickListener { startBatch() }
        }
        resumeButton = appButton("▶ Возобновить").apply {
            visibility = View.GONE
            setOnClickListener { resumeBatch() }
        }
        reprocessButton = appButton("↻ Переработать заново").apply {
            textSize = 14f
            setOnClickListener { confirmReprocess() }
        }
        pauseButton = appButton("Пауза").apply {
            setOnClickListener { togglePause() }
        }
        val stopButton = appButton("Остановить").apply {
            setOnClickListener {
                BatchProcessingService.command(this@MainActivity, BatchProcessingService.CMD_STOP)
            }
        }
        val runningControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        runningControls.addView(
            pauseButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            },
        )
        runningControls.addView(
            stopButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            },
        )

        val note = TextView(this).apply {
            text = "Оригиналы не изменяются  •  Результаты: CROP"
            textSize = 13f
            alpha = 0.76f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val about = appButton("О приложении").apply {
            textSize = 14f
            setOnClickListener { showAbout() }
        }

        fun add(view: View, bottom: Int = 7) {
            root.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(bottom) },
            )
        }

        add(title, 0)
        add(subtitle, 4)
        add(folderText, 0)
        add(choose, 5)
        add(settingsRow, 6)
        add(progress, 2)
        add(stateText, 4)
        add(startButton)
        add(resumeButton)
        add(reprocessButton)
        add(runningControls)
        add(note, 4)
        add(about, 0)

        setSafeScrollableContent(root)
    }

    private fun displayFolderName(uri: Uri): String = runCatching {
        val decoded = Uri.decode(DocumentsContract.getTreeDocumentId(uri))
        decoded.substringAfter(':', decoded).ifBlank { decoded }
    }.getOrElse {
        Uri.decode(uri.lastPathSegment ?: "выбрана")
    }

    private fun refreshFolder() {
        if (!mainScreenVisible) return
        folderText.text = treeUri?.let { "Папка: ${displayFolderName(it)}" } ?: "Папка не выбрана"
        startButton.isEnabled = treeUri != null
        reprocessButton.isEnabled = treeUri != null
    }

    private fun refreshSettings() {
        if (!mainScreenVisible) return
        val theme = ThemeSettingsStore(this).read()
        val output = OutputSettingsStore(this).read()
        settingsText.text = "${output.quality.label}\n${theme.label}"
    }

    private fun refreshState() {
        if (!mainScreenVisible) return
        val state = BatchStateStore(this).read()
        val done = state.completed + state.skipped + state.noPeople
        val pct = if (state.total > 0) done.toDouble() / state.total else 0.0
        progress.progress = (pct * 1000).toInt().coerceIn(0, 1000)
        stateText.text = buildString {
            append("Найдено: ${state.total}    Готово: ${state.completed}\n")
            append("Без людей: ${state.noPeople}    Пропущено: ${state.skipped}    Ошибки: ${state.errors}\n")
            if (state.currentName.isNotBlank()) append("Сейчас: ${state.currentName}\n")
            append(
                if (state.running) {
                    if (state.paused) "Пауза" else state.message.ifBlank { "Обработка…" }
                } else {
                    state.message.ifBlank { "Готов к запуску" }
                }
            )
        }

        val recoverableFromQueue = if (!state.running && state.folderUri.isNotBlank()) {
            runCatching {
                BatchDatabase(this).use { it.hasRecoverable(state.folderUri) }
            }.getOrDefault(false)
        } else false

        val interruptedByState = !state.running && state.folderUri.isNotBlank() && (
            state.message.contains("останов", ignoreCase = true) ||
                state.message.contains("прерван", ignoreCase = true) ||
                state.message.contains("возобнов", ignoreCase = true) ||
                state.errors > 0 ||
                (state.total > 0 && done + state.errors < state.total)
            )
        val recoverable = recoverableFromQueue || interruptedByState

        resumeButton.visibility = if (recoverable) View.VISIBLE else View.GONE
        resumeButton.isEnabled = recoverable
        startButton.isEnabled = !state.running && treeUri != null
        reprocessButton.isEnabled = !state.running && treeUri != null

        pauseButton.text = if (state.paused) "Продолжить" else "Пауза"
        pauseButton.isEnabled = state.running
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_TREE = 1001
        private const val REQ_NOTIFICATIONS = 1002
    }
}
