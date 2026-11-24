package com.nanopero.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Плавающая кнопка ✦ поверх всех приложений: касание — обработать скопированный текст. */
class BubbleService : Service() {
    private var view: ImageView? = null
    private lateinit var wm: WindowManager

    companion object {
        var running by mutableStateOf(false); private set
        private const val CH = "bubble"
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, BubbleService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BubbleService::class.java)) }
        fun canDraw(ctx: Context) = SysSettings.canDrawOverlays(ctx)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (view == null && canDraw(this)) addBubble()
        running = true
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Плавающая кнопка", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Плавающая кнопка НаноПеро")
            .setContentText("Скопируй текст и нажми ✦")
            .addAction(Notification.Action.Builder(null, "Скрыть", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, n)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        wm = getSystemService(WindowManager::class.java)
        val size = (52 * resources.displayMetrics.density).toInt()
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = (resources.displayMetrics.heightPixels * 0.4).toInt() }

        val dyn = Build.VERSION.SDK_INT >= 31
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (dyn) getColor(android.R.color.system_accent1_200) else 0xFFD0BCFF.toInt())
        }
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.ic_tile)
            setColorFilter(if (dyn) getColor(android.R.color.system_accent1_900) else 0xFF21005D.toInt())
            background = bg
            elevation = 8 * resources.displayMetrics.density
            val pad = (14 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
            alpha = 0.92f
            contentDescription = "НаноПеро"
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        iv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) + abs(dy) > 12) moved = true
                    p.x = sx + dx.toInt(); p.y = sy + dy.toInt(); wm.updateViewLayout(v, p); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        startActivity(Intent(this, ProcessTextActivity::class.java)
                            .putExtra(ProcessTextActivity.EXTRA_CLIPBOARD, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        // Прилипание к ближайшему краю
                        val w = resources.displayMetrics.widthPixels
                        p.x = if (p.x + size / 2 < w / 2) 0 else w - size
                        wm.updateViewLayout(v, p)
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(iv, p)
        view = iv
    }

    override fun onDestroy() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        running = false
        super.onDestroy()
    }
}

@Composable
fun BubbleTab() {
    val ctx = LocalContext.current
    var can by remember { mutableStateOf(BubbleService.canDraw(ctx)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) can = BubbleService.canDraw(ctx) }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Маленькая кнопка ✦ поверх всех приложений. Скопируй любой текст и коснись её — откроется панель НаноПеро. " +
                "Кнопку можно перетаскивать, она прилипает к краю.", style = MaterialTheme.typography.bodyMedium)
        StepCard(1, "Разрешить показ поверх окон", if (can) "Готово ✓" else "Нужно для плавающей кнопки", can) {
            ctx.startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Box(Modifier.padding(16.dp)) {
                SwitchRow("Плавающая кнопка", if (BubbleService.running) "Показана" else "Скрыта", BubbleService.running) { on ->
                    if (on && can) BubbleService.start(ctx) else BubbleService.stop(ctx)
                }
            }
        }
        Text("Пока кнопка видна, в шторке висит тихое уведомление — так Android требует для работы поверх окон. " +
                "Скрыть можно прямо из него.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
