package com.cliffracertech.soundaura.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.SoundAuraApplication
import com.cliffracertech.soundaura.library.Playlist
import com.cliffracertech.soundaura.model.database.PlaylistDao
import kotlinx.coroutines.runBlocking

class RemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var playlists: List<Playlist> = emptyList()
    private lateinit var playlistDao: PlaylistDao

    override fun onCreate() {
        // Get PlaylistDao from the application
        val application = context.applicationContext as SoundAuraApplication
        playlistDao = application.database.playlistDao()
    }

    override fun onDataSetChanged() {
        // Get the list of playlists from the database
        runBlocking {
            // Use the method that returns a list of LibraryPlaylist
            playlists = playlistDao.getPlaylistsForWidget()
        }
    }
    
    override fun onDestroy() {
        playlists = emptyList()
    }

    override fun getCount(): Int = playlists.size

    override fun getViewAt(position: Int): RemoteViews {
        val playlist = playlists[position]
        val views = RemoteViews(context.packageName, R.layout.widget_playlist_item)

        // Set the playlist name with a checkmark if active
        val displayName = if (playlist.isActive) "✓ ${playlist.name}" else playlist.name
        views.setTextViewText(R.id.widget_playlist_name, displayName)

        // Set background for the active item
        if (playlist.isActive) {
            views.setInt(R.id.widget_playlist_item_container, "setBackgroundResource", R.drawable.widget_playlist_item_bg)
            views.setInt(R.id.widget_playlist_item_container, "setBackgroundColor", context.getColor(R.color.widget_active_item_bg))
        } else {
            views.setInt(R.id.widget_playlist_item_container, "setBackgroundResource", 0)
        }

        // Set button +/- icon
        val buttonIcon = if (playlist.isActive) R.drawable.ic_baseline_remove_24
                         else R.drawable.ic_baseline_add_24
        views.setImageViewResource(R.id.widget_playlist_button, buttonIcon)

        // Set content description
        val buttonDesc = if (playlist.isActive) 
            context.getString(R.string.set_playlist_inactive_description, playlist.name)
        else
            context.getString(R.string.set_playlist_active_description, playlist.name)
        views.setContentDescription(R.id.widget_playlist_button, buttonDesc)

        // Set intent for when the button is clicked
        val fillInIntent = Intent().apply {
            action = SoundAuraWidget.ACTION_TOGGLE_PLAYLIST
            putExtra(SoundAuraWidget.EXTRA_PLAYLIST_ID, playlist.id)
            putExtra(SoundAuraWidget.EXTRA_PLAYLIST_NAME, playlist.name)
        }
        views.setOnClickFillInIntent(R.id.widget_playlist_button, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = playlists[position].id

    override fun hasStableIds(): Boolean = true
}
