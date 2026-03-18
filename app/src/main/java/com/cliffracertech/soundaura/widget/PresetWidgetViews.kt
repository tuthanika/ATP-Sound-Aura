package com.cliffracertech.soundaura.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.RemoteViews
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.preferenceFlow
import com.cliffracertech.soundaura.service.PlayerService
import com.cliffracertech.soundaura.settings.PrefKeys
import com.cliffracertech.soundaura.settings.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

        // Leer volumen y visibilidad del slider
        val masterVolumeKey = floatPreferencesKey(PrefKeys.masterVolume)
        val isSliderVisibleKey = booleanPreferencesKey(PrefKeys.isVolumeSliderVisible)
        val masterVolume = runBlocking { context.dataStore.data.first()[masterVolumeKey] ?: 1f }
        val isSliderVisible = runBlocking { context.dataStore.data.first()[isSliderVisibleKey] ?: false }

        val percentage = (masterVolume * 100).toInt()
        views.setTextViewText(R.id.widget_master_volume_text, "$percentage%")

        // Toggles del slider
        val toggleSliderIntent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            action = SoundAuraWidget.ACTION_TOGGLE_VOLUME_SLIDER
        }
        val toggleSliderPendingIntent = PendingIntent.getBroadcast(
            context, 100, toggleSliderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_master_volume_container, toggleSliderPendingIntent)

        // Visibilidad del slider (Ghost Overlay sync)
        views.setViewVisibility(R.id.widget_ghost_stop_space,
            if (showStopButton) android.view.View.VISIBLE else android.view.View.GONE)
        
        views.setViewVisibility(R.id.widget_volume_slider_container,
            if (isSliderVisible) android.view.View.VISIBLE else android.view.View.GONE)

        // Configurar 10 segmentos de volumen (10% a 100%)
        for (i in 1..10) {
            val level = i * 0.1f
            val percentage = (level * 100).toInt()
            val segmentId = context.resources.getIdentifier("widget_volume_$percentage", "id", context.packageName)
            
            if (segmentId != 0) {
                setupVolumeLevelButton(context, views, segmentId, level)
                // Los niveles superiores al volumen actual se oscurecen
                val isDimmed = masterVolume < (level - 0.05f)
                views.setInt(segmentId, "setBackgroundResource",
                    if (isDimmed) R.drawable.widget_volume_segment_dim else 0)
            }
        }

        // Actualizar la lista de presets
        val componentName = ComponentName(context, PresetWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_preset_list)
    }

    private fun setupVolumeLevelButton(context: Context, views: RemoteViews, viewId: Int, volume: Float) {
        val intent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            action = SoundAuraWidget.ACTION_SET_VOLUME_LEVEL
            putExtra(SoundAuraWidget.EXTRA_VOLUME_LEVEL, volume)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, viewId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(viewId, pendingIntent)
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
