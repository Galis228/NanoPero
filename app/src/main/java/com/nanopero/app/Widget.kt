package com.nanopero.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Виджет: три кнопки — обработать буфер, голосовая заметка, сводка дня. */
class NanoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val clip = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, ProcessTextActivity::class.java).putExtra(ProcessTextActivity.EXTRA_CLIPBOARD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val voice = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val digest = PendingIntent.getActivity(ctx, 3,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DIGEST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        ids.forEach { id ->
            val v = RemoteViews(ctx.packageName, R.layout.widget).apply {
                setOnClickPendingIntent(R.id.w_clip, clip)
                setOnClickPendingIntent(R.id.w_voice, voice)
                setOnClickPendingIntent(R.id.w_digest, digest)
            }
            mgr.updateAppWidget(id, v)
        }
    }
}
