/* This file is part of SoundAura, which is released under
 * the terms of the Apache License 2.0. See license.md in
 * the project's root directory to see the full license. */
package com.cliffracertech.soundaura.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cliffracertech.soundaura.R
import com.cliffracertech.soundaura.dialog.DialogWidth
import com.cliffracertech.soundaura.ui.HorizontalDivider
import com.cliffracertech.soundaura.dialog.SoundAuraDialog

@Composable fun AppSettings(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) = Surface(modifier, color = MaterialTheme.colors.background) {
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { DisplaySettingsCategory() }
        item { PlaybackSettingsCategory() }
        item { BackupSettingsCategory() }
        item { AboutSettingsCategory() }
    }

    val viewModel: SettingsViewModel = viewModel()
    val context = LocalContext.current
    viewModel.message?.let {
        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        viewModel.onMessageDismiss()
    }
}

@Composable private fun DisplaySettingsCategory() =
    SettingCategory(stringResource(R.string.display)) { paddingModifier ->
        val viewModel: SettingsViewModel = viewModel()

        EnumDialogSetting(
            title = stringResource(R.string.app_theme),
            modifier = paddingModifier,
            values = AppTheme.values(),
            valueNames = AppTheme.valueStrings(),
            currentValue = viewModel.appTheme,
            onValueClick = viewModel::onAppThemeClick)
    }

@Composable private fun PlayInBackgroundSetting(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onTileTutorialShowRequest: () -> Unit,
) = Setting(
    title = stringResource(R.string.play_in_background_setting_title),
    modifier = modifier,
    subtitle = stringResource(R.string.play_in_background_setting_description),
    onClick = viewModel::onPlayInBackgroundTitleClick
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Vertical Divider
        Box(Modifier.width((1.5).dp).height(40.dp)
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f)))

        Spacer(Modifier.width(6.dp))
        Switch(checked = viewModel.playInBackground,
            onCheckedChange = remember {{ viewModel.onPlayInBackgroundSwitchClick() }})
    }
    if (viewModel.showingPlayInBackgroundExplanation)
        PlayInBackgroundExplanationDialog(
            onDismissRequest = viewModel::onPlayInBackgroundExplanationDismiss)
    if (viewModel.showingNotificationPermissionDialog) {
        val context = LocalContext.current
        val showExplanation =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
                false
            else ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_DENIED
        NotificationPermissionDialog(
            showExplanationFirst = showExplanation,
            onShowTileTutorialClick = onTileTutorialShowRequest,
            onDismissRequest = viewModel::onNotificationPermissionDialogDismiss,
            onPermissionResult = viewModel::onNotificationPermissionDialogConfirm)
    }
}


@Composable private fun PlaybackSettingsCategory() =
    SettingCategory(stringResource(R.string.playback)) { paddingModifier ->
        val viewModel: SettingsViewModel = viewModel()
        var showingTileTutorialDialog by rememberSaveable { mutableStateOf(false) }

        PlayInBackgroundSetting(
            viewModel = viewModel,
            modifier = paddingModifier,
            onTileTutorialShowRequest = { showingTileTutorialDialog = true })

        HorizontalDivider(paddingModifier)
        EnumDialogSetting(
            title = stringResource(R.string.on_zero_volume_behavior_setting_title),
            modifier = paddingModifier,
            dialogWidth = DialogWidth.MatchToScreenSize(),
            description = stringResource(R.string.on_zero_volume_behavior_setting_description),
            values = enumValues(),
            valueNames = OnZeroVolumeAudioDeviceBehavior.valueStrings(),
            valueDescriptions = OnZeroVolumeAudioDeviceBehavior.valueDescriptions(),
            currentValue = viewModel.onZeroVolumeAudioDeviceBehavior,
            onValueClick = viewModel::onOnZeroVolumeAudioDeviceBehaviorClick)
        HorizontalDivider(paddingModifier)
        DialogSetting(
            title = stringResource(R.string.control_playback_using_tile_setting_title),
            modifier = paddingModifier,
            dialogVisible = showingTileTutorialDialog,
            onShowRequest = { showingTileTutorialDialog = true },
            onDismissRequest = { showingTileTutorialDialog = false },
            content = { TileTutorialDialog(onDismissRequest = it) })
        HorizontalDivider(paddingModifier)
        Setting(
            title = stringResource(R.string.stop_instead_of_pause_setting_title),
            modifier = paddingModifier,
            subtitle = stringResource(R.string.stop_instead_of_pause_setting_description),
            onClick = viewModel::onStopInsteadOfPauseClick
        ) {
            Switch(checked = viewModel.stopInsteadOfPause,
                onCheckedChange = { viewModel.onStopInsteadOfPauseClick() })
        }
    }

@Composable private fun AboutSettingsCategory() =
    SettingCategory(stringResource(R.string.about)) { paddingModifier ->
        DialogSetting(stringResource(R.string.privacy_policy_setting_title), paddingModifier) {
            PrivacyPolicyDialog(onDismissRequest = it)
        }
        HorizontalDivider(paddingModifier)
        DialogSetting(stringResource(R.string.open_source_licenses), paddingModifier) {
            OpenSourceLibrariesUsedDialog(onDismissRequest = it)
        }
        HorizontalDivider(paddingModifier)
        DialogSetting(stringResource(R.string.about_app_setting_title), paddingModifier) {
            AboutAppDialog(onDismissRequest = it)
        }
    }

@Composable private fun BackupSettingsCategory() =
    SettingCategory(stringResource(R.string.backup_restore)) { paddingModifier ->
        val viewModel: SettingsViewModel = viewModel()

        val backupLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) viewModel.onBackupRequest(uri)
        }

        val restoreLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) viewModel.onRestoreRequest(uri)
        }

        val relinkLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) viewModel.onRelinkRequest(uri)
        }

        Setting(
            title = stringResource(R.string.backup_data),
            modifier = paddingModifier,
            subtitle = stringResource(R.string.backup_data_description),
            onClick = { backupLauncher.launch("SoundAuraBackup.json") }
        ) {}
        HorizontalDivider(paddingModifier)
        Setting(
            title = stringResource(R.string.restore_data),
            modifier = paddingModifier,
            subtitle = stringResource(R.string.restore_data_description),
            onClick = { restoreLauncher.launch(arrayOf("application/json", "application/octet-stream")) }
        ) {}
        HorizontalDivider(paddingModifier)
        Setting(
            title = stringResource(R.string.relink_sounds),
            modifier = paddingModifier,
            subtitle = stringResource(R.string.relink_sounds_description),
            onClick = { relinkLauncher.launch(null) }
        ) {}

        if (viewModel.showingRestoreConfirmation) {
            SoundAuraDialog(
                title = stringResource(R.string.restore_data_confirm_title),
                text = stringResource(R.string.restore_data_confirm_message),
                confirmText = stringResource(R.string.ok),
                onConfirm = viewModel::onRestoreConfirm,
                onDismissRequest = viewModel::onRestoreCancel
            )
        }
    }