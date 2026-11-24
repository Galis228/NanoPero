package com.nanopero.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Общая папка моделей для НаноПеро и «Персоны»: Загрузки/AI-Models.
 * Модель скачивается один раз, и её видят оба приложения. Чтобы читать файлы, скачанные другим
 * приложением, Android требует разрешение «Доступ ко всем файлам» — у каждого из двух приложений своё.
 */
object SharedModels {
    const val FOLDER = "AI-Models"
    val dir: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
    val path: String get() = "Загрузки/$FOLDER"

    fun hasAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** Общая папка используется, как только выдан доступ ко всем файлам. */
    @Suppress("UNUSED_PARAMETER")
    fun active(ctx: Context): Boolean = hasAccess()

    /** Файл лежит в общей папке — значит, его видит и «Персона». */
    fun isShared(f: File): Boolean = runCatching { f.canonicalFile.parentFile == dir.canonicalFile }.getOrDefault(false)

    /** Папка для чтения: видна всегда, когда есть доступ (даже если НаноПеро сама туда не качает). */
    fun readable(): File? = if (hasAccess()) dir.takeIf { it.isDirectory } else null

    fun ensure(): File? = runCatching {
        dir.mkdirs()
        val readme = File(dir, "README.txt")
        if (!readme.exists()) readme.writeText(
            "Общая папка моделей ИИ для НаноПеро и Персоны.\n" +
                    "Сюда кладутся файлы .litertlm и .task — их видят оба приложения.\n" +
                    "Недокачанные файлы имеют окончание .part — не трогай их.\n"
        )
        dir
    }.getOrNull()

    fun requestAccess(ctx: Context) {
        val i = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun isModel(f: File) = f.isFile && (f.name.endsWith(".litertlm", true) || f.name.endsWith(".task", true) || f.name.endsWith(".gguf", true))

    /** Перенести уже скачанные в приложение модели в общую папку. Возвращает число перенесённых файлов. */
    suspend fun moveIn(ctx: Context, onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val target = ensure() ?: throw EngineException("Не удалось создать папку $path")
        val local = ctx.app.ai.local
        local.releaseAsync()
        var n = 0
        val sources = listOfNotNull(local.modelsDir, ctx.getExternalFilesDir("models"))
        for (d in sources) for (f in d.listFiles()?.filter { isModel(it) } ?: emptyList()) {
            val dst = File(target, f.name)
            if (dst.exists() && dst.length() == f.length()) { f.delete(); n++; continue }
            onProgress("Переношу ${f.name}…")
            if (target.usableSpace < f.length() + 200L * 1024 * 1024 && !f.renameTo(dst))
                throw EngineException("Не хватает места для ${f.name}")
            if (!dst.exists() && !f.renameTo(dst)) {
                // Разные разделы памяти — копируем и удаляем исходник
                val tmp = File(target, f.name + ".part")
                f.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                if (tmp.length() != f.length()) { tmp.delete(); throw EngineException("Ошибка копирования ${f.name}") }
                tmp.renameTo(dst); f.delete()
            }
            n++
        }
        n
    }
}

/** Карточка настройки общей папки на экране «Модель». */
@Composable
fun SharedModelsCard(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(SharedModels.hasAccess()) }
    var busy by remember { mutableStateOf<String?>(null) }
    // Вернулся из системных настроек — перепроверяем разрешение
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val now = SharedModels.hasAccess()
            if (now != access) { access = now; if (now) SharedModels.ensure(); onChanged() }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FolderShared, null); Spacer(Modifier.width(8.dp))
                Text("Общая папка с «Персоной»", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (access) Text("включена", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text("Модели хранятся в папке «${SharedModels.path}». Скачал один раз — пользуются и НаноПеро, и «Персона», " +
                    "место на телефоне не тратится дважды.", style = MaterialTheme.typography.bodySmall)
            if (!access) {
                Text("Нужно разрешение «Доступ ко всем файлам» — иначе Android не даёт одному приложению читать файлы, " +
                        "скачанные другим. НаноПеро работает только с этой папкой.", style = MaterialTheme.typography.bodySmall)
                FilledTonalButton({ SharedModels.requestAccess(ctx) }) { Text("Открыть доступ к общей папке") }
            }
            if (access) {
                val inside = remember(busy) { SharedModels.readable()?.listFiles()?.count { SharedModels.isModel(it) } ?: 0 }
                Text("В общей папке моделей: $inside", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                val localCount = remember(busy) {
                    listOfNotNull(ctx.app.ai.local.modelsDir, ctx.getExternalFilesDir("models"))
                        .sumOf { d -> d.listFiles()?.count { SharedModels.isModel(it) } ?: 0 }
                }
                if (localCount > 0) OutlinedButton({
                    scope.launch {
                        busy = "Переношу…"
                        val r = runCatching { SharedModels.moveIn(ctx) { busy = it } }
                        busy = null
                        SnackHost.show(ctx, r.fold({ "Перенесено в общую папку: $it" }, { it.message ?: "Ошибка" }))
                        onChanged()
                    }
                }, enabled = busy == null) { Text("Перенести мои модели в общую папку ($localCount)") }
                busy?.let { Row(verticalAlignment = Alignment.CenterVertically) { NanoLoader(20.dp); Spacer(Modifier.width(8.dp)); Text(it) } }
                Text("В «Персоне» включи такую же общую папку — и она увидит эти модели.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


/** Подтверждение удаления: для общей папки предупреждаем, что модель пропадёт и в «Персоне». */
@Composable
fun DeleteModelDialog(name: String, file: File?, sizeText: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val shared = file != null && SharedModels.isShared(file)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить $name?") },
        text = {
            Text(buildString {
                if (sizeText != null) append("Освободится $sizeText. ")
                if (shared) append("Модель лежит в общей папке — она пропадёт и в «Персоне». ")
                append("Скачать снова можно в любой момент.")
            })
        },
        confirmButton = { TextButton(onConfirm) { Text(if (shared) "Удалить из обоих" else "Удалить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } }
    )
}
