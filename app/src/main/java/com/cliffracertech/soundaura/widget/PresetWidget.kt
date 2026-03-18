package com.cliffracertech.soundaura.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.cliffracertech.soundaura.MainActivity
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.service.PlayerService
import com.cliffracertech.soundaura.service.PlayerService.Companion.PlaybackChangeListener

class PresetWidget : AppWidgetProvider() {

    private val playbackChangeListener = PlaybackChangeListener { newState ->
        val context = appContext ?: return@PlaybackChangeListener
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, PresetWidget::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        private var appContext: Context? = null
        
        const val ACTION_LOAD_PRESET = "com.cliffracertech.soundaura.widget.ACTION_LOAD_PRESET"
        const val EXTRA_PRESET_NAME = "extra_preset_name"

        fun sendAction(context: Context, action: String) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, PresetWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            val provider = PresetWidget()
            provider.updateAllWidgets(context, appWidgetManager, appWidgetIds)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appContext = context.applicationContext

        PlayerService.addPlaybackChangeListener(
            updateImmediately = true,
            listener = playbackChangeListener
        )

        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        PlayerService.removePlaybackChangeListener(playbackChangeListener)
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
        val views = android.widget.RemoteViews(context.packageName, R.layout.widget_preset)

        // Configurar intents para los botones (reutilizando actions de SoundAuraWidgetReceiver o similares)
        setButtonPendingIntent(context, views, R.id.widget_play_pause, SoundAuraWidget.ACTION_PLAY_PAUSE)
        setButtonPendingIntent(context, views, R.id.widget_stop, SoundAuraWidget.ACTION_STOP)

        // Intent para abrir la app
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = "soundaura://preset_widget".toUri()
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_title_container, openAppPendingIntent)

        // Configurar el RemoteViewsService para la lista
        val intent = Intent(context, PresetWidgetListService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = toUri(Intent.URI_INTENT_SCHEME).toUri()
        }
        views.setRemoteAdapter(R.id.widget_preset_list, intent)

        // Template cho click vào item
        val clickIntent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            action = ACTION_LOAD_PRESET
        }
        val clickPendingIntent = PendingIntent.getBroadcast(
            context, 1, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widget_preset_list, clickPendingIntent)

        // Cập nhật UI chung (Play/Pause/Status)
        PresetWidgetViews.updateWidgetUI(context, views, appWidgetManager, appWidgetId)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun setButtonPendingIntent(
        context: Context,
        views: android.widget.RemoteViews,
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
