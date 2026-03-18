package com.cliffracertech.soundaura.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.IBinder
import android.widget.RemoteViewsService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Servicio dedicado exclusivamente para proporcionar el RemoteViewsFactory
 * que alimenta la lista de playlists en el widget.
 */
@AndroidEntryPoint
class SoundAuraWidgetListService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return RemoteViewsFactory(this.applicationContext, appWidgetId)
    }
}
