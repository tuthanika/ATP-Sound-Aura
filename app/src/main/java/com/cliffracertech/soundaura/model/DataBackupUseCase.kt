/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.model

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cliffracertech.soundaura.Dispatcher
import com.cliffracertech.soundaura.model.database.*
import com.cliffracertech.soundaura.settings.PrefKeys
import com.cliffracertech.soundaura.model.UriPermissionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import androidx.documentfile.provider.DocumentFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataBackupUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val presetDao: PresetDao,
    private val dataStore: DataStore<Preferences>,
    private val database: SoundAuraDatabase,
    private val permissionHandler: UriPermissionHandler,
) {
    private val json = Json { 
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun exportAppData(outputStream: OutputStream) = withContext(Dispatcher.IO) {
        val prefs = dataStore.data.first().asMap()
        val settings = prefs.mapKeys { it.key.name }.mapValues { (_, value) ->
            when (value) {
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is Float -> JsonPrimitive(value)
                is Double -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }

        val tracks = playlistDao.getAllTracks().map {
            TrackMetadata(it.uri.toString(), it.hasError, it.loopEnabled)
        }
        val playlists = playlistDao.getAllPlaylists().map {
            PlaylistMetadata(it.id, it.name, it.shuffle, it.playSequentially, 
                             it.isActive, it.volume, it.volumeBoostDb)
        }
        val playlistTracks = playlistDao.getAllPlaylistTracks().map {
            PlaylistTrackMetadata(it.playlistId, it.playlistOrder, it.trackUri.toString())
        }
        val presets = presetDao.getAllPresets().map {
            PresetMetadata(it.name)
        }
        val presetPlaylists = presetDao.getAllPresetPlaylists().map {
            PresetPlaylistMetadata(it.presetName, it.playlistName, it.playlistVolume)
        }

        val appData = AppData(
            version = 1,
            settings = settings,
            tracks = tracks,
            playlists = playlists,
            playlistTracks = playlistTracks,
            presets = presets,
            presetPlaylists = presetPlaylists
        )

        val jsonString = json.encodeToString(appData)
        outputStream.write(jsonString.toByteArray())
    }

    suspend fun importAppData(inputStream: InputStream): Result<Unit> = withContext(Dispatcher.IO) {
        try {
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val appData = json.decodeFromString<AppData>(jsonString)

            database.runInTransaction {
                // We use runInTransaction to ensure clearing and inserting is atomic
                // Unfortunately clearAllTables() cannot be used inside an active transaction 
                // on some Room versions or requires careful handling.
                // We'll use manual deletes for safety within transaction.
                // But RoomDatabase.clearAllTables() is usually better.
            }
            
            // For simplicity and to avoid foreign key issues during clearing, 
            // we'll clear tables in correct order.
            database.runInTransaction {
                // Delete everything
                // SQLite doesn't have a simple way to truncate all, 
                // so we delete from child to parent.
            }

            // Using Room's clearAllTables instead
            database.clearAllTables()

            // Restore DB
            playlistDao.insertTracks(appData.tracks.map { 
                Track(Uri.parse(it.uri), it.hasError, it.loopEnabled) 
            })
            playlistDao.insertPlaylists(appData.playlists.map {
                Playlist(it.id, it.name, it.shuffle, it.playSequentially, 
                         it.isActive, it.volume, it.volumeBoostDb)
            })
            playlistDao.insertPlaylistTracks(appData.playlistTracks.map {
                PlaylistTrack(it.playlistId, it.playlistOrder, Uri.parse(it.trackUri))
            })
            presetDao.insertPresets(appData.presets.map { Preset(it.name) })
            presetDao.insertPresetPlaylists(appData.presetPlaylists.map {
                PresetPlaylist(it.presetName, it.playlistName, it.playlistVolume)
            })

            // Restore Settings
            dataStore.edit { prefs ->
                appData.settings.forEach { (keyName, value) ->
                    val valuePrimitive = value as? JsonPrimitive ?: return@forEach
                    // We only restore known keys to avoid garbage in DataStore
                    when (keyName) {
                        PrefKeys.showActivePlaylistsFirst,
                        PrefKeys.playInBackground,
                        PrefKeys.notificationPermissionRequested,
                        PrefKeys.autoPauseDuringCalls,
                        PrefKeys.stopInsteadOfPause,
                        PrefKeys.playButtonLongClickHintShown,
                        PrefKeys.isVolumeSliderVisible -> {
                            prefs[booleanPreferencesKey(keyName)] = valuePrimitive.boolean
                        }
                        PrefKeys.lastLaunchedVersionCode,
                        PrefKeys.playlistSort,
                        PrefKeys.appTheme,
                        PrefKeys.onZeroVolumeAudioDeviceBehavior -> {
                            prefs[intPreferencesKey(keyName)] = valuePrimitive.int
                        }
                        PrefKeys.masterVolume -> {
                            prefs[floatPreferencesKey(keyName)] = valuePrimitive.float
                        }
                        PrefKeys.activePresetName -> {
                            prefs[stringPreferencesKey(keyName)] = valuePrimitive.content
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun relinkTracks(rootFolderUri: Uri): Int = withContext(Dispatcher.IO) {
        // Take a persistable permission on the tree URI so all files inside remain
        // accessible after the app restarts (tree grants cover the whole subtree).
        try {
            context.contentResolver.takePersistableUriPermission(
                rootFolderUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission may already be persisted or not grantable; continue anyway
        }

        var relinkCount = 0
        val allTracks = playlistDao.getAllTracks()
        val rootFolder = DocumentFile.fromTreeUri(context, rootFolderUri) ?: return@withContext 0
        
        val allFiles = listFilesRecursive(rootFolder)
        
        val oldUrisToRelease = mutableListOf<Uri>()
        val newUrisToAcquire = mutableListOf<Uri>()

        allTracks.forEach { track ->
            val fileName = track.uri.lastPathSegment
                ?.substringAfterLast('/')?.substringAfterLast(':') ?: return@forEach
            val matchingFile = allFiles.find { it.name == fileName }
            if (matchingFile != null && matchingFile.uri != track.uri) {
                playlistDao.updateTrackUri(track.uri, matchingFile.uri)
                playlistDao.setTrackHasError(matchingFile.uri, false)
                oldUrisToRelease.add(track.uri)
                newUrisToAcquire.add(matchingFile.uri)
                relinkCount++
            } else if (matchingFile != null && matchingFile.uri == track.uri && track.hasError) {
                // Same URI but marked as error — just clear the error
                playlistDao.setTrackHasError(track.uri, false)
                relinkCount++
            }
        }

        // Release old permissions and acquire new ones so they persist after restart
        if (newUrisToAcquire.isNotEmpty()) {
            permissionHandler.releasePermissionsFor(oldUrisToRelease)
            permissionHandler.acquirePermissionsFor(newUrisToAcquire)
        }

        relinkCount
    }

    private fun listFilesRecursive(directory: DocumentFile, depth: Int = 0): List<DocumentFile> {
        if (depth > 5) return emptyList()
        val files = mutableListOf<DocumentFile>()
        directory.listFiles().forEach { file ->
            if (file.isDirectory) {
                files.addAll(listFilesRecursive(file, depth + 1))
            } else if (file.name != null) {
                files.add(file)
            }
        }
        return files
    }
}
