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
        val isPaused = PlayerService.playbackState == PlaybackStateCompat.STATE_PAUSED

        // Actualizar icono de play/pause
        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause
                            else           R.drawable.ic_widget_play
        views.setImageViewResource(R.id.widget_play_pause, playPauseIcon)

        // Actualizar texto de estado
        // CONTAR PLAYLISTS ACTIVAS
        val application = context.applicationContext as com.cliffracertech.soundaura.SoundAuraApplication
        val activeCount = runBlocking { 
            application.database.playlistDao().getPlaylistsForWidget().count { it.isActive }
        }

        if (activeCount > 0) {
            views.setViewVisibility(R.id.widget_active_count, android.view.View.VISIBLE)
            views.setTextViewText(R.id.widget_active_count, "($activeCount)")
        } else {
            views.setViewVisibility(R.id.widget_active_count, android.view.View.GONE)
        }

        val statusText = if (isPlaying) context.getString(R.string.playing)
                         else if (isPaused) context.getString(R.string.paused)
                         else context.getString(R.string.stopped)
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

        // CICLO DE VOLUMEN
        val cycleVolumeIntent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            action = SoundAuraWidget.ACTION_CYCLE_VOLUME
        }
        val cycleVolumePendingIntent = PendingIntent.getBroadcast(
            context, 301, cycleVolumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_master_volume_container, cycleVolumePendingIntent)
        

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
