package com.nanopero.app

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch

data class EmojiItem(val cat: Int, val e: String, val skin: Boolean, val kw: String)

object Emojis {
    @Volatile var all: List<EmojiItem> = emptyList(); private set
    val catIcons = listOf(
        Icons.Rounded.EmojiEmotions, Icons.Rounded.EmojiPeople, Icons.Rounded.EmojiNature, Icons.Rounded.EmojiFoodBeverage,
        Icons.Rounded.EmojiTransportation, Icons.Rounded.EmojiEvents, Icons.Rounded.EmojiObjects, Icons.Rounded.EmojiSymbols,
        Icons.Rounded.EmojiFlags
    )
    val catNames = listOf("Смайлы", "Люди", "Природа", "Еда", "Путешествия", "Занятия", "Предметы", "Символы", "Флаги")
    val tones = listOf("", "🏻", "🏼", "🏽", "🏾", "🏿")

    fun load(ctx: Context) {
        if (all.isNotEmpty()) return
        all = ctx.assets.open("emoji.tsv").bufferedReader().readLines().mapNotNull { l ->
            val p = l.split('\t'); if (p.size < 4) null else EmojiItem(p[0].toInt(), p[1], p[2] == "1", p[3])
        }
    }

    /** Применить тон кожи: модификатор после первого символа (вместо селектора FE0F). */
    fun withTone(e: String, tone: Int): String {
        if (tone == 0) return e
        val first = e.codePointAt(0)
        val len = Character.charCount(first)
        var rest = e.substring(len)
        if (rest.startsWith("️")) rest = rest.substring(1)
        return String(Character.toChars(first)) + tones[tone] + rest
    }

    /** Слово → эмодзи (для подсказок при наборе): «кот» → 🐱. */
    @Volatile private var byWord: Map<String, List<String>> = emptyMap()

    fun buildIndex() {
        if (byWord.isNotEmpty() || all.isEmpty()) return
        val m = HashMap<String, MutableList<String>>()
        all.filter { it.cat != 8 }.forEach { e ->
            e.kw.split(" | ").filter { !it.contains(' ') && it.length >= 2 }.forEach { k ->
                val l = m.getOrPut(k) { ArrayList(2) }; if (l.size < 2 && e.e !in l) l += e.e
            }
        }
        byWord = m
    }

    fun forWord(w: String): List<String> {
        val t = w.lowercase(); if (t.length < 2) return emptyList()
        byWord[t]?.let { return it }
        // Падежи и формы: «кота», «котик» → «кот»
        if (t.length >= 4) for (cut in 1..2) byWord[t.dropLast(cut)]?.let { return it }
        return emptyList()
    }

    fun search(q: String): List<EmojiItem> {
        val t = q.trim().lowercase(); if (t.isEmpty()) return emptyList()
        return all.filter { it.kw.contains(t) }.sortedBy { it.kw.split(" | ").none { k -> k.startsWith(t) } }.take(60)
    }

    // ---- недавние ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("emoji", Context.MODE_PRIVATE)
    fun recent(ctx: Context): List<String> = prefs(ctx).getString("recent", "")!!.split('\u0001').filter { it.isNotEmpty() }
    fun use(ctx: Context, e: String) {
        val list = (listOf(e) + recent(ctx).filter { it != e }).take(40)
        prefs(ctx).edit().putString("recent", list.joinToString("\u0001")).apply()
    }
    fun tone(ctx: Context, base: String) = prefs(ctx).getInt("tone|$base", prefs(ctx).getInt("tone", 0))
    fun setTone(ctx: Context, base: String, t: Int) = prefs(ctx).edit().putInt("tone|$base", t).putInt("tone", t).apply()
}

/** Панель эмодзи в стиле Gboard: категории, недавние, поиск, тон кожи. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EmojiPanel(ime: NanoIme, height: androidx.compose.ui.unit.Dp) {
    val ctx = ime
    val st = ime.state
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Emojis.load(ctx) }; st.emojiLoaded = true }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    var recent by remember { mutableStateOf(Emojis.recent(ctx)) }
    var tonePick by remember { mutableStateOf<EmojiItem?>(null) }
    val all = if (st.emojiLoaded) Emojis.all else emptyList()
    // Начало каждой категории в сетке (с учётом блока «недавние» и заголовков)
    val sections = remember(all.size, recent.size) {
        val s = mutableListOf<Pair<String, List<EmojiItem>>>()
        if (recent.isNotEmpty()) s += "Недавние" to recent.map { e -> EmojiItem(-1, e, false, "") }
        Emojis.catNames.forEachIndexed { i, n -> s += n to all.filter { it.cat == i } }
        s
    }
    val starts = remember(sections) {
        var idx = 0; sections.map { val st0 = idx; idx += 1 + it.second.size; st0 }
    }
    val current by remember { derivedStateOf {
        val f = grid.firstVisibleItemIndex; starts.indexOfLast { it <= f }.coerceAtLeast(0)
    } }

    Column(Modifier.fillMaxWidth().height(height)) {
        if (st.emojiSearch) {
            Surface(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(st.emojiQuery.ifEmpty { "Поиск эмодзи" }, color = if (st.emojiQuery.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (st.emojiQuery.length >= 3) IconButton({ ime.aiEmoji(st.emojiQuery) }, Modifier.size(32.dp)) {
                        if (st.aiEmojiBusy) NanoLoader(18.dp) else Icon(Icons.Rounded.AutoAwesome, "Найти с ИИ")
                    }
                    IconButton({ st.emojiSearch = false; st.emojiQuery = ""; st.aiEmoji = emptyList() }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Закрыть") }
                }
            }
            val res = remember(st.emojiQuery, st.aiEmoji) {
                val base = Emojis.search(st.emojiQuery)
                st.aiEmoji.map { e -> base.firstOrNull { it.e == e } ?: EmojiItem(0, e, false, "") } + base.filter { b -> b.e !in st.aiEmoji }
            }
            androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().height(52.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                items(res.size) { i ->
                    val it = res[i]
                    EmojiCell(Emojis.withTone(it.e, if (it.skin) Emojis.tone(ctx, it.e) else 0)) {
                        val out = Emojis.withTone(it.e, if (it.skin) Emojis.tone(ctx, it.e) else 0)
                        ime.commitEmoji(out); recent = Emojis.recent(ctx)
                    }
                }
            }
            // Ниже — обычные клавиши: они печатают в строку поиска
            Keys(ime, Modifier.weight(1f))
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.weight(1f).fillMaxWidth(), state = grid,
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            sections.forEach { (name, list) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(name, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(list) { it ->
                    val shown = if (it.skin) Emojis.withTone(it.e, Emojis.tone(ctx, it.e)) else it.e
                    EmojiCell(shown, onLong = if (it.skin) ({ tonePick = it }) else null) {
                        ime.commitEmoji(shown); recent = Emojis.recent(ctx)
                    }
                }
            }
        }
        // Нижняя панель категорий
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ st.emoji = false }) { Text("ABC", fontSize = 14.sp) }
            IconButton({ st.emojiSearch = true; st.emojiQuery = "" }) { Icon(Icons.Rounded.Search, "Поиск") }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                val icons = (if (recent.isNotEmpty()) listOf(Icons.Rounded.History) else emptyList()) + Emojis.catIcons
                icons.forEachIndexed { i, ic ->
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp))
                        .background(if (i == current) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClickable { scope.launch { grid.scrollToItem(starts.getOrElse(i) { 0 }) } },
                        contentAlignment = Alignment.Center) {
                        Icon(ic, null, Modifier.size(18.dp), tint = if (i == current) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            RepeatKey(Modifier.width(52.dp), onFire = { ime.backspace() }) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Стереть") }
        }
    }

    tonePick?.let { item ->
        Popup(alignment = Alignment.Center, onDismissRequest = { tonePick = null }) {
            Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(6.dp)) {
                    Emojis.tones.indices.forEach { t ->
                        EmojiCell(Emojis.withTone(item.e, t)) {
                            Emojis.setTone(ctx, item.e, t)
                            ime.commitEmoji(Emojis.withTone(item.e, t)); recent = Emojis.recent(ctx); tonePick = null
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(e: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onLongClick = onLong?.let { l -> { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); l() } }) {
                if (ime_haptic) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onClick()
            },
        contentAlignment = Alignment.Center
    ) { Text(e, fontSize = 26.sp) }
}

/** Вибрация на клавишах (выставляется клавиатурой из настроек). */
@Volatile var ime_haptic = true
