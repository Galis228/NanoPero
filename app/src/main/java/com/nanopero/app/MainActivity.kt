package com.nanopero.app

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : FragmentActivity() {
    private val tab = mutableIntStateOf(0)
    private val record = mutableStateOf(false)
    private val incoming = mutableStateOf("")
    private val more = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent, savedInstanceState == null)
        setContent {
            NanoTheme {
                val locked by AppLock.locked
                val s = app.settings
                when {
                    locked -> LockScreen { AppLock.prompt(this) }
                    !s.onboarded -> Onboarding { s.onboarded = true }
                    else -> MainScreen(tab, record, incoming, more)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (AppLock.locked.value) AppLock.prompt(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, true)
    }

    private fun handle(i: Intent?, fresh: Boolean) {
        if (i == null || !fresh) return
        i.getStringExtra(EXTRA_TEXT)?.let { incoming.value = it; tab.intValue = TAB_CHAT }
        if (i.hasExtra(EXTRA_TAB)) tab.intValue = i.getIntExtra(EXTRA_TAB, 0)
        i.getStringExtra(EXTRA_MORE)?.let { tab.intValue = TAB_MORE; more.value = it }
        if (i.getBooleanExtra(EXTRA_RECORD, false)) { tab.intValue = TAB_DIARY; record.value = true }
    }

    companion object {
        const val EXTRA_TEXT = "nanopero.text"
        const val EXTRA_TAB = "nanopero.tab"
        const val EXTRA_RECORD = "nanopero.record"
        const val EXTRA_MORE = "nanopero.more"
        const val TAB_CHAT = 0
        const val TAB_DIGEST = 1
        const val TAB_DIARY = 2
        const val TAB_MORE = 3
    }
}

private data class TabDef(val title: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector,
                          val iconSel: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    TabDef("НаноПеро", "Чат", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
    TabDef("Сводка", "Сводка", Icons.Outlined.Summarize, Icons.Rounded.Summarize),
    TabDef("Дневник", "Дневник", Icons.Outlined.Mic, Icons.Rounded.Mic),
    TabDef("Ещё", "Ещё", Icons.Outlined.Widgets, Icons.Rounded.Widgets),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(tabState: MutableIntState, record: MutableState<Boolean>, incoming: MutableState<String>,
               moreState: MutableState<String?>) {
    var tab by tabState
    var more by moreState
    val view = rememberHaptics()
    LaunchedEffect(Unit) {
        if (ErrorLog.hadCrash) {
            ErrorLog.ackCrash()
            kotlinx.coroutines.delay(600)
            SnackHost.undo("Прошлый раз приложение закрылось с ошибкой. Отчёт — в «Ещё → Журнал ошибок»") {}
        }
    }
    // Раз в сутки — тихая проверка обновлений на GitHub; при находке — короткое уведомление внизу
    val ctxU = LocalContext.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        runCatching { Updates.autoCheck(ctxU) }
        Updates.available?.let { r -> SnackHost.action("Доступна НаноПеро ${r.versionName}", "Подробнее") { tab = MainActivity.TAB_MORE; more = null } }
    }
    LaunchedEffect(ChatState.openModels) {
        if (ChatState.openModels) { ChatState.openModels = false; tab = MainActivity.TAB_MORE; more = "model" }
    }
    LaunchedEffect(incoming.value) { if (incoming.value.isNotEmpty()) { ChatState.draft = incoming.value; incoming.value = "" } }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    BackHandler(enabled = more != null) { more = null }
    BackHandler(enabled = more == null && tab != MainActivity.TAB_CHAT) { tab = MainActivity.TAB_CHAT }

    @OptIn(ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    val adaptive = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
    val digestOn = LocalContext.current.app.settings.digestEnabled
    LaunchedEffect(digestOn) { if (!digestOn && tab == MainActivity.TAB_DIGEST) tab = MainActivity.TAB_CHAT }
    NavigationSuiteScaffold(
        // Пока открыта клавиатура, нижняя панель прячется — поле ввода прилегает к клавиатуре
        layoutType = if (imeVisible && adaptive == NavigationSuiteType.NavigationBar) NavigationSuiteType.None else adaptive,
        navigationSuiteItems = {
            tabs.forEachIndexed { i, t ->
                if (i == MainActivity.TAB_DIGEST && !digestOn) return@forEachIndexed
                item(
                    selected = tab == i,
                    onClick = { if (tab != i) view.tick(); if (tab == i) more = null; tab = i },
                    icon = { Icon(if (tab == i) t.iconSel else t.icon, null) },
                    label = { Text(t.label) }
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            snackbarHost = { SnackbarHost(SnackHost.state) },
            topBar = {
                TopAppBar(
                    scrollBehavior = scroll,
                    navigationIcon = {
                        if (tab == MainActivity.TAB_MORE && more != null)
                            IconButton({ more = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (tab == MainActivity.TAB_CHAT) {
                                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                            }
                            AnimatedContent(if (tab == MainActivity.TAB_MORE && more != null) moreTitle(more!!) else tabs[tab].title,
                                transitionSpec = { (fadeIn(tween(180, 60)) + slideInVertically(tween(220)) { it / 3 }) togetherWith fadeOut(tween(120)) },
                                label = "title") { title -> Text(title, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                )
            }
        ) { pad ->
            // Лёгкие переходы Material 3: вкладки — «затухание с проявлением», подэкраны — сдвиг вперёд/назад
            val target = tab to (if (tab == MainActivity.TAB_MORE) more else null)
            Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
                AnimatedContent(target, transitionSpec = {
                    val (fromTab, fromMore) = initialState; val (toTab, toMore) = targetState
                    if (fromTab == toTab && fromTab == MainActivity.TAB_MORE) {
                        val forward = fromMore == null && toMore != null
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(280, easing = EaseOutCubic)) { w -> dir * w / 5 } + fadeIn(tween(220, 40))) togetherWith
                                (slideOutHorizontally(tween(220, easing = EaseInCubic)) { w -> -dir * w / 8 } + fadeOut(tween(140)))
                    } else {
                        (fadeIn(tween(220, 70)) + scaleIn(tween(260, 50, EaseOutCubic), initialScale = 0.97f)) togetherWith fadeOut(tween(90))
                    }.using(SizeTransform(clip = false))
                }, label = "screen", contentKey = { it }) { (t, m) ->
                    when (t) {
                        MainActivity.TAB_CHAT -> ChatTab()
                        MainActivity.TAB_DIGEST -> SummaryTab()
                        MainActivity.TAB_DIARY -> { DiaryTab(record.value); LaunchedEffect(Unit) { record.value = false } }
                        else -> when (m) {
                            null -> MoreMenu { more = it }
                            "history" -> HistoryTab { ChatState.draft = it; more = null; tab = MainActivity.TAB_CHAT }
                            "cards" -> CardsTab()
                            "model" -> ModelTab()
                            "settings" -> SettingsTab()
                            "presets" -> PresetsTab()
                            "backup" -> BackupTab()
                            "keyboard" -> KeyboardSetupTab()
                            "bubble" -> BubbleTab()
                            "errors" -> ErrorLogTab()
                            else -> MoreMenu { more = it }
                        }
                    }
                }
            }
        }
    }
}

private fun moreTitle(k: String) = moreItems.firstOrNull { it.key == k }?.title ?: "Ещё"

private data class MoreItem(val key: String, val title: String, val sub: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val moreItems = listOf(
    MoreItem("history", "История", "Все результаты с поиском", Icons.Rounded.History),
    MoreItem("cards", "Карточки", "Повторение по интервалам", Icons.Rounded.Style),
    MoreItem("keyboard", "Клавиатура ✦", "ИИ-кнопка в любом поле ввода", Icons.Rounded.Keyboard),
    MoreItem("bubble", "Плавающая кнопка", "Обработать буфер одним касанием", Icons.Rounded.BubbleChart),
    MoreItem("presets", "Пресеты", "Свои стили текста", Icons.Rounded.Tune),
    MoreItem("model", "Модель", "Gemini Nano или файл-модель", Icons.Rounded.Memory),
    MoreItem("settings", "Настройки", "Язык, сводка, приватность, замок", Icons.Rounded.Settings),
    MoreItem("backup", "Резервная копия", "Экспорт в Markdown / JSON и импорт", Icons.Rounded.SaveAlt),
    MoreItem("errors", "Журнал ошибок", "Отчёт для разработчика одной кнопкой", Icons.Rounded.BugReport),
)

@Composable
fun MoreMenu(onOpen: (String) -> Unit) {
    val view = rememberHaptics()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UpdateBanner()
        moreItems.forEachIndexed { i, it ->
            val shape = when (i) {
                0 -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
                moreItems.lastIndex -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                else -> RoundedCornerShape(6.dp)
            }
            Surface(onClick = { view.tick(); onOpen(it.key) }, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
                ListItem(
                    headlineContent = { Text(it.title) },
                    supportingContent = { Text(it.sub) },
                    leadingContent = {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Icon(it.icon, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("НаноПеро ${BuildConfig.VERSION_NAME} · всё работает на телефоне",
            Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun ModelTab() {
    val ctx = LocalContext.current
    val s = ctx.app.settings
    val ai = ctx.app.ai
    val scope = rememberCoroutineScope()
    var nanoState by remember { mutableStateOf(NanoState.UNKNOWN) }
    var nanoMsg by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(ai.local.models()) }
    var delFile by remember { mutableStateOf<java.io.File?>(null) }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { nanoState = ai.nano.state() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importError = null; importProgress = 0f
            try {
                val f = ai.local.import(uri) { importProgress = it }
                models = ai.local.models()
                if (s.activeModel != f.name) ai.local.releaseAsync()
                s.activeModel = f.name; s.engine = EngineChoice.LOCAL.name
                if ((f.name.endsWith(".litertlm") || f.name.endsWith(".gguf")) && s.maxTokens < 4096) s.maxTokens = 4096
                if ("ekv1280" in f.name && s.maxTokens > 1024) s.maxTokens = 1024
            } catch (e: Throwable) {
                importError = "Не удалось импортировать: ${e.message}"
            } finally { importProgress = null }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        Text("Какой движок использовать", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EngineChoice.entries.forEachIndexed { i, e ->
                SegmentedButton(
                    selected = s.engineChoice == e, onClick = { s.engine = e.name },
                    shape = SegmentedButtonDefaults.itemShape(i, EngineChoice.entries.size)
                ) { Text(e.title, maxLines = 1) }
            }
        }
        Text("«Авто» берёт Gemini Nano, если телефон её поддерживает, иначе — твой файл-модель.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // --- Gemini Nano ---
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                    Text("Gemini Nano (AICore)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    val ok = nanoState == NanoState.AVAILABLE
                    Text(nanoState.title, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                }
                Text("Встроенная модель Google. Официально — Pixel 9 и новее, Galaxy S25 и ряд других; на Pixel 8 Pro — " +
                        "через «Параметры разработчика → AICore». Без интернета, ничего не уходит в облако.", style = MaterialTheme.typography.bodySmall)
                if (nanoState == NanoState.AVAILABLE) {
                    if (s.engineChoice == EngineChoice.LOCAL) FilledTonalButton({ s.engine = EngineChoice.AUTO.name }) { Text("Использовать Gemini Nano") }
                    else Text("Используется для чата, текста и сводок. В клавиатуре и пузыре (фоном) работает файл-модель, если она скачана — " +
                            "AICore не отвечает фоновым приложениям.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (nanoState == NanoState.UNAVAILABLE || nanoState == NanoState.ERROR) {
                    Text("Если ты только что включил Nano: обнови «AICore» и «Private Compute Services» в Play Маркете, " +
                            "перезагрузи телефон и нажми «Проверить снова». Первая загрузка модели в AICore может идти до 30 минут на Wi-Fi и зарядке.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=com.google.android.aicore")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("AICore") }
                        OutlinedButton({
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("market://details?id=com.google.android.as.oss")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Private Compute") }
                    }
                }
                if (ai.nano.lastDetail.isNotBlank()) Text(ai.nano.lastDetail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (nanoState == NanoState.DOWNLOADABLE || nanoState == NanoState.DOWNLOADING) {
                    Button(onClick = {
                        scope.launch {
                            runCatching { ai.nano.download().collect { nanoMsg = it } }
                                .onFailure { nanoMsg = "Ошибка: ${it.message}" }
                            nanoState = ai.nano.state()
                            if (nanoState == NanoState.AVAILABLE && s.engineChoice == EngineChoice.LOCAL) s.engine = EngineChoice.AUTO.name
                        }
                    }) { Text("Скачать Gemini Nano") }
                }
                if (nanoMsg.isNotBlank()) Text(nanoMsg, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { nanoState = NanoState.UNKNOWN; nanoState = ai.nano.state() } }) {
                    Text("Проверить снова")
                }
            }
        }

        SharedModelsCard { models = ai.local.models() }

        // --- Локальная модель ---
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Memory, null); Spacer(Modifier.width(8.dp))
                    Text("Локальная модель-файл", style = MaterialTheme.typography.titleSmall)
                }
                Text("Работает на любом современном телефоне, в т.ч. Pixel 8 Pro. Скачай файл модели " +
                        "(.litertlm, .task или .gguf) и импортируй его сюда. Интернет нужен только для скачивания.",
                    style = MaterialTheme.typography.bodySmall)
                ModelStore { models = ai.local.models() }
                if (models.isEmpty()) Text("Пока нет моделей", color = MaterialTheme.colorScheme.onSurfaceVariant)
                models.forEach { f ->
                    ModelRow(f, f.name == s.activeModel,
                        onSelect = {
                            s.activeModel = f.name; s.engine = EngineChoice.LOCAL.name; ai.local.releaseAsync()
                            if ((f.name.endsWith(".litertlm") || f.name.endsWith(".gguf")) && s.maxTokens < 4096) s.maxTokens = 4096
                        },
                        onDelete = { delFile = f })
                }
                delFile?.let { f ->
                    DeleteModelDialog(f.name, f, fmtBytes(f.length()), onConfirm = {
                        if (s.activeModel == f.name) { s.activeModel = ""; ai.local.releaseAsync() }
                        f.delete(); models = ai.local.models(); delFile = null
                    }, onDismiss = { delFile = null })
                }
                importProgress?.let {
                    LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth())
                    Text("Копирую файл… ${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = importProgress == null) {
                        Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Импорт файла")
                    }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/litert-community")))
                    }) { Text("Другие модели") }
                }
            }
        }

        SpeedTestCard()

        // --- Параметры локальной модели ---
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Параметры файл-модели", style = MaterialTheme.typography.titleSmall)
                Text("Формат промпта", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Template.entries.forEach { t ->
                        FilterChip(s.templateChoice == t, { s.template = t.name }, { Text(t.title, maxLines = 1) })
                    }
                }
                Text("Только для .task. «Авто» выбирает формат по названию файла (qwen → ChatML, gemma → Gemma). " +
                        "Файлы .litertlm сами знают свой формат.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Контекст (вход + ответ): ${s.maxTokens} токенов", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1024, 2048, 4096, 8192).forEach { n ->
                        FilterChip(s.maxTokens == n, { s.maxTokens = n; ai.local.releaseAsync() }, { Text("$n") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ускорение на GPU", style = MaterialTheme.typography.labelLarge)
                        Text("Быстрее, но не все модели поддерживают", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(s.useGpu, { s.useGpu = it; ai.local.releaseAsync() })
                }
            }
        }
    }
}

@Composable
private fun ModelRow(f: File, active: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(active, onSelect)
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text("%.2f ГБ".format(f.length() / 1_073_741_824f), style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onDelete) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
    }
}

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    val app = ctx.app
    val s = app.settings
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        s.reminderOn = ok; Reminder.apply(ctx)
    }
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
    SetSearch(q, { q = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    CompositionLocalProvider(LocalSetQuery provides q) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        SetSection("Текст и ответы") {
            SetChoice(Icons.Rounded.Translate, "Язык для «Перевести»", listOf("английский", "русский", "украинский", "польский", "немецкий", "испанский")
                .map { it to it.replaceFirstChar { c -> c.uppercase() } }, s.translateTo) { s.translateTo = it }
            SetSlider(Icons.Rounded.Tune, "Креативность", "%.1f".format(s.temperature), s.temperature, 0f..1f, steps = 9) { s.temperature = (it * 10).toInt() / 10f }
            SetText("Ниже — точнее и стабильнее, выше — разнообразнее.")
            SetSwitch(Icons.Rounded.History, "Сохранять историю", "Все результаты остаются только на телефоне", s.historyEnabled) { s.historyEnabled = it }
            SetSwitch(Icons.Rounded.Fingerprint, "Замок по отпечатку", if (AppLock.available(ctx)) "Отпечаток или PIN при открытии"
                else "На телефоне не настроен отпечаток/PIN", s.lockEnabled) { on ->
                if (!on) s.lockEnabled = false
                else (ctx as? androidx.fragment.app.FragmentActivity)?.let { act -> AppLock.prompt(act) { s.lockEnabled = true } }
            }
        }

        SetSection("Сводка уведомлений") {
            SetSwitch(Icons.Rounded.Inbox, "Сводка сообщений", "Собирать сообщения из мессенджеров. Выключи — вкладка «Сводка» исчезнет", s.digestEnabled) { on ->
                s.digestEnabled = on
                if (!on) SnackHost.action("Сводка выключена", "Удалить собранное") { app.stores.notifs.clear() }
            }
            if (s.digestEnabled) SetSwitch(Icons.Rounded.Apps, "Все приложения", "Иначе — только мессенджеры и почта", s.notifAllApps) { s.notifAllApps = it }
            SetSwitch(Icons.Rounded.GppMaybe, "Защита от развода в SMS", "Предупреждать о подозрительных SMS", s.smsGuard) { s.smsGuard = it }
            SetSwitch(Icons.Rounded.Notifications, "Напоминание вечером", "«Пора сделать сводку дня»", s.reminderOn) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { s.reminderOn = on; Reminder.apply(ctx) }
            }
            if (s.reminderOn) SetSlider(Icons.Rounded.Schedule, "Время напоминания", "${s.reminderHour}:00", s.reminderHour.toFloat(), 17f..23f, steps = 5,
                onFinished = { Reminder.apply(ctx) }) { s.reminderHour = it.toInt() }
        }

        UpdatesSettings()

        SetSection("Батарея") {
            SetSlider(Icons.Rounded.Memory, "Выгружать модель из памяти через", when {
                s.unloadSec < 60 -> "${s.unloadSec} с"; else -> "${s.unloadSec / 60} мин" }, s.unloadSec.toFloat(), 15f..600f) {
                s.unloadSec = (if (it < 60) (it / 15).toInt() * 15 else (it / 60).toInt() * 60).coerceAtLeast(15)
            }
            SetText("Меньше — экономнее память и батарея, но каждый новый запрос ждёт загрузки модели (5–15 с). " +
                    "Когда приложение свёрнуто, модель выгружается через 15 с в любом случае.")
            SetSwitch(Icons.Rounded.BatteryChargingFull, "Тяжёлое — только на зарядке", "Длинные тексты и сводки по частям не запускаются без зарядки",
                s.heavyOnCharge) { s.heavyOnCharge = it }
        }

        SetSection("Голосовой дневник") {
            SetChoice(Icons.Rounded.RecordVoiceOver, "Язык распознавания", listOf("ru-RU" to "Русский", "uk-UA" to "Українська", "en-US" to "English"), s.speechLang) { s.speechLang = it }
            SetText("Для полного офлайна скачай язык: Настройки телефона → Система → Языки → Распознавание речи на устройстве. " +
                    "Быстрая запись: плитка «Голосовая заметка» в шторке.")
        }
        if (q.isBlank()) {
        val (tx, rx) = remember { trafficBytes() }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Приватность", style = MaterialTheme.typography.titleSmall)
                Text("Трафик НаноПеро с включения телефона: отправлено ${fmtBytes(tx)}, получено ${fmtBytes(rx)}.")
                Text("Сеть используется только для загрузки статей в «Прочитать потом» и скачивания Gemini Nano. " +
                        "Тексты, сообщения и голос обрабатываются на телефоне.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Как пользоваться", style = MaterialTheme.typography.titleSmall)
                Text("• Выдели текст в любом приложении → в меню (иногда под ⋮) «НаноПеро ✦». " +
                        "В полях ввода кнопка «Заменить» подставит результат на место.")
                Text("• «Поделиться» → НаноПеро — обработать текст или «Отложить» статью.")
                Text("• Плитка «НаноПеро: буфер» в шторке берёт скопированный текст.")
                Text("• Виджет на рабочем столе: буфер, голосовая заметка, сводка.")
                Text("• Плитка «Голосовая заметка» — запись в дневник в одно касание.")
            }
        }
        }
    }
    }
    }
}

@Composable
fun SwitchRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
fun PresetsTab() {
    val store = LocalContext.current.app.stores.presets
    var editing by remember { mutableStateOf<Preset?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Свои стили появляются рядом с обычными режимами — и в приложении, и в меню выделения текста.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        store.items.toList().forEach { p ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text(p.instruction, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    IconButton({ editing = p }) { Icon(Icons.Rounded.Edit, "Изменить") }
                    IconButton({ store.remove { it.id == p.id } }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                }
            }
        }
        Button({ editing = Preset(0, "", "") }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Новый пресет") }
    }
    editing?.let { p ->
        var name by remember(p) { mutableStateOf(p.name) }
        var instr by remember(p) { mutableStateOf(p.instruction) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (p.id == 0L) "Новый пресет" else "Пресет") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(30) }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(instr, { instr = it }, label = { Text("Что сделать с текстом") }, minLines = 3,
                        placeholder = { Text("Например: перепиши как отзыв на 5 звёзд, коротко и по делу") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && instr.isNotBlank(), onClick = {
                    if (p.id == 0L) store.items.add(Preset(newId(), name.trim(), instr.trim())).also { store.save() }
                    else store.update({ it.id == p.id }) { it.copy(name = name.trim(), instruction = instr.trim()) }
                    editing = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton({ editing = null }) { Text("Отмена") } }
        )
    }
}

