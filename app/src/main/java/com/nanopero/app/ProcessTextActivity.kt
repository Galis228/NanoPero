package com.nanopero.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Всплывающая панель поверх любого приложения:
 * меню выделения текста (PROCESS_TEXT), «Поделиться» (SEND) и плитка буфера обмена.
 */
class ProcessTextActivity : ComponentActivity() {
    private val input = mutableStateOf("")
    private val fromClipboard get() = intent?.getBooleanExtra(EXTRA_CLIPBOARD, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val i = intent
        input.value = when (i?.action) {
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> i?.getStringExtra(EXTRA_INPUT)
        }.orEmpty()
        val readOnly = i?.action != Intent.ACTION_PROCESS_TEXT ||
                i.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        setContent {
            NanoTheme {
                Sheet(
                    text = input.value,
                    readOnly = readOnly,
                    autoMode = i?.getStringExtra(EXTRA_AUTO_MODE)?.let { m -> runCatching { Mode.valueOf(m) }.getOrNull() },
                    canSave = i?.action == Intent.ACTION_SEND,
                    onSave = {
                        ReadLater.add(this, input.value, i?.getStringExtra(Intent.EXTRA_SUBJECT))
                        android.widget.Toast.makeText(this, "Отложено — смотри «Сводка → Прочитать потом»",
                            android.widget.Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onDismiss = { finish() },
                    onReplace = { result ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
                        finish()
                    },
                    onOpenApp = { t ->
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_TEXT, t)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        finish()
                    }
                )
            }
        }
    }

    // Буфер обмена можно читать только когда окно в фокусе (Android 10+)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fromClipboard && input.value.isBlank()) {
            val cm = getSystemService(ClipboardManager::class.java)
            input.value = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        }
    }

    companion object {
        const val EXTRA_CLIPBOARD = "nanopero.clipboard"
        const val EXTRA_INPUT = "nanopero.input"
        const val EXTRA_AUTO_MODE = "nanopero.auto_mode"
    }
}

@Composable
private fun Sheet(
    text: String,
    readOnly: Boolean,
    autoMode: Mode?,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onReplace: (String) -> Unit,
    onOpenApp: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val r = rememberRunner()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, label = "in")
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(autoMode, text) { if (autoMode != null && text.isNotBlank() && r.mode == null) r.run(autoMode, text) }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f * a))
            .clickable(remember { MutableInteractionSource() }, null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        SnackbarHost(SnackHost.state, Modifier.align(Alignment.TopCenter).statusBarsPadding())
        Surface(
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .graphicsLayer { translationY = (1f - a) * 400f; alpha = a }
                .clickable(remember { MutableInteractionSource() }, null) { /* не закрывать */ }
        ) {
            Column(Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("НаноПеро", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    IconButton({ onOpenApp(if (r.result.isNotBlank()) r.result else text) }) {
                        Icon(Icons.Rounded.OpenInFull, "Открыть в приложении")
                    }
                    IconButton(onDismiss) { Icon(Icons.Rounded.Close, "Закрыть") }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clickable { expanded = !expanded }
                ) {
                    Text(
                        text.ifBlank { "Буфер обмена пуст" },
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(12.dp))
                ModeChips(r, !r.busy, onMode = { r.run(it, text) }, onPreset = { r.run(Mode.CUSTOM, text, it) })
                if (canSave) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onSave, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(Icons.Rounded.BookmarkAdd, null); Spacer(Modifier.width(6.dp)); Text("Отложить в «Прочитать потом»")
                    }
                }
                Spacer(Modifier.height(12.dp))
                ResultCard(
                    r,
                    onReplace = if (readOnly) null else onReplace,
                    onCopy = { ctx.copyText(it) },
                    onShare = { ctx.shareText(it) }
                )
                if (r.mode == null) {
                    Text("Выбери, что сделать с текстом. Всё обрабатывается на телефоне.",
                        Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
