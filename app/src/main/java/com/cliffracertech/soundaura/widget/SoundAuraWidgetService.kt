package com.cliffracertech.soundaura.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SoundAuraWidgetService : LifecycleService() {

    @Inject
    lateinit var widgetUpdateHelper: SoundAuraWidgetUpdateHelper

    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // Eliminado el bucle de actualización cada segundo
        // El widget ahora se actualiza solo cuando hay cambios reales
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    companion object {
        fun requestWidgetUpdate(context: Context) {
            val intent = Intent(context, SoundAuraWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, SoundAuraWidget::class.java))
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}

class SoundAuraWidgetUpdateHelper @Inject constructor() {
    // Helper para futuras mejoras
}
