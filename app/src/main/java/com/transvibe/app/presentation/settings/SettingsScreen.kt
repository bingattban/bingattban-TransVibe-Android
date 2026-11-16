package com.transvibe.app.presentation.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.presentation.components.DropdownChoice
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.ScreenHeroHeader
import com.transvibe.app.presentation.components.SegmentedPillSelector
import java.util.Locale

@Composable
fun SettingsScreen(vm: SettingsViewModel, onModels: () -> Unit, onAbout: () -> Unit) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val storage by vm.storageInfo.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val models by vm.modelRegistry.collectAsStateWithLifecycle()
    val modelResources by vm.modelResources.collectAsStateWithLifecycle()
    val fastModels by vm.availableFastAsrModels.collectAsStateWithLifecycle()
    var subtitleScale by remember(prefs.subtitleTextScale) { mutableFloatStateOf(prefs.subtitleTextScale) }
    val context = LocalContext.current
    var folderPermissionError by remember { mutableStateOf(false) }
    val outputFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val permissionGranted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
        }.getOrDefault(false)
        folderPermissionError = !permissionGranted
        if (permissionGranted) vm.setSubtitleOutputTreeUri(uri.toString())
    }

    LaunchedEffect(Unit) { vm.refreshStorageInfo() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeroHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
                icon = Icons.Rounded.Settings
            )
        }

        // ═══════════════════════════════════════════
        // Language & Appearance Section
        // ═══════════════════════════════════════════
        item { SettingsSectionLabel(stringResource(R.string.settings_section_language_appearance)) }

        item {
            SettingsCard(title = stringResource(R.string.application_language), icon = Icons.Rounded.Language) {
                SegmentedChoices(
                    values = listOf("ar", "en"),
                    labels = listOf(
                        stringResource(R.string.language_arabic),
                        stringResource(R.string.language_english)
                    ),
                    selected = prefs.applicationLanguage,
                    onSelected = vm::setApplicationLanguage
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.appearance), icon = Icons.Rounded.Palette) {
                SegmentedChoices(
                    values = listOf("system", "light", "dark"),
                    labels = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark)
                    ),
                    selected = prefs.themeMode,
                    onSelected = vm::setThemeMode
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.interface_text_size), icon = Icons.Rounded.Subtitles) {
                var textScale by remember(prefs.interfaceTextScale) { mutableFloatStateOf(prefs.interfaceTextScale) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.interface_text_size_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(textScale * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Slider(
                    value = textScale,
                    onValueChange = { textScale = it },
                    onValueChangeFinished = { vm.setInterfaceTextScale(textScale) },
                    valueRange = 0.85f..1.40f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ═══════════════════════════════════════════
        // Processing Section
        // ═══════════════════════════════════════════
        item { SettingsSectionLabel(stringResource(R.string.settings_section_processing)) }

        item {
            SettingsCard(title = stringResource(R.string.processing_mode), icon = Icons.Rounded.Bolt) {
                SegmentedChoices(
                    values = listOf("FAST", "BALANCED", "PRESERVE_ORIGINAL"),
                    labels = listOf(
                        stringResource(R.string.mode_fast),
                        stringResource(R.string.mode_balanced),
                        stringResource(R.string.mode_preserve_original)
                    ),
                    selected = prefs.processingProfile,
                    onSelected = vm::setProcessingProfile
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            when (prefs.processingProfile) {
                                "FAST" -> R.string.mode_cost_fast
                                "PRESERVE_ORIGINAL" -> R.string.mode_cost_preserve_original
                                else -> R.string.mode_cost_balanced
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.default_model), icon = Icons.Rounded.Psychology) {
                val readyWhisper = models.filter {
                    modelResources.modelState(it.id) == com.transvibe.app.domain.model.ModelIntegrityState.READY
                }
                val choices = buildList {
                    readyWhisper.forEach { add(it.id to it.displayName) }
                    fastModels.forEach { add(it.id to it.displayName) }
                }
                val effectiveDefaultId = prefs.defaultProcessingModelId
                    .takeIf { id -> choices.any { it.first == id } }
                    ?: selectedModelId.takeIf { id -> choices.any { it.first == id } }
                    ?: choices.firstOrNull()?.first

                Text(
                    text = stringResource(R.string.default_model_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (choices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.default_model_none_available),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    DropdownChoice(
                        options = choices,
                        selected = effectiveDefaultId ?: choices.first().first,
                        onSelected = vm::setDefaultProcessingModel
                    )
                    Text(
                        text = stringResource(R.string.default_model_home_link_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilledTonalButton(
                    onClick = onModels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.manage_models),
                        modifier = Modifier.padding(start = 8.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ═══════════════════════════════════════════
        // Downloads Section
        // ═══════════════════════════════════════════
        item { SettingsSectionLabel(stringResource(R.string.settings_section_downloads)) }

        item {
            SettingsCard(title = stringResource(R.string.translation_downloads), icon = Icons.Rounded.Folder) {
                val checked = prefs.wifiOnlyTranslationDownloads
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
                        .clickable(role = Role.Switch) {
                            vm.setWifiOnlyTranslationDownloads(!checked)
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.wifi_only_downloads),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.wifi_only_downloads_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = vm::setWifiOnlyTranslationDownloads,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                }
            }
        }

        // ═══════════════════════════════════════════
        // Player Section
        // ═══════════════════════════════════════════
        item { SettingsSectionLabel(stringResource(R.string.settings_section_player)) }

        item {
            SettingsCard(title = stringResource(R.string.subtitle_appearance), icon = Icons.Rounded.Subtitles) {
                SubtitlePreview(scale = subtitleScale, interfaceScale = prefs.interfaceTextScale)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.text_size_label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${(subtitleScale * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Slider(
                    value = subtitleScale,
                    onValueChange = { subtitleScale = it },
                    onValueChangeFinished = { vm.setSubtitleTextScale(subtitleScale) },
                    valueRange = 0.85f..1.80f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
                        .clickable(role = Role.Switch) {
                            vm.setKeepScreenOnDuringPlayback(!prefs.keepScreenOnDuringPlayback)
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.keep_screen_on),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = prefs.keepScreenOnDuringPlayback,
                        onCheckedChange = vm::setKeepScreenOnDuringPlayback,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.preferred_export_format), icon = Icons.Rounded.Save) {
                SegmentedChoices(
                    values = listOf("srt", "vtt"),
                    labels = listOf(
                        stringResource(R.string.format_srt),
                        stringResource(R.string.format_webvtt)
                    ),
                    selected = prefs.preferredExportFormat,
                    onSelected = vm::setPreferredExportFormat
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.subtitle_output_folder), icon = Icons.Rounded.Folder) {
                if (folderPermissionError) {
                    Text(
                        stringResource(R.string.output_folder_permission_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (prefs.subtitleOutputTreeUri.isBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary,
                                CircleShape
                            )
                    )
                    Text(
                        text = if (prefs.subtitleOutputTreeUri.isBlank()) stringResource(R.string.subtitle_output_default)
                        else stringResource(R.string.subtitle_output_custom),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilledTonalButton(
                    onClick = { outputFolderPicker.launch(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.choose_folder),
                        modifier = Modifier.padding(start = 8.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (prefs.subtitleOutputTreeUri.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { vm.setSubtitleOutputTreeUri(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.use_default_output_folder),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // ═══════════════════════════════════════════
        // Storage & About Section
        // ═══════════════════════════════════════════
        item { SettingsSectionLabel(stringResource(R.string.settings_section_storage_about)) }

        item {
            SettingsCard(title = stringResource(R.string.storage), icon = Icons.Rounded.Storage) {
                val accounted = storage.modelBytes + storage.sessionBytes
                val capacity = (accounted + storage.freeBytes).coerceAtLeast(1L)
                val usedFraction = (accounted.toFloat() / capacity.toFloat()).coerceIn(0f, 1f)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = usedFraction)
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                )
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.storage_usage_summary,
                            formatBytes(accounted),
                            formatBytes(storage.freeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StorageRow(
                        stringResource(R.string.storage_ai_models_label),
                        formatBytes(storage.modelBytes),
                        Icons.Rounded.Psychology
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    StorageRow(
                        stringResource(R.string.storage_sessions_label),
                        formatBytes(storage.sessionBytes),
                        Icons.Rounded.ClosedCaption
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    StorageRow(
                        stringResource(R.string.storage_available_label),
                        formatBytes(storage.freeBytes),
                        Icons.Rounded.Storage
                    )
                }
            }
        }

        item {
            SettingsCard(
                title = stringResource(R.string.about),
                icon = Icons.Rounded.Info,
                onClick = onAbout
            )
        }
    }
}

@Composable
private fun SettingsSectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = scheme.primary
                    )
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SubtitlePreview(scale: Float, interfaceScale: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF090D16),
                        Color(0xFF1E293B)
                    )
                )
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = stringResource(R.string.subtitle_preview_sample),
            color = Color.White,
            fontSize = (18f * scale / interfaceScale).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = (25f * scale).sp,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.7f),
                    offset = Offset(1f, 1f),
                    blurRadius = 3f
                )
            )
        )
    }
}

@Composable
private fun StorageRow(label: String, value: String, icon: ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(scheme.secondaryContainer.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = scheme.secondary
            )
        }
        Text(
            text = label,
            color = scheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SegmentedChoices(
    values: List<String>,
    labels: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    SegmentedPillSelector(values.zip(labels), selected, onSelected)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}