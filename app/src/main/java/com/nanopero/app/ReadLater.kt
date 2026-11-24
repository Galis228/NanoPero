package com.nanopero.app

import android.content.Context
import android.text.Html
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** «Прочитать потом»: сохраняет текст или страницу по ссылке, выжимку делает локальная модель. */
object ReadLater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val urlRx = Regex("https?://[^\\s<>\"]+")

    fun firstUrl(text: String) = urlRx.find(text)?.value?.trimEnd('.', ',', ')', '»')

    /** Добавить из «Поделиться». Если в тексте ссылка — скачиваем страницу (это единственный сетевой запрос). */
    fun add(ctx: Context, shared: String, subject: String?) {
        val store = ctx.app.stores.reads
        val url = firstUrl(shared)
        val textWithoutUrl = if (url != null) shared.replace(url, "").trim() else shared.trim()
        if (url != null && textWithoutUrl.length < 200) {
            val item = ReadItem(newId(), System.currentTimeMillis(), subject?.takeIf { it.isNotBlank() } ?: url, url, "")
            store.add(item)
            refetch(ctx, item.id, url)
        } else {
            val title = subject?.takeIf { it.isNotBlank() }
                ?: textWithoutUrl.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Текст"
            store.add(ReadItem(newId(), System.currentTimeMillis(), title, url.orEmpty(), textWithoutUrl))
        }
    }

    fun refetch(ctx: Context, id: Long, url: String) {
        val store = ctx.app.stores.reads
        scope.launch {
            store.update({ it.id == id }) { it.copy(error = "Загружаю страницу…") }
            val res = runCatching { withContext(Dispatchers.IO) { fetch(url) } }
            store.update({ it.id == id }) { old ->
                res.fold(
                    { (t, body) ->
                        old.copy(title = if (old.title == url && t.isNotBlank()) t else old.title, text = body,
                            error = if (body.isBlank()) "Не удалось извлечь текст страницы" else "")
                    },
                    { old.copy(error = "Нет сети или сайт не отдал страницу: ${it.message}") }
                )
            }
        }
    }

    private fun fetch(url: String): Pair<String, String> {
        var u = URL(url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "ru,uk;q=0.9,en;q=0.8")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                u = URL(u, loc); conn.disconnect(); continue
            }
            break
        }
        val charset = conn.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotBlank() } ?: "UTF-8"
        val bytes = conn.inputStream.use { inp ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < 3_000_000) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            out.toByteArray()
        }
        conn.disconnect()
        val html = runCatching { String(bytes, charset(charset)) }.getOrElse { String(bytes) }
        return extract(html)
    }

    /** Грубое, но рабочее извлечение текста статьи из HTML. */
    fun extract(html: String): Pair<String, String> {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { decode(it).trim() }.orEmpty()
        var body = html
        listOf("script", "style", "noscript", "svg", "nav", "header", "footer", "aside", "form").forEach { tag ->
            body = body.replace(Regex("<$tag\\b.*?</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        }
        val article = Regex("<article\\b.*?</article>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.value ?: body
        val paras = Regex("<(p|h[1-3]|li)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(article).map { decode(it.groupValues[2]).replace(Regex("\\s+"), " ").trim() }
            .filter { it.length > 30 }.toList()
        val text = if (paras.sumOf { it.length } > 300) paras.joinToString("\n\n")
        else decode(article).replace(Regex("\\s+"), " ").trim()
        return title to text.take(60_000)
    }

    private fun decode(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
}
