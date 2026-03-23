/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.model.database

import android.net.Uri
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapInfo
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.cliffracertech.soundaura.service.ActivePlaylistSummary
import kotlinx.coroutines.flow.Flow

typealias LibraryPlaylist = com.cliffracertech.soundaura.library.Playlist

private const val librarySelectBase =
    "SELECT id, name, isActive, " +
           "COUNT(playlistId) = 1 AS isSingleTrack, " +
           "shuffle, playSequentially, MIN(track.loopEnabled) as loopEnabled, playlist.volume, volumeBoostDb, " +
           "SUM(track.hasError) = COUNT(track.hasError) as hasError " +
    "FROM playlist " +
    "JOIN playlistTrack ON playlist.id = playlistTrack.playlistId " +
    "JOIN track on playlistTrack.trackUri = track.uri "

private const val librarySelect =
    librarySelectBase + "GROUP BY playlistTrack.playlistId"

private const val librarySelectWithFilter =
    librarySelectBase +
    "WHERE name LIKE :filter " +
    "GROUP BY playlistTrack.playlistId"

@Dao abstract class PlaylistDao {
    @Query("SELECT last_insert_rowid()")
    protected abstract suspend fun getLastInsertId(): Long

    @Query("INSERT INTO track (uri) VALUES (:uri)")
    protected abstract suspend fun insertTrack(uri: Uri)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTracks(tracks: List<Track>)

    @Query("INSERT INTO playlist (name, shuffle, playSequentially) VALUES (:name, :shuffle, :playSequentially)")
    protected abstract suspend fun insertPlaylist(name: String, shuffle: Boolean = false, playSequentially: Boolean = true)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPlaylistTrack(playlistTrack: PlaylistTrack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPlaylistTracks(playlistTracks: List<PlaylistTrack>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPlaylists(playlists: List<Playlist>)

    /**
     * Insert a single [Playlist] whose [Playlist.name] and [Playlist.shuffle]
     * vales will be equal to [playlistName] and [shuffle], respectively. The
     * [Uri]s in [tracks] will be added as the contents of the playlist.
     *
     * @return The [Long] id of the newly inserted [Playlist]
     * */
    @Transaction
    open suspend fun insertPlaylist(
        playlistName: String,
        shuffle: Boolean,
        playSequentially: Boolean = true,
        tracks: List<TrackWithVolume>,
        newUris: List<Uri>? = null,
    ): Long {
        insertTracks(newUris?.map(::Track) ?: tracks.map { it.track })
        tracks.forEach { setTrackLoopEnabled(it.uri, it.loopEnabled) }
        insertPlaylist(playlistName, shuffle, playSequentially)
        val id = getLastInsertId()
        insertPlaylistTracks(tracks.mapIndexed { index, track ->
            PlaylistTrack(id, index, track.uri, track.volume)
        })
        return id
    }

    /**
     * Attempt to add multiple single-track playlists. Each value in
     * [names] will be used as a name for a new [Playlist], while the
     * [Uri] with the same index in [uris] will be used as that [Playlist]'s
     * single track. The [Playlist.shuffle] value for the new [Playlist]s
     * will be the default value (i.e. false) due to shuffle having no
     * meaning for single-track playlists.
     *
     * If the [Uri]s in [uris] that are not already a part of any existing
     * [Playlist]s is already known, it can be passed in to the parameter
     * [newUris] to prevent the database inserting already existing tracks.
     */
    @Transaction
    open suspend fun insertSingleTrackPlaylists(
        names: List<String>,
        uris: List<Uri>,
        newUris: List<Uri>? = null,
    ) {
        require(names.size == uris.size) { "Names and URIs lists must have the same size" }
        insertTracks((newUris ?: uris).map(::Track))
        val playlistTracks = List(names.size) {
            insertPlaylist(names[it], playSequentially = false)
            PlaylistTrack(
                playlistId = getLastInsertId(),
                playlistOrder = 0,
                trackUri = uris[it])
        }
        insertPlaylistTracks(playlistTracks)
    }

    /** Delete the playlist identified by [id] from the database. */
    @Query("DELETE FROM playlist WHERE id = :id")
    protected abstract suspend fun deletePlaylistName(id: Long)

    @Query("DELETE FROM playlistTrack WHERE playlistId = :playlistId")
    protected abstract suspend fun deletePlaylistTracks(playlistId: Long)

    @Query("DELETE FROM track WHERE uri IN (:uris)")
    protected abstract suspend fun deleteTracks(uris: List<Uri>)

    /** Delete the [Playlist] identified by [id] along with its contents.
     * @return the [List] of [Uri]s that are no longer a part of any playlist */
    @Transaction
    open suspend fun deletePlaylist(id: Long): List<Uri> {
        val removableTracks = getUniqueUris(id)
        deletePlaylistName(id)
        // playlistTrack.playlistName has an 'on delete: cascade' policy,
        // so the playlistTrack rows don't need to be deleted manually
        deleteTracks(removableTracks)
        return removableTracks
    }

    @Query("SELECT shuffle FROM playlist WHERE id = :id LIMIT 1")
    abstract suspend fun getPlaylistShuffle(id: Long): Boolean

    @Query("SELECT playSequentially FROM playlist WHERE id = :id LIMIT 1")
    abstract suspend fun getPlaylistPlaySequentially(id: Long): Boolean

    @Query("UPDATE playlist SET shuffle = :shuffle WHERE id = :id")
    abstract suspend fun setPlaylistShuffle(id: Long, shuffle: Boolean)

    @Query("UPDATE playlist SET playSequentially = :playSequentially WHERE id = :id")
    abstract suspend fun setPlaylistPlaySequentially(id: Long, playSequentially: Boolean)

    /**
     * Set the playlist identified by [playlistId] to have a [Playlist.shuffle]
     * value equal to [shuffle], and overwrite its tracks to be equal to [tracks].
     *
     * If the [Uri]s in [tracks] that are not already in any other playlists
     * has already been obtained, it can be passed as [newUris] to prevent the
     * database from needing to insert already existing tracks. Likewise, if
     * the [Uri]s that were previously a part of the playlist, but are not in
     * the new [tracks] and are not in any other playlist has already been
     * obtained, it can be passed as [removableUris] to prevent the database
     * from needing to recalculate the [Uri]s that are no longer needed.
     *
     * @return The [List] of [Uri]s that are no longer in any [Playlist] after the change.
     */
    @Transaction
    open suspend fun setPlaylistShuffleAndTracks(
        playlistId: Long,
        shuffle: Boolean,
        playSequentially: Boolean = true,
        tracks: List<TrackWithVolume>,
        newUris: List<Uri>? = null,
        removableUris: List<Uri>? = null,
    ): List<Uri> {
        val removedUris = removableUris ?:
            getUniqueUrisNotIn(tracks.map(TrackWithVolume::uri), playlistId)
        deleteTracks(removedUris)
        insertTracks(newUris?.map(::Track) ?: tracks.map { it.track })
        tracks.forEach { setTrackLoopEnabled(it.uri, it.loopEnabled) }

        deletePlaylistTracks(playlistId)
        insertPlaylistTracks(tracks.mapIndexed { index, track ->
            PlaylistTrack(playlistId, index, track.uri, track.volume)
        })
        setPlaylistShuffle(playlistId, shuffle)
        setPlaylistPlaySequentially(playlistId, playSequentially)
        return removedUris
    }

    /** Return the track uris of the [Playlist] identified by
     * [playlistId] that are not in any other [Playlist]s. */
    @Query("SELECT trackUri FROM playlistTrack " +
           "GROUP BY trackUri HAVING COUNT(playlistId) = 1 " +
                             "AND playlistId = :playlistId")
    protected abstract suspend fun getUniqueUris(playlistId: Long): List<Uri>

    /** Return the track uris of the [Playlist] identified by [playlistId]
     * that are not in any other [Playlist]s and are not in [exceptions]. */
    @Query("SELECT trackUri FROM playlistTrack " +
           "WHERE trackUri NOT IN (:exceptions) " +
           "GROUP BY trackUri HAVING COUNT(playlistId) = 1 " +
                             "AND playlistId = :playlistId")
    abstract suspend fun getUniqueUrisNotIn(exceptions: List<Uri>, playlistId: Long): List<Uri>

    @RawQuery
    protected abstract suspend fun filterNewUris(query: SupportSQLiteQuery): List<Uri>

    suspend fun filterNewUris(tracks: List<Uri>): List<Uri> {
        if (tracks.isEmpty()) return emptyList()
        // The following query requires parentheses around each argument. This
        // is not supported by Room, so the query must be made manually.
        val query = StringBuilder()
            .append("WITH newTrack(uri) AS (VALUES ")
            .apply {
                for (i in 0 until tracks.lastIndex)
                    append("(?), ")
            }.append("(?)) ")
            .append("SELECT newTrack.uri FROM newTrack ")
            .append("LEFT JOIN track ON track.uri = newTrack.uri ")
            .append("WHERE track.uri IS NULL;")
            .toString()
        val args = Array(tracks.size) { tracks[it].toString() }
        return filterNewUris(SimpleSQLiteQuery(query, args))
    }

    /** Return whether or not a [Playlist] whose name matches [name] exists. */
    @Query("SELECT EXISTS(SELECT name FROM playlist WHERE name = :name)")
    abstract suspend fun exists(name: String?): Boolean

    @Query("$librarySelect ORDER BY name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByNameAsc(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY name COLLATE NOCASE DESC")
    abstract fun getPlaylistsSortedByNameDesc(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY id ASC")
    abstract fun getPlaylistsSortedByOrderAdded(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByActiveThenNameAsc(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, name COLLATE NOCASE DESC")
    abstract fun getPlaylistsSortedByActiveThenNameDesc(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, id ASC")
    abstract fun getPlaylistsSortedByActiveThenOrderAdded(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isSingleTrack ASC, name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByPlaylistFirst(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelect ORDER BY isActive DESC, isSingleTrack ASC, name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByActiveThenPlaylistFirst(): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByNameAsc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY name COLLATE NOCASE DESC")
    abstract fun getPlaylistsSortedByNameDesc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY id ASC")
    abstract fun getPlaylistsSortedByOrderAdded(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY isActive DESC, name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByActiveThenNameAsc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY isActive DESC, name COLLATE NOCASE DESC")
    abstract fun getPlaylistsSortedByActiveThenNameDesc(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY isActive DESC, id ASC")
    abstract fun getPlaylistsSortedByActiveThenOrderAdded(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY isSingleTrack ASC, name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByPlaylistFirst(filter: String): Flow<List<LibraryPlaylist>>

    @Query("$librarySelectWithFilter ORDER BY isActive DESC, isSingleTrack ASC, name COLLATE NOCASE ASC")
    abstract fun getPlaylistsSortedByActiveThenPlaylistFirst(filter: String): Flow<List<LibraryPlaylist>>

    @Query("SELECT NOT EXISTS(SELECT 1 FROM playlist WHERE isActive)")
    abstract fun getNoPlaylistsAreActive(): Flow<Boolean>

    /** Return a [Flow] that updates with a [Map] of each active
     * [Playlist] (represented as an [ActivePlaylistSummary]
     * mapped to its tracks (represented as a [List] of [TrackWithVolume]s). */
    @MapInfo(valueColumn = "uri")
    @Query("SELECT id, shuffle, playSequentially, playlist.volume, volumeBoostDb, uri, hasError, loopEnabled, playlistTrack.volume " +
           "FROM playlist " +
           "JOIN playlistTrack ON playlist.id = playlistTrack.playlistId " +
           "JOIN track on playlistTrack.trackUri = track.uri " +
           "WHERE isActive ORDER by playlistOrder")
    abstract fun getActivePlaylistsAndTracks(): Flow<Map<ActivePlaylistSummary, List<TrackWithVolume>>>

    @Query("SELECT name FROM playlist")
    abstract suspend fun getPlaylistNames(): List<String>

    @Query("SELECT uri, hasError, loopEnabled, volume FROM playlistTrack " +
           "JOIN track on playlistTrack.trackUri = track.uri " +
           "WHERE playlistId = :id ORDER by playlistOrder")
    abstract suspend fun getPlaylistTracks(id: Long): List<TrackWithVolume>

    /** Set the volume for the track identified by [trackUri] within the playlist identified by [playlistId]. */
    @Query("UPDATE playlistTrack SET volume = :volume WHERE playlistId = :playlistId AND trackUri = :trackUri")
    abstract suspend fun setPlaylistTrackVolume(playlistId: Long, trackUri: Uri, volume: Float)

    /** Rename the [Playlist] identified by [id] to [newName]. */
    @Query("UPDATE playlist SET name = :newName WHERE id = :id")
    abstract suspend fun rename(id: Long, newName: String)

    /** Toggle the [Playlist.isActive] field of the [Playlist] identified by [id]. */
    @Query("UPDATE playlist set isActive = 1 - isActive WHERE id = :id")
    abstract suspend fun toggleIsActive(id: Long)

    @Query("UPDATE playlist SET isActive = 0")
    abstract suspend fun deactivateAll()

    /** Set the [Playlist.volume] field of the [Playlist] identified by [id]. */
    @Query("UPDATE playlist SET volume = :volume WHERE id = :id")
    abstract suspend fun setVolume(id: Long, volume: Float)

    /** Set the [Playlist.volumeBoostDb] field of the [Playlist identified by [id]. */
    @Query("UPDATE playlist SET volumeBoostDb = :dbBoost WHERE id = :id")
    abstract suspend fun setVolumeBoostDb(id: Long, dbBoost: Int)

    @Query("UPDATE track SET hasError = 1 WHERE uri in (:uris)")
    abstract suspend fun setTracksHaveError(uris: List<Uri>)

    @Query("SELECT * FROM track")
    abstract suspend fun getAllTracks(): List<Track>

    @Query("SELECT * FROM playlist")
    abstract suspend fun getAllPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlistTrack")
    abstract suspend fun getAllPlaylistTracks(): List<PlaylistTrack>

    @Query("UPDATE track SET hasError = :hasError WHERE uri = :uri")
    abstract suspend fun setTrackHasError(uri: Uri, hasError: Boolean)

    @Query("UPDATE track SET loopEnabled = :loopEnabled WHERE uri = :uri")
    abstract suspend fun setTrackLoopEnabled(uri: Uri, loopEnabled: Boolean)

    @Query("SELECT * FROM track WHERE uri = :uri")
    abstract suspend fun getTrack(uri: Uri): Track?

    @Query("UPDATE playlistTrack SET trackUri = :newUri WHERE trackUri = :oldUri")
    abstract suspend fun updatePlaylistTrackUris(oldUri: Uri, newUri: Uri)

    @Query("SELECT COUNT(*) FROM playlistTrack WHERE trackUri = :uri")
    abstract suspend fun getTrackUseCount(uri: Uri): Int

    @Query("DELETE FROM track WHERE uri = :uri")
    abstract suspend fun deleteTrack(uri: Uri)

    @Transaction
    open suspend fun updateTrackUri(oldUri: Uri, newUri: Uri) {
        if (oldUri == newUri) return
        val existingNewTrack = getTrack(newUri)
        if (existingNewTrack == null) {
            val oldTrack = getTrack(oldUri) ?: return
            insertTracks(listOf(oldTrack.copy(uri = newUri, hasError = false)))
        } else {
            setTrackHasError(newUri, false)
        }
        updatePlaylistTrackUris(oldUri, newUri)
        if (getTrackUseCount(oldUri) == 0)
            deleteTrack(oldUri)
    }

    // --- METHOD FOR THE WIDGET ---
    @Query("""
        SELECT id, name, shuffle, playSequentially, isActive,
        COUNT(playlistId) = 1 AS isSingleTrack,
        MIN(track.loopEnabled) as loopEnabled,
        playlist.volume, volumeBoostDb,
        SUM(track.hasError) = COUNT(track.hasError) as hasError
        FROM playlist
        JOIN playlistTrack ON playlist.id = playlistTrack.playlistId
        JOIN track on playlistTrack.trackUri = track.uri
        GROUP BY playlistTrack.playlistId
        ORDER BY isActive DESC, name COLLATE NOCASE ASC
        """)
    abstract suspend fun getPlaylistsForWidget(): List<LibraryPlaylist>

}