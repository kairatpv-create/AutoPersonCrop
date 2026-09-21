package kz.autopersoncrop.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var folderText: TextView
    private lateinit var stateText: TextView
    private lateinit var settingsText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var resumeButton: Button
    private lateinit var reprocessButton: Button
    private lateinit var errorRetryButton: Button
    private lateinit var fileListButton: Button
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
            refreshFolder()
            refreshSettings()
            refreshState()
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    @Deprecated("Legacy result API intentionally used to keep dependencies small.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        if (resultCode == RESULT_OK) {
            val resultIntent = data ?: return
            val uri = resultIntent.data ?: return
            val flags = resultIntent.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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

    private fun themeColor(attr: Int, fallback: Int): Int {
        val out = TypedValue()
        if (!theme.resolveAttribute(attr, out, true)) return fallback
        if (out.resourceId != 0) return runCatching { getColor(out.resourceId) }.getOrDefault(out.data)
        return out.data
    }

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(themeColor(android.R.attr.colorBackground, 0xFF10151D.toInt()))
        setStroke(dp(1), themeColor(android.R.attr.colorControlHighlight, 0x33000000))
    }

    private fun systemDimen(name: String, fallbackDp: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        val value = if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        return max(value, dp(fallbackDp))
    }

    /** Keeps every screen clear of status bar, navigation/gesture bar and camera cutout. */
    private fun setSafeScrollableContent(content: View) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        val fallbackTop = systemDimen("status_bar_height", 24)
        val fallbackBottom = systemDimen("navigation_bar_height", 24)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val permanent = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val visible = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(
                max(max(permanent.left, visible.left), dp(6)),
                max(max(permanent.top, visible.top), fallbackTop) + dp(6),
                max(max(permanent.right, visible.right), dp(6)),
                max(max(permanent.bottom, visible.bottom), fallbackBottom) + dp(8),
            )
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun showDialog(builder: AlertDialog.Builder): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener { dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) } }
        dialog.show()
        return dialog
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
                    message = "Предыдущая обработка прервана. Нажмите «Возобновить».",
                ),
                sync = true,
            )
        }
    }

    private fun showFolderSelectionScreen() {
        mainScreenVisible = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        val title = TextView(this).apply {
            text = "Выбор папки"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        val current = TextView(this).apply {
            text = treeUri?.let { "Текущая папка: ${displayFolderName(it)}" } ?: "Папка не выбрана"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        val open = appButton("Открыть выбор папки").apply { setOnClickListener { launchSystemFolderPicker() } }
        val back = appButton("← Назад").apply {
            setOnClickListener {
                buildMainUi(); refreshFolder(); refreshSettings(); repairStaleRunState(); refreshState()
            }
        }
        val hint = TextView(this).apply {
            text = "Доступ предоставляется только к выбранной папке. Оригиналы не изменяются."
            textSize = 13f
            alpha = 0.72f
            setPadding(0, dp(10), 0, 0)
        }
        listOf(title, current, open, back, hint).forEach {
            root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
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
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("Переработать заново?")
                .setMessage("Все фотографии будут обработаны заново, файлы в CROP будут заменены. Оригиналы не изменяются.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Переработать") { _, _ -> startBatchFor(uri, forceReprocess = true) }
        )
    }

    private fun confirmRetryErrors() {
        val state = BatchStateStore(this).read()
        val uri = treeUri ?: state.folderUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return toast("Сначала выберите папку")
        if (state.running) return toast("Сначала остановите текущую обработку")
        if (state.errors <= 0) return toast("Ошибок для переработки нет")
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("Переработать ошибки?")
                .setMessage("Будут повторно обработаны только файлы со статусом ошибки: ${state.errors}. Удачные фотографии не затрагиваются.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Переработать ошибки") { _, _ -> startBatchFor(uri, retryErrorsOnly = true) }
        )
    }

    private fun startBatchFor(
        uri: Uri,
        forceReprocess: Boolean = false,
        retryErrorsOnly: Boolean = false,
    ) {
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds
        else @Suppress("DEPRECATION") Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        BatchProcessingService.command(
            this,
            BatchProcessingService.CMD_START,
            uri.toString(),
            bounds.width(),
            bounds.height(),
            forceReprocess = forceReprocess,
            retryErrorsOnly = retryErrorsOnly,
        )
    }

    private fun togglePause() {
        val state = BatchStateStore(this).read()
        val command = if (state.paused) BatchProcessingService.CMD_RESUME else BatchProcessingService.CMD_PAUSE
        BatchProcessingService.command(this, command)
    }

    private fun showPhotoList() {
        val state = BatchStateStore(this).read()
        val folder = state.folderUri.ifBlank { treeUri?.toString().orEmpty() }
        if (folder.isBlank()) return toast("Сначала выберите папку")

        val items = runCatching { BatchDatabase(this).use { it.items(folder) } }.getOrDefault(emptyList())
        if (items.isEmpty()) return toast("Список появится после сканирования папки")

        val body = TextView(this).apply {
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setTextIsSelectable(true)
        }
        val text = SpannableStringBuilder()
        text.append("✓ обработано   ⚠ не обработано   ✕ ошибка\n… обработка   ○ ожидает   ↪ уже существовало\n\n")

        val errorColor = Color.rgb(211, 47, 47)
        val warningColor = Color.rgb(230, 126, 34)
        val processingColor = themeColor(android.R.attr.colorAccent, Color.rgb(33, 150, 243))

        items.forEach { item ->
            val start = text.length
            val marker = when (item.status) {
                BatchDatabase.DONE -> "✓"
                BatchDatabase.NO_PEOPLE -> "⚠"
                BatchDatabase.ERROR -> "✕"
                BatchDatabase.EXISTING -> "↪"
                BatchDatabase.PROCESSING -> "…"
                else -> "○"
            }
            val path = if (item.relativeDir.isBlank()) item.name else "${item.relativeDir}/${item.name}"
            val label = when (item.status) {
                BatchDatabase.NO_PEOPLE -> "  — не обработано"
                BatchDatabase.ERROR -> "  — ошибка"
                BatchDatabase.PROCESSING -> "  — обработка"
                BatchDatabase.PENDING -> "  — ожидает"
                else -> ""
            }
            text.append("$marker $path$label")
            if (item.status == BatchDatabase.ERROR && item.message.isNotBlank()) {
                text.append("\n    ${item.message.take(180)}")
            }
            text.append('\n')
            val end = text.length
            when (item.status) {
                BatchDatabase.ERROR -> {
                    text.setSpan(ForegroundColorSpan(errorColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                BatchDatabase.NO_PEOPLE -> {
                    text.setSpan(ForegroundColorSpan(warningColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                BatchDatabase.PROCESSING -> text.setSpan(
                    ForegroundColorSpan(processingColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        body.text = text

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(body)
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("Список фотографий • ${items.size}")
                .setView(scroll)
                .setPositiveButton("Закрыть", null)
        )
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
            text = "Тема"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        }
        val themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ThemeMode.entries.map { it.label })
            setSelection(ThemeMode.entries.indexOf(currentTheme))
        }
        val qualityLabel = TextView(this).apply {
            text = "Качество изображения"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, 0)
        }
        val qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, OutputQuality.entries.map { it.label })
            setSelection(OutputQuality.entries.indexOf(currentOutput.quality))
        }
        val qualityInfo = TextView(this).apply {
            text = "«Оригинал» — lossless JPEG без пересжатия. Остальные варианты пересжимают только результат."
            textSize = 13f; alpha = 0.75f; setPadding(0, dp(5), 0, 0)
        }
        val backgroundLabel = TextView(this).apply {
            text = "Работа в фоне"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(14), 0, 0)
        }
        val powerManager = getSystemService(PowerManager::class.java)
        val batteryInfo = TextView(this).apply {
            text = if (powerManager.isIgnoringBatteryOptimizations(packageName)) "Ограничение батареи отключено." else "На Honor разрешите работу без ограничений."
            textSize = 13f; alpha = 0.75f; setPadding(0, dp(5), 0, dp(5))
        }
        val batteryButton = appButton("Настройки фоновой работы").apply { setOnClickListener { openBatterySettings() } }
        listOf(themeLabel, themeSpinner, qualityLabel, qualitySpinner, qualityInfo, backgroundLabel, batteryInfo, batteryButton).forEach { box.addView(it) }

        showDialog(
            AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setView(ScrollView(this).apply { addView(box) })
                .setNegativeButton("Назад", null)
                .setPositiveButton("Сохранить") { _, _ ->
                    val selectedTheme = ThemeMode.entries[themeSpinner.selectedItemPosition]
                    val selectedQuality = OutputQuality.entries[qualitySpinner.selectedItemPosition]
                    themeStore.write(selectedTheme)
                    outputStore.write(OutputSettings(quality = selectedQuality))
                    if (selectedTheme != currentTheme) recreate() else { refreshSettings(); toast("Настройки сохранены") }
                }
        )
    }

    private fun openBatterySettings() {
        val primary = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        runCatching { startActivity(primary) }.onFailure { startActivity(fallback) }
    }

    private fun openDeveloperEmail() {
        val subject = Uri.encode("AutoPersonCrop ${BuildConfig.VERSION_NAME}")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL?subject=$subject"))
        runCatching { startActivity(intent) }.onFailure { toast("Почтовое приложение не найдено") }
    }

    private fun showAbout() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val info = TextView(this).apply {
            text = "Версия приложения: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\nАвтокадрирование людей офлайн\nОригиналы не изменяются.\nРезультаты сохраняются в CROP.\n\nДля связи с разработчиком:"
            textSize = 15f
        }
        val email = TextView(this).apply {
            text = DEVELOPER_EMAIL
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.colorAccent, 0xFF2196F3.toInt()))
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            isClickable = true
            isFocusable = true
            contentDescription = "Написать разработчику $DEVELOPER_EMAIL"
            setPadding(0, dp(10), 0, dp(12))
            setOnClickListener { openDeveloperEmail() }
        }
        val copyright = TextView(this).apply {
            text = "© 2026 Кайрат Калиев"; textSize = 14f; alpha = 0.78f
        }
        box.addView(info); box.addView(email); box.addView(copyright)
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("О приложении")
                .setView(box)
                .setNeutralButton("Конфиденциальность") { _, _ -> showPrivacyPolicy() }
                .setPositiveButton("Закрыть", null)
        )
    }

    private fun showPrivacyPolicy() {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("Конфиденциальность")
                .setMessage("Auto Person Crop обрабатывает фотографии только на устройстве.\n\n• Интернет не используется.\n• Доступ только к выбранной папке.\n• Очередь и прогресс хранятся локально.\n• Оригиналы не изменяются. Результаты — в CROP.\n• Нет рекламы, аналитики и регистрации.")
                .setPositiveButton("Понятно", null)
        )
    }

    private fun buildMainUi() {
        mainScreenVisible = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "AutoPersonCrop"
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name); textSize = 24f; setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Автокадрирование людей • офлайн"; textSize = 13f; alpha = 0.76f; setPadding(0, dp(2), 0, 0)
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(-1, -2)); addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = panelBackground()
            setPadding(dp(10), dp(10), dp(12), dp(10))
            addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
            addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        }

        folderText = TextView(this).apply { textSize = 13f; visibility = View.GONE; setPadding(dp(2), dp(2), dp(2), dp(4)) }
        val choose = appButton("Выбрать папку").apply { setOnClickListener { showFolderSelectionScreen() } }

        settingsText = TextView(this).apply { textSize = 13f; alpha = 0.78f }
        val settings = appButton("⚙ Настройки").apply { setOnClickListener { showSettings() } }
        val settingsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = panelBackground()
            setPadding(dp(12), dp(7), dp(7), dp(7))
            addView(settingsText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(settings, LinearLayout.LayoutParams(dp(150), -2))
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
        stateText = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, 0) }
        val progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = panelBackground(); setPadding(dp(12), dp(12), dp(12), dp(10))
            addView(progress, LinearLayout.LayoutParams(-1, -2)); addView(stateText, LinearLayout.LayoutParams(-1, -2))
        }

        fileListButton = appButton("Список фотографий").apply { setOnClickListener { showPhotoList() } }
        startButton = appButton("Обработать всё").apply { setOnClickListener { startBatch() } }
        resumeButton = appButton("▶ Возобновить").apply { visibility = View.GONE; setOnClickListener { resumeBatch() } }
        errorRetryButton = appButton("↻ Переработать ошибки").apply { visibility = View.GONE; setOnClickListener { confirmRetryErrors() } }
        reprocessButton = appButton("↻ Переработать заново").apply { setOnClickListener { confirmReprocess() } }
        pauseButton = appButton("Пауза").apply { setOnClickListener { togglePause() } }
        val stopButton = appButton("Остановить").apply {
            setOnClickListener { BatchProcessingService.command(this@MainActivity, BatchProcessingService.CMD_STOP) }
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val note = TextView(this).apply {
            text = "Оригиналы не изменяются  •  Результаты: CROP"; textSize = 13f; alpha = 0.76f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        val about = appButton("О приложении").apply { setOnClickListener { showAbout() } }

        fun add(view: View, bottom: Int = 8) {
            root.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) })
        }
        add(header, 10); add(folderText, 2); add(choose, 8); add(settingsRow, 9); add(progressPanel, 7)
        add(fileListButton, 9); add(startButton, 5); add(resumeButton, 5); add(errorRetryButton, 5); add(reprocessButton, 5)
        add(actionRow, 6); add(note, 5); add(about, 0)
        setSafeScrollableContent(root)
    }

    private fun refreshFolder() {
        if (!mainScreenVisible) return
        if (treeUri == null) {
            folderText.text = ""; folderText.visibility = View.GONE
        } else {
            folderText.text = "Папка: ${displayFolderName(treeUri!!)}"; folderText.visibility = View.VISIBLE
        }
        startButton.isEnabled = treeUri != null
        reprocessButton.isEnabled = treeUri != null
        fileListButton.isEnabled = treeUri != null
    }

    private fun displayFolderName(uri: Uri): String = runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        id.substringAfterLast(':').substringAfterLast('/').ifBlank { id }
    }.getOrElse { uri.lastPathSegment ?: "Выбранная папка" }

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
        val ratio = if (state.total > 0) done.toDouble() / state.total else 0.0
        progress.progress = (ratio * 1000).toInt().coerceIn(0, 1000)

        stateText.text = buildString {
            append("Найдено: ${state.total}    Готово: ${state.completed}\n")
            append("Без людей: ${state.noPeople}    Пропущено: ${state.skipped}\n")
            append("Ошибки: ${state.errors}\n")
            if (state.currentName.isNotBlank()) append("Сейчас: ${state.currentName}\n")
            append(if (state.running) { if (state.paused) "Пауза" else state.message.ifBlank { "Обработка…" } } else state.message.ifBlank { "Готов к запуску" })
        }

        val recoverableFromQueue = if (!state.running && state.folderUri.isNotBlank()) {
            runCatching { BatchDatabase(this).use { it.hasRecoverable(state.folderUri) } }.getOrDefault(false)
        } else false
        val interrupted = !state.running && state.folderUri.isNotBlank() && (
            state.message.contains("останов", true) || state.message.contains("прерван", true) ||
                state.message.contains("возобнов", true) || (state.total > 0 && done + state.errors < state.total)
            )
        val recoverable = recoverableFromQueue || interrupted

        resumeButton.visibility = if (recoverable) View.VISIBLE else View.GONE
        resumeButton.isEnabled = recoverable
        errorRetryButton.visibility = if (state.errors > 0) View.VISIBLE else View.GONE
        errorRetryButton.text = if (state.errors > 0) "↻ Переработать ошибки (${state.errors})" else "↻ Переработать ошибки"
        errorRetryButton.isEnabled = !state.running && state.errors > 0

        val problems = state.noPeople + state.errors
        fileListButton.text = when {
            state.total <= 0 -> "Список фотографий"
            problems > 0 -> "Список фотографий • проблем: $problems"
            else -> "Список фотографий • ${state.total}"
        }
        fileListButton.isEnabled = state.folderUri.isNotBlank() || treeUri != null

        startButton.isEnabled = !state.running && treeUri != null
        reprocessButton.isEnabled = !state.running && treeUri != null
        pauseButton.text = if (state.paused) "Продолжить" else "Пауза"
        pauseButton.isEnabled = state.running
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
        private const val DEVELOPER_EMAIL = "kairat.pv@gmail.com"
    }
}
