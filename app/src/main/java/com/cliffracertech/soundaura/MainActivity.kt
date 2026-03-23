/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cliffracertech.soundaura.addbutton.AddButton
import com.cliffracertech.soundaura.appbar.SoundAuraAppBar
import com.cliffracertech.soundaura.library.SoundAuraLibraryView
import com.cliffracertech.soundaura.mediacontroller.MediaControllerSizes
import com.cliffracertech.soundaura.mediacontroller.SoundAuraMediaController
import com.cliffracertech.soundaura.model.MessageHandler
import com.cliffracertech.soundaura.model.NavigationState
import com.cliffracertech.soundaura.model.PlaybackState
import com.cliffracertech.soundaura.settings.AppSettings
import com.cliffracertech.soundaura.settings.AppTheme
import com.cliffracertech.soundaura.settings.PrefKeys
import com.cliffracertech.soundaura.ui.SlideAnimatedContent
import com.cliffracertech.soundaura.ui.theme.SoundAuraTheme
import com.cliffracertech.soundaura.ui.tweenDuration
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel class MainActivityViewModel @Inject constructor(
    messageHandler: MessageHandler,
    private val dataStore: DataStore<Preferences>,
    private val navigationState: NavigationState,
    private val playbackState: PlaybackState,
) : ViewModel() {
    private val scope = viewModelScope + Dispatcher.Immediate
    val messages = messageHandler.messages
    val showingAppSettings get() = navigationState.showingAppSettings
    val showingPresetSelector get() = navigationState.mediaControllerState.isExpanded

    private val appThemeKey = intPreferencesKey(PrefKeys.appTheme)
    val appTheme = dataStore.data
        .map { it[appThemeKey] ?: AppTheme.UseSystem.ordinal }
        .map { AppTheme.values()[it] }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val lastLaunchedVersionCodeKey = intPreferencesKey(PrefKeys.lastLaunchedVersionCode)
    val lastLaunchedVersionCode by dataStore.preferenceState(
        key = lastLaunchedVersionCodeKey,
        initialValue = 0,
        defaultValue = 9, // version code 9 was the last version code before
        scope = scope)    // the lastLaunchedVersionCode was introduced
    fun onNewVersionDialogDismiss() {
        dataStore.edit(lastLaunchedVersionCodeKey, BuildConfig.VERSION_CODE, scope)
    }

    fun onBackButtonClick() = navigationState.onBackButtonClick()

    fun onKeyDown(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            playbackState.toggleIsPlaying()
            true
        } KeyEvent.KEYCODE_MEDIA_PLAY -> {
            if (!playbackState.isPlaying) {
                playbackState.toggleIsPlaying()
                true
            } else false
        } KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            if (playbackState.isPlaying) {
                playbackState.toggleIsPlaying()
                true
            } else false
        } KeyEvent.KEYCODE_MEDIA_STOP -> {
            if (playbackState.isPlaying) {
                playbackState.toggleIsPlaying()
                true
            } else false
        } else -> false
    }
}

val LocalWindowSizeClass = compositionLocalOf {
    WindowSizeClass.calculateFromSize(DpSize(0.dp, 0.dp))
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainActivityViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }

        splashScreen.setKeepOnScreenCondition {
            viewModel.appTheme.value == null
        }

        setContentWithTheme {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    viewModel.messages.collect { message ->
                        message.showAsSnackbar(this@MainActivity, snackbarHostState)
                    }
                }

                androidx.activity.compose.BackHandler(enabled = true) {
                    if (!viewModel.onBackButtonClick())
                        finish()
                }

                NewVersionDialogShower(
                    lastLaunchedVersionCode = viewModel.lastLaunchedVersionCode,
                    onDialogDismissed = viewModel::onNewVersionDialogDismiss)

                Column {
                    SoundAuraAppBar()
                    val mainContentPadding = rememberWindowInsetsPaddingValues(
                        insets = WindowInsets.navigationBars,
                        additionalTop = 8.dp,
                        additionalStart = 8.dp,
                        additionalBottom = MediaControllerSizes.defaultMinThicknessDp.dp + 16.dp,
                        additionalEnd = 8.dp)
                    MainContent(mainContentPadding)
                }

                val floatingButtonPadding = rememberWindowInsetsPaddingValues(
                    insets = WindowInsets.systemBars,
                    additionalStart = 8.dp,
                    additionalEnd = 8.dp,
                    additionalBottom = 8.dp,
                    additionalTop = 8.dp + 56.dp)

                SoundAuraMediaController(padding = floatingButtonPadding)

                AddTrackButton(Modifier
                    .align(Alignment.BottomEnd)
                    .padding(floatingButtonPadding))

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(floatingButtonPadding))
            }
        }
    }

    /** Read the app's theme from a SettingsViewModel instance
     * and compose the provided content using the theme. */
    private fun setContentWithTheme(
        parent: CompositionContext? = null,
        content: @Composable () -> Unit
    ) = setContent(parent) {
        val themePreference by viewModel.appTheme.collectAsState()
        val systemInDarkTheme = isSystemInDarkTheme()
        val useDarkTheme by remember(themePreference, systemInDarkTheme) {
            derivedStateOf {
                themePreference == AppTheme.Dark ||
                (themePreference == AppTheme.UseSystem && systemInDarkTheme)
            }
        }

        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !useDarkTheme
        SoundAuraTheme(useDarkTheme) {
            val windowSizeClass = calculateWindowSizeClass(this)
            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                content()
            }
        }
    }

    @Composable private fun MainContent(padding: PaddingValues) {
        // The track list state is remembered here so that the
        // scrolling position will not be lost if the user
        // navigates to the app settings screen and back.
        val trackListState = rememberLazyListState()

        SlideAnimatedContent(
            targetState = viewModel.showingAppSettings,
            leftToRight = !viewModel.showingAppSettings,
            modifier = Modifier.fillMaxSize()
        ) { showingAppSettingsScreen ->
            if (showingAppSettingsScreen)
                AppSettings(padding)
            else SoundAuraLibraryView(
                padding = padding,
                state = trackListState)
        }
    }

    @Composable private fun AddTrackButton(modifier: Modifier = Modifier) {
        val showingPresetSelector = viewModel.showingPresetSelector
        // Different stiffnesses are used for the x and y offsets so that the
        // add button moves in a swooping movement instead of a linear one
        val addButtonXDpOffset by animateDpAsState(
            targetValue = if (showingPresetSelector) (-16).dp
                          else                       0.dp,
            label = "Add button x offset animation",
            animationSpec = tween(tweenDuration * 5 / 4, 0, LinearOutSlowInEasing))

        val addButtonYDpOffset by animateDpAsState(
            targetValue = if (showingPresetSelector) (-16).dp else 0.dp,
            label = "Add button y offset animation",
            animationSpec = tween(tweenDuration, 0, LinearOutSlowInEasing))

        AddButton(
            backgroundColor = MaterialTheme.colors.primaryVariant,
            visible = !viewModel.showingAppSettings,
            modifier = modifier.graphicsLayer {
                translationX = addButtonXDpOffset.toPx()
                translationY = addButtonYDpOffset.toPx()
            })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?) =
        if (viewModel.onKeyDown(keyCode)) true
        else super.onKeyDown(keyCode, event)
}