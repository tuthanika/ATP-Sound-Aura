package com.cliffracertech.soundaura.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import com.cliffracertech.soundaura.SoundAuraApplication
import com.cliffracertech.soundaura.edit
import com.cliffracertech.soundaura.preferenceFlow
import com.cliffracertech.soundaura.service.PlayerService
import com.cliffracertech.soundaura.settings.PrefKeys
import com.cliffracertech.soundaura.settings.dataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SoundAuraWidgetReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        when (action) {
            SoundAuraWidget.ACTION_PLAY_PAUSE -> {
                SoundAuraWidget.sendAction(context, action)
            }
            SoundAuraWidget.ACTION_STOP -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val application = context.applicationContext as SoundAuraApplication
                        application.database.playlistDao().deactivateAll()
                        
                        // Parar el servicio y actualizar la UI
                        SoundAuraWidget.sendAction(context, action)
                        withContext(Dispatchers.Main) {
                            SoundAuraWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                            PresetWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            SoundAuraWidget.ACTION_TOGGLE_PLAYLIST -> {
                val playlistId = intent.getLongExtra(SoundAuraWidget.EXTRA_PLAYLIST_ID, -1)
                if (playlistId != -1L) {
                    val pendingResult = goAsync()
                    scope.launch {
                        try {
                            val application = context.applicationContext as SoundAuraApplication
                            val playlistDao = application.database.playlistDao()
                            playlistDao.toggleIsActive(playlistId)
                            withContext(Dispatchers.Main) {
                                SoundAuraWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                                PresetWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            SoundAuraWidget.ACTION_UPDATE_WIDGET -> {
                SoundAuraWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                PresetWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
            }
            PresetWidget.ACTION_LOAD_PRESET -> {
                val presetName = intent.getStringExtra(PresetWidget.EXTRA_PRESET_NAME)
                if (presetName != null) {
                    val pendingResult = goAsync()
                    scope.launch {
                        try {
                            val application = context.applicationContext as SoundAuraApplication
                            val presetDao = application.database.presetDao()
                            presetDao.loadPreset(presetName)
                            context.startService(PlayerService.playIntent(context))
                            withContext(Dispatchers.Main) {
                                SoundAuraWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                                PresetWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            SoundAuraWidget.ACTION_CYCLE_VOLUME -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val volKey = floatPreferencesKey(PrefKeys.masterVolume)
                        val current = context.dataStore.data.first()[volKey] ?: 1f
                        
                        // Cycle: 0 -> 0.25 -> 0.5 -> 0.75 -> 1.0 -> 0
                        val next = when {
                            current < 0.125f -> 0.25f
                            current < 0.375f -> 0.50f
                            current < 0.625f -> 0.75f
                            current < 0.875f -> 1.00f
                            else -> 0f
                        }
                        
                        context.dataStore.edit(volKey, next)
                        
                        // Ensure slider is always hidden (just in case)
                        val visKey = booleanPreferencesKey(PrefKeys.isVolumeSliderVisible)
                        context.dataStore.edit(visKey, false)
                        cancelAutoHide(context)
                        
                        withContext(Dispatchers.Main) {
                            SoundAuraWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                            PresetWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            SoundAuraWidget.ACTION_SET_VOLUME_LEVEL -> {
                val volume = intent.getFloatExtra(SoundAuraWidget.EXTRA_VOLUME_LEVEL, 1f)
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val volKey = floatPreferencesKey(PrefKeys.masterVolume)
                        context.dataStore.edit(volKey, volume)
                        
                        withContext(Dispatchers.Main) {
                            SoundAuraWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                            PresetWidget.sendAction(context, SoundAuraWidget.ACTION_UPDATE_WIDGET)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun scheduleAutoHide(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            action = SoundAuraWidget.ACTION_HIDE_VOLUME_SLIDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 5000, pendingIntent)
    }

    private fun cancelAutoHide(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SoundAuraWidgetReceiver::class.java).apply {
            action = SoundAuraWidget.ACTION_HIDE_VOLUME_SLIDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }
}
