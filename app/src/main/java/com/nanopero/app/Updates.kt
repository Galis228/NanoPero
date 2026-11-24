package com.nanopero.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Проверка обновлений через GitHub (github.com/Galis228/NanoPero).
 * Источники по порядку: последний релиз (Releases) → файл latest.json в ветке «releases».
 */
object Updates {
    private const val REPO = "Galis228/NanoPero"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RAW_LATEST = "https://raw.githubusercontent.com/$REPO/releases/latest.json"
    const val PAGE = "https://github.com/$REPO"

    data class Release(val versionName: String, val versionCode: Int, val notes: String, val apkUrl: String, val size: Long)

    /** Состояние для интерфейса. */
    var available by mutableStateOf<Release?>(null)
    var status by mutableStateOf("")
    var checking by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "NanoPero/${BuildConfig.VERSION_NAME}")
        return try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** «v1.8», «1.8.1», «НаноПеро 1.8» → [1, 8, 1]. */
    private fun parts(v: String) = Regex("\\d+").findAll(v).map { it.value.toInt() }.toList()
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun fromApi(): Release? = runCatching {
        val o = JSONObject(get(API_LATEST) ?: return null)
        val assets = o.optJSONArray("assets") ?: return null
        var url = ""; var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", true)) { url = a.optString("browser_download_url"); size = a.optLong("size") }
        }
        if (url.isEmpty()) return null
        val tag = o.optString("tag_name").ifBlank { o.optString("name") }
        Release(tag.trimStart('v', 'V'), 0, o.optString("body"), url, size)
    }.getOrNull()

    private fun fromRaw(): Release? = runCatching {
        val o = JSONObject(get(RAW_LATEST) ?: return null)
        Release(o.getString("versionName"), o.optInt("versionCode"), o.optString("notes"), o.getString("apk"), o.optLong("size"))
    }.getOrNull()

    /** Проверить; вернуть релиз, если он новее установленной версии. */
    suspend fun check(ctx: Context, manual: Boolean): Release? = withContext(Dispatchers.IO) {
        checking = true
        try {
            val r = fromApi()?.takeIf { newer(it.versionName, BuildConfig.VERSION_NAME) } ?: fromRaw()
            ctx.app.settings.updLastCheck = System.currentTimeMillis()
            val isNew = r != null && (if (r.versionCode > 0) r.versionCode > BuildConfig.VERSION_CODE else newer(r.versionName, BuildConfig.VERSION_NAME))
            if (r == null) { status = if (manual) "Не удалось связаться с GitHub. Проверь интернет." else ""; null }
            else if (isNew) { available = r; status = "Доступна версия ${r.versionName}"; r }
            else { available = null; status = "У тебя последняя версия (${BuildConfig.VERSION_NAME})"; null }
        } finally { checking = false }
    }

    /** Автопроверка не чаще раза в сутки. */
    suspend fun autoCheck(ctx: Context) {
        val s = ctx.app.settings
        if (!s.updAuto || System.currentTimeMillis() - s.updLastCheck < 20 * 3600_000L) return
        check(ctx, manual = false)
    }

    private fun certs(pm: PackageManager, info: android.content.pm.PackageInfo?): Set<String> {
        val sig = info?.signingInfo ?: return emptySet()
        val arr = if (sig.hasMultipleSigners()) sig.apkContentsSigners else sig.signingCertificateHistory
        return arr.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** Скачать APK, проверить подпись и открыть установщик. */
    suspend fun downloadAndInstall(ctx: Context, r: Release): String? = withContext(Dispatchers.IO) {
        val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "NanoPero-${r.versionName}.apk")
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle("НаноПеро ${r.versionName}").setDescription("Обновление")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file)))
        progress = 0f
        try {
            while (true) {
                delay(400)
                val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return@withContext "Загрузка прервалась"
                c.use {
                    if (!it.moveToFirst()) return@withContext "Загрузка отменена"
                    val st = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { t -> t > 0 } ?: r.size
                    if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    if (st == DownloadManager.STATUS_FAILED) return@withContext "Не удалось скачать обновление"
                    if (st == DownloadManager.STATUS_SUCCESSFUL) break
                }
            }
        } finally { progress = null }
        // Безопасность: ставим только APK этого же приложения, подписанный тем же ключом
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val arch = pm.getPackageArchiveInfo(file.path, flags) ?: return@withContext "Файл обновления повреждён"
        if (arch.packageName != ctx.packageName) return@withContext "Это не обновление НаноПеро — установка отменена"
        val mine = certs(pm, pm.getPackageInfo(ctx.packageName, flags))
        val theirs = certs(pm, arch)
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty())
            return@withContext "Подпись обновления не совпадает с установленным приложением — установка отменена"
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@withContext "Разреши НаноПеро устанавливать приложения и нажми «Обновить» ещё раз"
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }
    }
}

/** Плашка «Доступно обновление» + диалог с описанием и установкой. */
@Composable
fun UpdateBanner() {
    val r = Updates.available ?: return
    var open by remember { mutableStateOf(false) }
    Surface(onClick = { open = true }, color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Доступна версия ${r.versionName}", style = MaterialTheme.typography.titleSmall)
                Text("Нажми, чтобы посмотреть, что нового", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (open) UpdateDialog(r) { open = false }
}

@Composable
fun UpdateDialog(r: Updates.Release, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (Updates.progress == null) onDismiss() },
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text("НаноПеро ${r.versionName}") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сейчас установлена ${BuildConfig.VERSION_NAME}" + (if (r.size > 0) " · размер ${r.size / 1_048_576} МБ" else ""),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (r.notes.isNotBlank()) MdText(r.notes)
                Updates.progress?.let { p ->
                    LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Скачиваю… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Данные и настройки сохранятся.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button({ scope.launch { err = null; err = Updates.downloadAndInstall(ctx, r) } }, enabled = Updates.progress == null) { Text("Обновить") }
        },
        dismissButton = { TextButton(onDismiss, enabled = Updates.progress == null) { Text("Позже") } })
}

/** Раздел настроек «Обновления». */
@Composable
fun UpdatesSettings() {
    val ctx = LocalContext.current
    val s = ctx.app.settings
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(false) }
    SetSection("Обновления") {
        SetSwitch(Icons.Rounded.SystemUpdate, "Проверять обновления", "Раз в день смотреть на GitHub, не вышла ли новая версия", s.updAuto) { s.updAuto = it }
        SetCustom("Проверить сейчас", "версия") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ scope.launch { if (Updates.check(ctx, manual = true) != null) dialog = true } }, enabled = !Updates.checking) {
                        Text(if (Updates.checking) "Проверяю…" else "Проверить сейчас")
                    }
                    if (Updates.available != null) Button({ dialog = true }) { Text("Обновить") }
                }
                AnimatedVisibility(Updates.status.isNotBlank()) {
                    Text(Updates.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("Проект на GitHub")
                }
            }
        }
    }
    if (dialog) Updates.available?.let { UpdateDialog(it) { dialog = false } }
}
