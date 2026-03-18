package com.cliffracertech.soundaura.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.RemoteViews
import androidx.core.net.toUri
import com.cliffracertech.soundaura.MainActivity
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.service.PlayerService
import com.cliffracertech.soundaura.service.PlayerService.Companion.PlaybackChangeListener
import kotlinx.coroutines.*

class SoundAuraWidget : AppWidgetProvider() {

    private val playbackChangeListener = PlaybackChangeListener { newState ->
        val context = appContext ?: return@PlaybackChangeListener
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, SoundAuraWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        private var appContext: Context? = null
        private var serviceScope: CoroutineScope? = null

        const val ACTION_PLAY_PAUSE = "com.cliffracertech.soundaura.widget.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.cliffracertech.soundaura.widget.ACTION_STOP"
        const val ACTION_TOGGLE_PLAYLIST = "com.cliffracertech.soundaura.widget.ACTION_TOGGLE_PLAYLIST"
        const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"
        const val ACTION_UPDATE_WIDGET = "com.cliffracertech.soundaura.widget.ACTION_UPDATE_WIDGET"
        const val ACTION_TOGGLE_VOLUME_SLIDER = "com.cliffracertech.soundaura.widget.ACTION_TOGGLE_VOLUME_SLIDER"
        const val ACTION_SET_VOLUME_LEVEL = "com.cliffracertech.soundaura.widget.ACTION_SET_VOLUME_LEVEL"
        const val ACTION_HIDE_VOLUME_SLIDER = "com.cliffracertech.soundaura.widget.ACTION_HIDE_VOLUME_SLIDER"
        const val EXTRA_VOLUME_LEVEL = "extra_volume_level"

        fun sendAction(context: Context, action: String, playlistId: Long = -1, playlistName: String = "") {
            when (action) {
                ACTION_PLAY_PAUSE -> {
                    PlayerService.binder?.toggleIsPlaying() ?:
                    context.startService(PlayerService.playIntent(context))
                }
                ACTION_STOP -> {
                    context.startService(PlayerService.stopIntent(context))
                }
                ACTION_TOGGLE_PLAYLIST -> {
                    val intent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
                        setAction(ACTION_TOGGLE_PLAYLIST)
                        putExtra(EXTRA_PLAYLIST_ID, playlistId)
                        putExtra(EXTRA_PLAYLIST_NAME, playlistName)
                    }
                    context.sendBroadcast(intent)
                }
                ACTION_UPDATE_WIDGET -> {
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val componentName = ComponentName(context, SoundAuraWidget::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                    
                    val provider = SoundAuraWidget()
                    provider.updateAllWidgets(context, appWidgetManager, appWidgetIds)
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appContext = context.applicationContext

        // Iniciar servicio para mantener el widget actualizado
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        startWidgetUpdateService(context)

        // Registrar listener para cambios en el estado de reproducción
        PlayerService.addPlaybackChangeListener(
            updateImmediately = true,
            listener = playbackChangeListener
        )

        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        checkAndCleanupListeners(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        serviceScope?.cancel()
        serviceScope = null
        PlayerService.removePlaybackChangeListener(playbackChangeListener)
    }

    private fun checkAndCleanupListeners(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, SoundAuraWidget::class.java)
        val remainingWidgets = appWidgetManager.getAppWidgetIds(componentName)

        if (remainingWidgets.isEmpty()) {
            serviceScope?.cancel()
            serviceScope = null
            PlayerService.removePlaybackChangeListener(playbackChangeListener)
        }
    }

    private fun startWidgetUpdateService(context: Context) {
        val intent = Intent(context, SoundAuraWidgetService::class.java)
        context.startService(intent)
    }

    private fun updateAllWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_soundaura)

        // Configurar intents para los botones
        setButtonPendingIntent(context, views, R.id.widget_play_pause, ACTION_PLAY_PAUSE)
        setButtonPendingIntent(context, views, R.id.widget_stop, ACTION_STOP)

        // Intent para abrir la app al hacer clic en el título
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = "soundaura://widget".toUri()
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_title_container, openAppPendingIntent)

        // Configurar el RemoteViewsService para la lista - usando SoundAuraWidgetListService
        val intent = Intent(context, SoundAuraWidgetListService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = toUri(Intent.URI_INTENT_SCHEME).toUri()
        }
        views.setRemoteAdapter(R.id.widget_playlist_list, intent)

        // Establecer el PendingIntent para los clicks en los items de la lista
        val clickIntent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            action = ACTION_TOGGLE_PLAYLIST
        }
        val clickPendingIntent = PendingIntent.getBroadcast(
            context, 0, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widget_playlist_list, clickPendingIntent)

        // Actualizar UI según el estado actual
        SoundAuraWidgetViews.updateWidgetUI(context, views, appWidgetManager, appWidgetId)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun setButtonPendingIntent(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        action: String
    ) {
        val intent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            setAction(action)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, viewId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }
}
