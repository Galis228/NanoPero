package com.nanopero.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

private val Seed = Color(0xFF6750A4)

private val NanoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private fun nanoTypography(): Typography {
    val b = Typography()
    return b.copy(
        displaySmall = b.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = b.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = b.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = b.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = b.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = b.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = b.bodyLarge.copy(lineHeight = 24.sp),
    )
}

/** Material 3 Expressive: динамические цвета Material You, выразительная анимация, крупные скругления. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NanoTheme(snackbars: Boolean = true, kbTheme: Int = -1, content: @Composable () -> Unit) {
    val dark = when (kbTheme) { 1 -> false; 2, 3 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val base = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFD0BCFF), secondary = Color(0xFFCCC2DC), tertiary = Color(0xFFEFB8C8))
        else -> lightColorScheme(primary = Seed, secondary = Color(0xFF625B71), tertiary = Color(0xFF7D5260))
    }
    // AMOLED: чистый чёрный фон, клавиши чуть светлее
    val scheme = if (kbTheme == 3) base.copy(surface = Color.Black, surfaceContainer = Color.Black, surfaceContainerLow = Color.Black,
        surfaceContainerHigh = Color(0xFF161616), surfaceContainerHighest = Color(0xFF222222), background = Color.Black) else base
    MaterialExpressiveTheme(colorScheme = scheme, shapes = NanoShapes, typography = nanoTypography()) {
        if (snackbars) SnackHost.Provider(content) else content()
    }
}

// ---------- Снекбары вместо тостов ----------

object SnackHost {
    private data class Ev(val msg: String, val action: String?, val onAction: (() -> Unit)?)
    private val events = MutableSharedFlow<Ev>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var collectors by mutableIntStateOf(0)
    val state = SnackbarHostState()

    /** Показать сообщение: снекбаром, если экран приложения открыт, иначе тостом. */
    fun show(ctx: android.content.Context, msg: String) {
        if (collectors > 0) events.tryEmit(Ev(msg, null, null))
        else Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    /** Снекбар с кнопкой «Отменить». */
    fun undo(msg: String, onUndo: () -> Unit) { events.tryEmit(Ev(msg, "Отменить", onUndo)) }
    /** Снекбар с произвольной кнопкой. */
    fun action(msg: String, label: String, onAction: () -> Unit) { events.tryEmit(Ev(msg, label, onAction)) }

    @Composable
    fun Provider(content: @Composable () -> Unit) {
        DisposableEffect(Unit) { collectors++; onDispose { collectors-- } }
        LaunchedEffect(Unit) {
            events.collect { e ->
                state.currentSnackbarData?.dismiss()
                kotlinx.coroutines.coroutineScope {
                    launch {
                        val r = state.showSnackbar(e.msg, e.action, duration = if (e.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) e.onAction?.invoke()
                    }
                }
            }
        }
        content()
    }
}

// ---------- Тактильный отклик ----------

fun View.tick() = performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
fun View.confirm() = performHapticFeedback(
    if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
)

@Composable
fun rememberHaptics(): View = LocalView.current

// ---------- Индикаторы загрузки Material 3 Expressive (морфинг форм) ----------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NanoLoader(size: Dp = 24.dp, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    LoadingIndicator(modifier.size(size), color = color)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NanoLoaderContained(size: Dp = 48.dp) {
    ContainedLoadingIndicator(Modifier.size(size))
}

val MonoLabel = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp)
