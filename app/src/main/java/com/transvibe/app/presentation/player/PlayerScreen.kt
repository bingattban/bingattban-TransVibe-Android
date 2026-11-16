package com.transvibe.app.presentation.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.transvibe.app.R
import com.transvibe.app.data.subtitle.SubtitleTimeline
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.PlayerExportResult
import com.transvibe.app.presentation.components.EmptyStateIllustration
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.ScreenHeroHeader
import com.transvibe.app.presentation.components.ScrollablePillTabs
import com.transvibe.app.presentation.components.SecondaryActionButton
import com.transvibe.app.presentation.components.SegmentedPillSelector
import com.transvibe.app.presentation.processing.ProcessingUiErrorMapper
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(vm: PlayerViewModel, onAnother: () -> Unit) {
    val state by vm.processingState.collectAsStateWithLifecycle()
    val session = (state as? ProcessingState.Completed)?.result?.session
    if (session == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            EmptyStateIllustration(
                icon = Icons.Rounded.PlayCircle,
                title = stringResource(R.string.player_title),
                subtitle = stringResource(R.string.player_no_result)
            )
        }
        return
    }
    PlayerAndExport(vm, session, onAnother)
}

@Composable
fun PlayerAndExport(vm: PlayerViewModel, session: ProcessingSession, onAnother: () -> Unit) {
    val context = LocalContext.current
    val playerHeight = 235.dp
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val sourceAccess by vm.sourceAccess.collectAsStateWithLifecycle()
    val exportResult by vm.exportResult.collectAsStateWithLifecycle()
    val segments by vm.subtitleWindow.collectAsStateWithLifecycle()
    val sourceAccessible = sourceAccess?.takeIf { it.sessionId == session.id }?.accessible
    var activeTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(session.id) {
        vm.checkSourceAccess(session)
        vm.ensureSubtitleWindow(session.id, 0L, force = true)
    }

    val player = remember(session.id, sourceAccessible) {
        if (sourceAccessible == true) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(session.videoUri))
                prepare()
            }
        } else null
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.stop()
            player?.release()
        }
    }
    val timeline = remember(segments) { SubtitleTimeline(segments) }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, session.id) {
        while (player != null) {
            pos = player.currentPosition
            vm.ensureSubtitleWindow(session.id, pos)
            delay(150)
        }
    }
    val active = remember(pos, timeline) { timeline.at(pos) }
    val activeText = active?.let { displayText(it, prefs.subtitleDisplayMode) }.orEmpty()

    var editing by remember { mutableStateOf<SubtitleSegment?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ScreenHeroHeader(
            title = session.videoName,
            subtitle = "${session.modelId} • ${session.detectedLanguage.uppercase()}",
            icon = Icons.Rounded.PlayCircle
        )

        // Pinned Video Player Dock at the Top
        when (sourceAccessible) {
            null -> LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(100.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                color = MaterialTheme.colorScheme.primary
            )
            false -> GlassCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.player_source_unavailable),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            true -> player?.let { p ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(playerHeight)
                            .clip(RoundedCornerShape(22.dp))
                    ) {
                        AndroidView(
                            factory = {
                                PlayerView(it).apply {
                                    this.player = p
                                    keepScreenOn = prefs.keepScreenOnDuringPlayback
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        AnimatedContent(
                            targetState = activeText,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "subtitle-fade"
                        ) { subtitleText ->
                            if (subtitleText.isNotBlank()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Spacer(
                                        modifier = Modifier.weight(
                                            prefs.subtitleVerticalPosition.coerceIn(.10f, .95f)
                                        )
                                    )
                                    SubtitleOverlay(
                                        text = subtitleText,
                                        fontSizeSp = 18f * prefs.subtitleTextScale / prefs.interfaceTextScale,
                                        alignment = subtitleAlignment(prefs.subtitleAlignment),
                                        backgroundStyle = prefs.subtitleBackgroundStyle,
                                        backgroundOpacity = prefs.subtitleBackgroundOpacity,
                                        textColor = subtitleTextColor(prefs.subtitleTextColor)
                                    )
                                    Spacer(
                                        modifier = Modifier.weight(
                                            (1f - prefs.subtitleVerticalPosition).coerceAtLeast(.05f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Studio Workspace Tabs below the pinned video
        ScrollablePillTabs(
            tabs = listOf(
                "0" to stringResource(R.string.all_subtitles),
                "1" to stringResource(R.string.subtitle_display_settings),
                "2" to stringResource(R.string.export_subtitles)
            ),
            selected = activeTab.toString(),
            onSelected = { activeTab = it.toIntOrNull() ?: 0 }
        )

        Box(modifier = Modifier.weight(1f)) {
            when (activeTab) {
                0 -> SubtitleTimelineTab(
                    segments = segments,
                    active = active,
                    displayMode = prefs.subtitleDisplayMode,
                    onEdit = { editing = it }
                )
                1 -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        SubtitleDisplayControls(
                            displayMode = prefs.subtitleDisplayMode,
                            textScale = prefs.subtitleTextScale,
                            opacity = prefs.subtitleBackgroundOpacity,
                            backgroundStyle = prefs.subtitleBackgroundStyle,
                            textColor = prefs.subtitleTextColor,
                            vertical = prefs.subtitleVerticalPosition,
                            alignment = prefs.subtitleAlignment,
                            onMode = vm::setSubtitleDisplayMode,
                            onScale = vm::setSubtitleTextScale,
                            onOpacity = vm::setSubtitleBackgroundOpacity,
                            onBackgroundStyle = vm::setSubtitleBackgroundStyle,
                            onTextColor = vm::setSubtitleTextColor,
                            onVertical = vm::setSubtitleVerticalPosition,
                            onAlignment = vm::setSubtitleAlignment
                        )
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        GlassCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.export_subtitles),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                SegmentedPillSelector(
                                    options = listOf(
                                        "srt" to stringResource(R.string.format_srt),
                                        "vtt" to stringResource(R.string.format_webvtt)
                                    ),
                                    selected = prefs.preferredExportFormat,
                                    onSelected = vm::setPreferredExportFormat
                                )
                                Button(
                                    onClick = { vm.exportToSettingsFolder(session, prefs.preferredExportFormat == "vtt") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 50.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = stringResource(R.string.export_subtitles),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                                Text(
                                    stringResource(R.string.export_direct_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    exportResult?.let { result ->
                        item {
                            GlassCard(highlighted = result is PlayerExportResult.Success) {
                                Text(
                                    text = when (result) {
                                        PlayerExportResult.Success -> stringResource(R.string.export_success)
                                        is PlayerExportResult.Failure -> {
                                            val uiError = ProcessingUiErrorMapper.map(result.error)
                                            stringResource(uiError.explanation)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when (result) {
                                        PlayerExportResult.Success -> MaterialTheme.colorScheme.primary
                                        is PlayerExportResult.Failure -> MaterialTheme.colorScheme.error
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                )
                            }
                        }
                    }
                    item {
                        SecondaryActionButton(
                            text = stringResource(R.string.process_another_video),
                            onClick = onAnother,
                            icon = Icons.Rounded.VideoLibrary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    editing?.let { segment ->
        EditSubtitleDialog(
            segment = segment,
            onDismiss = { editing = null },
            onSave = { original, translated ->
                vm.updateSegment(session.id, segment, original, translated)
                editing = null
            }
        )
    }
}

@Composable
private fun SubtitleTimelineTab(
    segments: List<SubtitleSegment>,
    active: SubtitleSegment?,
    displayMode: String,
    onEdit: (SubtitleSegment) -> Unit
) {
    val listState = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(active?.id, segments) {
        val index = segments.indexOfFirst { it.id == active?.id }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (active != null) {
            GlassCard(highlighted = true) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "${stringResource(R.string.current_subtitle)} • ${formatTimestamp(active.startMs)} - ${formatTimestamp(active.endMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = scheme.primary
                        )
                        Text(
                            text = displayText(active, displayMode),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface
                        )
                    }
                    OutlinedButton(
                        onClick = { onEdit(active) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(
                            text = stringResource(R.string.edit_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }

        GlassCard(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(segments, key = { it.id }) { segment ->
                    val isActive = segment.id == active?.id
                    Surface(
                        onClick = { onEdit(segment) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isActive) scheme.primaryContainer else scheme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (isActive) scheme.primary else scheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${formatTimestamp(segment.startMs)} - ${formatTimestamp(segment.endMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) scheme.primary else scheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = stringResource(R.string.edit_subtitle),
                                    tint = if (isActive) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = displayText(segment, displayMode),
                                color = if (isActive) scheme.onPrimaryContainer else scheme.onSurface,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

@Composable
private fun SubtitleDisplayControls(
    displayMode: String,
    textScale: Float,
    opacity: Float,
    backgroundStyle: String,
    textColor: String,
    vertical: Float,
    alignment: String,
    onMode: (String) -> Unit,
    onScale: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onBackgroundStyle: (String) -> Unit,
    onTextColor: (String) -> Unit,
    onVertical: (Float) -> Unit,
    onAlignment: (String) -> Unit
) {
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.subtitle_display_settings),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.subtitle_display_mode),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            SegmentedPillSelector(
                options = listOf(
                    "original" to stringResource(R.string.subtitle_mode_original),
                    "translated" to stringResource(R.string.subtitle_mode_translated),
                    "both" to stringResource(R.string.subtitle_mode_both)
                ),
                selected = displayMode,
                onSelected = onMode
            )

            LabeledSlider(R.string.subtitle_text_size, textScale, .85f..1.80f, onScale)

            Text(
                text = stringResource(R.string.subtitle_background_style),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            ScrollablePillTabs(
                tabs = listOf(
                    "compact" to stringResource(R.string.subtitle_background_compact),
                    "band" to stringResource(R.string.subtitle_background_band),
                    "shadow" to stringResource(R.string.subtitle_background_shadow),
                    "none" to stringResource(R.string.subtitle_background_none)
                ),
                selected = backgroundStyle,
                onSelected = onBackgroundStyle
            )

            if (backgroundStyle == "compact" || backgroundStyle == "band") {
                LabeledSlider(R.string.subtitle_background_opacity, opacity, 0f..1f, onOpacity)
            }

            Text(
                text = stringResource(R.string.subtitle_text_color),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            SegmentedPillSelector(
                options = listOf(
                    "white" to stringResource(R.string.subtitle_color_white),
                    "yellow" to stringResource(R.string.subtitle_color_yellow),
                    "cyan" to stringResource(R.string.subtitle_color_cyan),
                    "green" to stringResource(R.string.subtitle_color_green)
                ),
                selected = textColor,
                onSelected = onTextColor
            )

            LabeledSlider(R.string.subtitle_vertical_position, vertical, .10f..0.95f, onVertical)

            Text(
                text = stringResource(R.string.subtitle_alignment),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            SegmentedPillSelector(
                options = listOf(
                    "start" to stringResource(R.string.align_start),
                    "center" to stringResource(R.string.align_center),
                    "end" to stringResource(R.string.align_end)
                ),
                selected = alignment,
                onSelected = onAlignment
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    labelId: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(labelId),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 44.dp)
        )
    }
}

@Composable
private fun EditSubtitleDialog(
    segment: SubtitleSegment,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    var original by remember(segment.id) { mutableStateOf(segment.originalText) }
    var translated by remember(segment.id) { mutableStateOf(segment.translatedText.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.edit_subtitle),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = original,
                    onValueChange = { original = it },
                    label = { Text(stringResource(R.string.subtitle_original_text)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    minLines = 2,
                    maxLines = 4
                )
                OutlinedTextField(
                    value = translated,
                    onValueChange = { translated = it },
                    label = { Text(stringResource(R.string.subtitle_translated_text)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(original, translated.ifBlank { null })
                },
                enabled = original.isNotBlank()
            ) {
                Text(
                    text = stringResource(R.string.save),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

private fun displayText(segment: SubtitleSegment, mode: String): String {
    val original = segment.originalText.trim()
    val translated = (segment.translatedText ?: segment.englishText ?: segment.originalText).trim()
    return when (mode) {
        "original" -> original
        "both" -> if (translated.isBlank() || translated == original) original else "$original\n$translated"
        else -> translated
    }
}

@Composable
private fun SubtitleOverlay(
    text: String,
    fontSizeSp: Float,
    alignment: TextAlign,
    backgroundStyle: String,
    backgroundOpacity: Float,
    textColor: Color
) {
    val shadowOnly = backgroundStyle == "shadow"
    val lines = text.split('\n', limit = 2)
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (lines.size > 1) {
                SubtitleDirectedText(
                    text = lines.first(),
                    modifier = Modifier.fillMaxWidth(),
                    fontSizeSp = fontSizeSp * 0.78f,
                    alignment = alignment,
                    color = Color.White.copy(alpha = 0.74f),
                    shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(1f, 1f), 2f)
                )
            }
            SubtitleDirectedText(
                text = lines.last(),
                modifier = Modifier.fillMaxWidth(),
                fontSizeSp = fontSizeSp,
                alignment = alignment,
                color = textColor,
                shadow = if (shadowOnly) Shadow(Color.Black, Offset(2f, 2f), 5f)
                else Shadow(Color.Black.copy(alpha = 0.65f), Offset(1f, 1f), 2f)
            )
        }
    }
    when (backgroundStyle) {
        "band" -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0F172A).copy(
                alpha = (backgroundOpacity.coerceIn(0f, 1f) * 0.88f).coerceAtLeast(0.30f)
            )
        ) { content() }
        "compact" -> Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0F172A).copy(
                alpha = (backgroundOpacity.coerceIn(0f, 1f) * 0.88f).coerceAtLeast(0.30f)
            )
        ) { content() }
        else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun SubtitleDirectedText(
    text: String,
    modifier: Modifier,
    fontSizeSp: Float,
    alignment: TextAlign,
    color: Color,
    shadow: Shadow
) {
    CompositionLocalProvider(LocalLayoutDirection provides contentDirection(text)) {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            textAlign = alignment,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.25f).sp,
            style = TextStyle(shadow = shadow)
        )
    }
}

private fun subtitleTextColor(value: String): Color = when (value) {
    "yellow" -> Color(0xFFFFEB3B)
    "cyan" -> Color(0xFF80DEEA)
    "green" -> Color(0xFFA5D6A7)
    else -> Color.White
}

private fun subtitleAlignment(value: String): TextAlign = when (value) {
    "start" -> TextAlign.Start
    "end" -> TextAlign.End
    else -> TextAlign.Center
}

private fun contentDirection(text: String): LayoutDirection {
    text.forEach { ch ->
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return LayoutDirection.Ltr
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return LayoutDirection.Rtl
        }
    }
    return LayoutDirection.Ltr
}