package com.cliffracertech.soundaura.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.SoundAuraApplication
import com.cliffracertech.soundaura.model.database.Preset
import com.cliffracertech.soundaura.model.database.PresetDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class PresetRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var presets: List<Preset> = emptyList()
    private lateinit var presetDao: PresetDao

    override fun onCreate() {
        val application = context.applicationContext as SoundAuraApplication
        presetDao = application.database.presetDao()
    }

    override fun onDataSetChanged() {
        runBlocking {
            presets = presetDao.getPresetList().first()
        }
    }
    
    override fun onDestroy() {
        presets = emptyList()
    }

    override fun getCount(): Int = presets.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= presets.size) return RemoteViews(context.packageName, R.layout.widget_preset_item)
        
        val preset = presets[position]
        val views = RemoteViews(context.packageName, R.layout.widget_preset_item)

        views.setTextViewText(R.id.widget_preset_name, preset.name)

        val fillInIntent = Intent().apply {
            action = PresetWidget.ACTION_LOAD_PRESET
            putExtra(PresetWidget.EXTRA_PRESET_NAME, preset.name)
        }
        views.setOnClickFillInIntent(R.id.widget_preset_item_container, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
