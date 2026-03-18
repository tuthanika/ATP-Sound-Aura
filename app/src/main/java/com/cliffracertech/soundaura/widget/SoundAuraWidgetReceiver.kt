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
            SoundAuraWidget.ACTION_PLAY_PAUSE,
            SoundAuraWidget.ACTION_STOP -> {
                SoundAuraWidget.sendAction(context, action)
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
            SoundAuraWidget.ACTION_TOGGLE_VOLUME_SLIDER -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val key = booleanPreferencesKey(PrefKeys.isVolumeSliderVisible)
                        val current = context.dataStore.data.first()[key] ?: false
                        context.dataStore.edit(key, !current)
                        
                        if (!current) { // Si se acaba de mostrar, programar auto-hide
                            scheduleAutoHide(context)
                        } else {
                            cancelAutoHide(context)
                        }
                        
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
                        val visKey = booleanPreferencesKey(PrefKeys.isVolumeSliderVisible)
                        context.dataStore.edit(volKey, volume)
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
            SoundAuraWidget.ACTION_HIDE_VOLUME_SLIDER -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val key = booleanPreferencesKey(PrefKeys.isVolumeSliderVisible)
                        context.dataStore.edit(key, false)
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
