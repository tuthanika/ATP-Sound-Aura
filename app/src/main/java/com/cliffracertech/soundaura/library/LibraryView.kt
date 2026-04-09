/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.library

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.SnackbarDuration
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cliffracertech.soundaura.Dispatcher
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.collectAsState
import com.cliffracertech.soundaura.launchIO
import com.cliffracertech.soundaura.model.MessageHandler
import com.cliffracertech.soundaura.model.ModifyLibraryUseCase
import com.cliffracertech.soundaura.model.PlaybackState
import com.cliffracertech.soundaura.model.ReadLibraryUseCase
import com.cliffracertech.soundaura.model.SearchQueryState
import com.cliffracertech.soundaura.model.StringResource
import com.cliffracertech.soundaura.model.database.Track
import com.cliffracertech.soundaura.screenSizeBasedHorizontalPadding
import com.cliffracertech.soundaura.ui.tweenDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** LibraryState's subtypes, [Loading], [Empty], and [Content], represent
 * the possible states for an asynchronously loaded library of [Playlist]s. */
sealed class LibraryState {
    /** The library's contents are still being loaded */
    data object Loading: LibraryState()

    /** The library is empty. [message] can be resolved to obtain a [String]
     * describing the empty content. This can be used to, e.g., explain that
     * the library is empty due to the current search criteria. */
    class Empty(val message: StringResource): LibraryState()

    /** The library's content has been loaded, and can be obtained through the
     * property [playlists]. The value of the property [playlistViewCallback]
     * can be used as an item callback in, e.g., a [PlaylistView]. */
    class Content(
        private val getPlaylists: () -> ImmutableList<Playlist>?,
        val playlistViewCallback: PlaylistViewCallback,
    ): LibraryState() {
        val playlists get() = getPlaylists()
    }
}

/**
 * A [LazyColumn] to display all of the provided [Playlist]s with instances of [PlaylistView].
 *
 * @param modifier The [Modifier] that will be used for the TrackList
 * @param lazyListState The [LazyListState] used for the library's scrolling state
 * @param contentPadding The [PaddingValues] instance that will be used as
 *     the content padding for the list of items
 * @param shownDialog A [PlaylistDialog] instance that describes the [Playlist]
 *     related dialog that should be shown, or null if no dialog needs to be shown
 * @param libraryState A [LibraryState] instance that describes the UI state of the LibraryView
 */
@Composable fun LibraryView(
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues,
    shownDialog: PlaylistDialog?,
    libraryState: LibraryState,
) {
    PlaylistDialogShower(shownDialog)

    Crossfade(
        targetState = libraryState,
        modifier = modifier,
        animationSpec = tween(tweenDuration),
        label = "LibraryView loading/empty/content crossfade",
    ) { viewState -> when(viewState) {
        is LibraryState.Loading -> {
            // The CircularProgressIndicator is not center aligned properly when
            // Modifier.wrapContentSize() is used, so a fillMaxSize box is used instead
            Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
                CircularProgressIndicator(strokeCap = StrokeCap.Round)
            }
        } is LibraryState.Empty -> {
            val context = LocalContext.current
            val text = remember(viewState) { viewState.message.resolve(context) }
            Text(text = text,
                 modifier = Modifier
                     .fillMaxSize()
                     .padding(contentPadding)
                     .screenSizeBasedHorizontalPadding(48.dp)
                     .wrapContentSize(),
                 textAlign = TextAlign.Justify)
        } is LibraryState.Content -> {
            val items = (viewState.playlists ?: emptyList()) as ImmutableList<Playlist>
            LazyColumn(
                Modifier, lazyListState, contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = items,
                    key = Playlist::id::get,
                    contentType = { "playlist_item" },
                ) { playlist ->
                    PlaylistView(
                        playlist = playlist,
                        callback = viewState.playlistViewCallback,
                        lightweightContent = lazyListState.isScrollInProgress,
                    )
                }
            }
        }
    }}
}

/**
 * A [ViewModel] to provide state and callbacks for an instance of LibraryView.
 *
 * The most recent list of all playlists is provided via the property
 * [playlists]. The [PlaylistViewCallback] that should be used for item
 * interactions is provided via the property [itemCallback]. The state
 * of any dialogs that should be shown are provided via the property
 * [shownDialog].
 */
@HiltViewModel class LibraryViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
    private val readLibrary: com.cliffracertech.soundaura.model.ReadLibraryUseCase,
    private val modifyLibrary: com.cliffracertech.soundaura.model.ModifyLibraryUseCase,
    private val uriPermissionHandler: com.cliffracertech.soundaura.model.UriPermissionHandler,
    private val searchQueryState: com.cliffracertech.soundaura.model.SearchQueryState,
    private val messageHandler: com.cliffracertech.soundaura.model.MessageHandler,
    playbackState: com.cliffracertech.soundaura.model.PlaybackState,
) : ViewModel() {
    private val scope = viewModelScope + Dispatcher.Immediate

    var shownDialog by mutableStateOf<PlaylistDialog?>(null)
    private fun dismissDialog() { shownDialog = null }

    private val itemCallback = object : PlaylistViewCallback {
        override fun onAddRemoveButtonClick(playlist: Playlist) {
            scope.launchIO { modifyLibrary.togglePlaylistIsActive(playlist.id) }
        }
        override fun onVolumeChange(playlist: Playlist, volume: Float) {
            playbackState.setPlaylistVolume(playlist.id, volume)
        }
        override fun onVolumeChangeFinished(playlist: Playlist, volume: Float) {
            scope.launchIO { modifyLibrary.setPlaylistVolume(playlist.id, volume) }
        }
        override fun onRenameClick(playlist: Playlist) {
            shownDialog = PlaylistDialog.Rename(
                target = playlist,
                namingState = modifyLibrary.renameState(
                    playlist.id, playlist.name, scope, ::dismissDialog),
                onDismissRequest = ::dismissDialog)
        }
        override fun onExtraOptionsClick(playlist: Playlist) {
            scope.launchIO {
                val existingTracks = readLibrary.getPlaylistTracks(playlist.id)
                val shuffleEnabled = readLibrary.getPlaylistShuffle(playlist.id)
                val playSequentially = readLibrary.getPlaylistPlaySequentially(playlist.id)
                assert((existingTracks.size == 1) == playlist.isSingleTrack)
                withContext(Dispatcher.Immediate) {
                    if (playlist.isSingleTrack)
                        showFileChooser(playlist, existingTracks)
                    else showPlaylistOptions(playlist, existingTracks, shuffleEnabled, playSequentially)
                }
            }
        }
        override fun onToggleLoopClick(playlist: Playlist) {
            if (!playlist.isSingleTrack) return
            scope.launchIO {
                val track = readLibrary.getPlaylistTracks(playlist.id).firstOrNull() ?: return@launchIO
                modifyLibrary.setTrackLoopEnabled(track.uri, !track.loopEnabled)
            }
        }
        override fun onChangePathClick(playlist: Playlist) {
            if (!playlist.isSingleTrack) return
            scope.launchIO {
                val track = readLibrary.getPlaylistTracks(playlist.id).firstOrNull() ?: return@launchIO
                withContext(Dispatcher.Immediate) {
                    showPickSingleFileDialog(playlist, track.uri)
                }
            }
        }
        override fun onFolderPathChangeClick(playlist: Playlist) {
            showPickFolderDialog(playlist)
        }
        override fun onTrackChangePathClick(playlist: Playlist, trackUri: android.net.Uri) {
            showPickSingleFileDialog(playlist, trackUri)
        }
        override fun onVolumeBoostClick(playlist: Playlist) {
            shownDialog = PlaylistDialog.BoostVolume(
                target = playlist,
                onDismissRequest = ::dismissDialog,
                onConfirm = { volumeBoostDb ->
                    dismissDialog()
                    scope.launchIO {
                        modifyLibrary.setPlaylistVolumeBoostDb(playlist.id, volumeBoostDb)
                    }
                })
        }
        override fun onRemoveClick(playlist: Playlist) {
            if (playlist.hasError)
                scope.launchIO { modifyLibrary.removePlaylist(playlist.id) }
            else shownDialog = PlaylistDialog.Remove(
                target = playlist,
                onDismissRequest = ::dismissDialog,
                onConfirmClick = {
                    dismissDialog()
                    scope.launchIO { modifyLibrary.removePlaylist(playlist.id) }
                })
        }
    }

    private val playlists by readLibrary.playlistsFlow.collectAsState(null, scope)
    private val noSearchResultsState = LibraryState.Empty(StringResource(R.string.no_search_results_message))
    private val emptyLibraryState = LibraryState.Empty(StringResource(R.string.empty_library_message))
    private val contentState = LibraryState.Content(::playlists, itemCallback)

    val viewState get() = when {
        playlists == null ->
            LibraryState.Loading
        playlists?.isEmpty() == true -> {
            if (searchQueryState.isActive)
                noSearchResultsState
            else emptyLibraryState
        } else -> contentState
    }

    private fun showFileChooser(
        target: Playlist,
        existingTracks: List<com.cliffracertech.soundaura.model.database.TrackWithVolume>,
        shuffleEnabled: Boolean = false,
        playSequentially: Boolean = true,
    ) {
        shownDialog = PlaylistDialog.FileChooser(
            target, messageHandler, existingTracks,
            onDismissRequest = {
                // If the file chooser was arrived at by selecting the 'create playlist'
                // option for a single track playlist, we want the back button/gesture to
                // completely dismiss the dialog. If the file chooser was arrived at by
                // selecting the 'add more files' button in the playlist options dialog
                // of an existing multi-track playlist, then we want the back button/
                // gesture to go back to the playlist options dialog for that playlist.
                if (existingTracks.size == 1)
                    dismissDialog()
                else showPlaylistOptions(target, existingTracks, shuffleEnabled, playSequentially)
            }, onChosenFilesValidated = { validatedFiles ->
                val newTrackList = existingTracks + validatedFiles.map { 
                    com.cliffracertech.soundaura.model.database.TrackWithVolume(it, false, true, 1f)
                }
                showPlaylistOptions(target, newTrackList, shuffleEnabled, playSequentially)
            })
    }

    private fun showPlaylistOptions(
        target: Playlist,
        existingTracks: List<com.cliffracertech.soundaura.model.database.TrackWithVolume>,
        shuffleEnabled: Boolean,
        playSequentially: Boolean,
    ) {
        shownDialog = PlaylistDialog.PlaylistOptions(
            target, existingTracks, shuffleEnabled, playSequentially, ::dismissDialog,
            onAddFilesClick = {
                showFileChooser(target, existingTracks, shuffleEnabled, playSequentially)
            }, onTrackChangePathClick = { trackUri ->
                showPickSingleFileDialog(target, trackUri)
            }, onConfirm = { newShuffle, newPlaySequentially, newTracks ->
                scope.launchIO {
                    val result = modifyLibrary.setPlaylistShuffleAndTracks(
                        target.id, newShuffle, newPlaySequentially, newTracks)
                    withContext(Dispatcher.Immediate) {
                        when (result) {
                            is ModifyLibraryUseCase.Result.Success ->
                                dismissDialog()
                            is ModifyLibraryUseCase.Result.NewTracksNotAdded ->
                                showRequestStoragePermission(
                                    target, newShuffle, newPlaySequentially, newTracks, result)
                        }
                    }
                }
            })
    }

    private fun showRequestStoragePermission(
        target: Playlist,
        shuffleEnabled: Boolean,
        playSequentially: Boolean,
        existingTracks: List<com.cliffracertech.soundaura.model.database.TrackWithVolume>,
        result: ModifyLibraryUseCase.Result.NewTracksNotAdded,
    ) {
        shownDialog = PlaylistDialog.RequestStoragePermissionExplanation(
            target = target,
            permissionsUsed = result.permissionsUsed,
            permissionsAllowed = result.permissionAllowance,
            onDismissRequest = ::dismissDialog,
            onOkClick = {
                shownDialog = PlaylistDialog.RequestStoragePermission(
                    target = target,
                    onDismissRequest = ::dismissDialog,
                    onResult = { permissionGranted ->
                        dismissDialog()
                        if (permissionGranted) scope.launchIO {
                            modifyLibrary.setPlaylistShuffleAndTracks(
                                target.id, shuffleEnabled, playSequentially,
                                existingTracks + result.unaddedUris.map {
                                    com.cliffracertech.soundaura.model.database.TrackWithVolume(it, false, true, 1f)
                                })
                        } else messageHandler.postMessage(
                            stringResId = R.string.cant_add_playlist_tracks_warning,
                            duration = SnackbarDuration.Long)
                    })
            })
    }

    private fun showPickSingleFileDialog(target: Playlist, trackUri: android.net.Uri) {
        shownDialog = PlaylistDialog.PickSingleFile(
            target = target,
            trackUri = trackUri,
            onDismissRequest = ::dismissDialog,
            onFileChosen = { newUri ->
                scope.launchIO {
                    modifyLibrary.updateTrackUri(trackUri, newUri)
                }
            })
    }

    private fun showPickFolderDialog(target: Playlist) {
        shownDialog = PlaylistDialog.PickFolder(
            target = target,
            onDismissRequest = ::dismissDialog,
            onFolderChosen = { newFolderUri ->
                scope.launchIO {
                    uriPermissionHandler.acquirePermissionsFor(listOf(newFolderUri))
                    val tracks: List<com.cliffracertech.soundaura.model.database.TrackWithVolume> =
                        readLibrary.getPlaylistTracks(target.id)
                    val newFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, newFolderUri) ?: return@launchIO
                    val newFolderFiles = newFolder.listFiles()

                    val extractBaseName = { uri: Uri ->
                        val file = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                        (file?.name ?: uri.lastPathSegment ?: "")
                            .substringAfterLast('/')
                            .substringAfterLast(':')
                    }

                    for (track in tracks) {
                        val oldName = extractBaseName(track.uri)
                        if (oldName.isEmpty()) continue
                        
                        val matchingNewFile = newFolderFiles.find { 
                            val newName = it.name ?: it.uri.lastPathSegment ?: ""
                            newName.substringAfterLast('/')
                                   .substringAfterLast(':') == oldName
                        }
                        if (matchingNewFile != null) {
                            modifyLibrary.updateTrackUri(track.uri, matchingNewFile.uri)
                        }
                    }
                }
            })
    }
}

/**
 * Show a [LibraryView] that uses an instance of [LibraryViewModel] for its state.
 *
 * @param modifier The [Modifier] that will be used for the TrackList.
 * @param padding A [PaddingValues] instance whose values will be
 *     as the contentPadding for the TrackList
*  @param state The [LazyListState] used for the TrackList. state
 *     defaults to an instance of LazyListState returned from a
 *     [rememberLazyListState] call, but can be overridden here in
 *     case, e.g., the scrolling position needs to be remembered
 *     even when the SoundAuraTrackList leaves the composition.
 */
@Composable fun SoundAuraLibraryView(
    modifier: Modifier = Modifier,
    padding: PaddingValues,
    state: LazyListState = rememberLazyListState(),
) = Surface(modifier, color = MaterialTheme.colors.background) {
    val viewModel: LibraryViewModel = viewModel()
    LibraryView(
        modifier = modifier,
        lazyListState = state,
        contentPadding = padding,
        shownDialog = viewModel.shownDialog,
        libraryState = viewModel.viewState)
}