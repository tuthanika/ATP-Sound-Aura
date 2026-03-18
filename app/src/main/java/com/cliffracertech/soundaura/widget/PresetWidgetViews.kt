package com.cliffracertech.soundaura.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.RemoteViews
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.service.PlayerService
import java.time.Duration
import java.time.Instant

object PresetWidgetViews {

    fun updateWidgetUI(
        context: Context,
        views: RemoteViews,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val isPlaying = PlayerService.playbackState == PlaybackStateCompat.STATE_PLAYING

        // Actualizar icono de play/pause
        val playPauseIcon = if (isPlaying) R.drawable.ic_baseline_pause_24
                            else           R.drawable.ic_baseline_play_24
        views.setImageViewResource(R.id.widget_play_pause, playPauseIcon)

        // Actualizar texto de estado
        val statusText = when (PlayerService.playbackState) {
            PlaybackStateCompat.STATE_PLAYING -> context.getString(R.string.playing)
            PlaybackStateCompat.STATE_PAUSED -> context.getString(R.string.paused)
            else -> context.getString(R.string.stopped)
        }
        views.setTextViewText(R.id.widget_status, statusText)

        // Mostrar tiempo restante del timer si existe
        val stopTime = PlayerService.binder?.stopTime
        if (stopTime != null && isPlaying) {
            val remaining = Duration.between(Instant.now(), stopTime)
            if (!remaining.isNegative && !remaining.isZero) {
                val timeStr = formatDuration(remaining)
                views.setTextViewText(R.id.widget_timer, "⏱️ $timeStr")
                views.setViewVisibility(R.id.widget_timer, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_timer, android.view.View.GONE)
            }
        } else {
            views.setViewVisibility(R.id.widget_timer, android.view.View.GONE)
        }

        // Visibilidad del botón de stop
        val showStopButton = PlayerService.playbackState != PlaybackStateCompat.STATE_STOPPED
        views.setViewVisibility(R.id.widget_stop,
            if (showStopButton) android.view.View.VISIBLE else android.view.View.GONE)

        // Actualizar la lista de presets
        val componentName = ComponentName(context, PresetWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_preset_list)
    }

    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = (duration.toMinutes() % 60)
        val seconds = (duration.toSeconds() % 60)

        return when {
            hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
            minutes > 0 -> "%d:%02d".format(minutes, seconds)
            else -> "%ds".format(seconds)
        }
    }
}
