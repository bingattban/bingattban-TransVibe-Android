package com.transvibe.app.presentation.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.model.ModelPerformanceTier
import com.transvibe.app.domain.model.ProcessingResourceReadiness
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.presentation.components.DropdownChoice
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.MetadataBadge
import com.transvibe.app.presentation.components.PrimaryActionButton
import com.transvibe.app.presentation.components.ScreenHeroHeader
import com.transvibe.app.presentation.components.SecondaryActionButton
import com.transvibe.app.presentation.components.SegmentedPillSelector
import com.transvibe.app.presentation.components.StatusPill
import com.transvibe.app.presentation.components.human
import com.transvibe.app.presentation.components.isActiveProcessing
import com.transvibe.app.presentation.components.overallProgressPercent
import com.transvibe.app.presentation.processing.ProcessingUiErrorMapper
import com.transvibe.app.presentation.theme.LocalStatusColors

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    process: ProcessingState,
    onCancelProcessing: () -> Unit,
    onModels: () -> Unit,
    onProcessing: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val video by vm.selectedVideo.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val models by vm.modelRegistry.collectAsStateWithLifecycle()
    val resourceSnapshot by vm.resources.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val usingDefaultModel by vm.usingDefaultModel.collectAsStateWithLifecycle()
    val fastModels by vm.availableFastAsrModels.collectAsStateWithLifecycle()
    val readiness by vm.pipelineReadiness.collectAsStateWithLifecycle()
    val preflightBusy by vm.preflightBusy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::selectVideo) }
    val startProcessing = remember(vm, onProcessing) { { vm.startProcessing(onProcessing) } }
    val busy = process.isActiveProcessing()

    // Green check only when the complete processing pipeline is ready AND an ASR model is installed.
    val installedModelAvailable = models.any {
        resourceSnapshot.modelState(it.id) == com.transvibe.app.domain.model.ModelIntegrityState.READY
    } || fastModels.isNotEmpty()
    val resourcesReady = readiness is ProcessingResourceReadiness.Ready && installedModelAvailable
    val canStart = video != null && resourcesReady && !busy && !preflightBusy

    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.processingReady.collect { startProcessing() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = if (busy || video != null) 92.dp else 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ScreenHeroHeader(
                    title = stringResource(R.string.home_title),
                    subtitle = stringResource(R.string.home_workflow_subtitle),
                    icon = Icons.Rounded.Subtitles
                )
            }

            item {
                ReadinessHero(
                    resourcesReady = resourcesReady,
                    onManageModels = onModels
                )
            }

            item {
                VideoDropzone(
                    videoName = video?.displayName,
                    videoSize = video?.sizeBytes,
                    enabled = !busy,
                    onPick = { picker.launch(arrayOf("video/*")) },
                    onClear = vm::clearSelectedVideo
                )
            }

            // Unified Language Pipeline Card (Steps 2 & 3 combined cleanly)
            item {
                GlassCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        WorkflowStepHeader(
                            step = "1",
                            icon = Icons.Rounded.Translate,
                            title = "${stringResource(R.string.workflow_source_language)}  •  ${stringResource(R.string.workflow_target_language)}"
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.workflow_source_language),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            SourceLanguageChoices(
                                options = listOf(
                                    "auto_video" to stringResource(R.string.source_auto),
                                    "manual:ar" to stringResource(R.string.language_arabic),
                                    "manual:en" to stringResource(R.string.language_english),
                                    "manual:fr" to stringResource(R.string.language_french),
                                    "manual:ru" to stringResource(R.string.language_russian),
                                    "manual:de" to stringResource(R.string.language_german),
                                    "manual:es" to stringResource(R.string.language_spanish)
                                ),
                                selected = if (prefs.videoSpeechLanguage == "auto_adaptive") "auto_video" else prefs.videoSpeechLanguage,
                                onSelected = vm::setSourceLanguage
                            )
                            if (prefs.videoSpeechLanguage.startsWith("auto")) {
                                Text(
                                    text = stringResource(R.string.source_language_speed_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.workflow_target_language),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            SegmentedPillSelector(
                                options = listOf(
                                    "ar" to stringResource(R.string.language_arabic),
                                    "en" to stringResource(R.string.language_english)
                                ),
                                selected = prefs.subtitleTargetLanguage,
                                onSelected = vm::setTargetLanguage
                            )
                        }
                    }
                }
            }

            // Unified Engine & Quality Configuration Card (Steps 4 & 5 combined cleanly)
            item {
                GlassCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        WorkflowStepHeader(
                            step = "2",
                            icon = Icons.Rounded.AutoAwesome,
                            title = "${stringResource(R.string.workflow_quality)}  •  ${stringResource(R.string.ai_model_choice)}"
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.workflow_quality),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            SegmentedPillSelector(
                                options = listOf(
                                    "FAST" to stringResource(R.string.quality_fast),
                                    "BALANCED" to stringResource(R.string.quality_balanced),
                                    "PRESERVE_ORIGINAL" to stringResource(R.string.quality_best_text)
                                ),
                                selected = prefs.processingProfile,
                                onSelected = vm::setProcessingQuality
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.ai_model_choice),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            val options = buildList {
                                add("__default__" to stringResource(R.string.model_default_choice))
                                models.filter {
                                    resourceSnapshot.modelState(it.id) == com.transvibe.app.domain.model.ModelIntegrityState.READY
                                }.forEach { add(it.id to it.displayName) }
                                fastModels.forEach { add(it.id to it.displayName) }
                            }
                            ChoiceRow(
                                options = options,
                                selected = if (usingDefaultModel) "__default__"
                                else prefs.preferredAsrModelId.takeIf { it !in setOf("auto", "whisper") }
                                    ?: selectedModelId,
                                onSelected = {
                                    if (it == "__default__") vm.useDefaultModelSelection()
                                    else vm.selectProcessingModel(it)
                                }
                            )
                            val effectiveModelId = if (usingDefaultModel) {
                                prefs.defaultProcessingModelId
                            } else {
                                prefs.preferredAsrModelId.takeIf { it !in setOf("auto", "whisper") }
                                    ?: selectedModelId
                            }
                            val selectedWhisper = models.firstOrNull { it.id == effectiveModelId }
                            if (selectedWhisper?.performanceTier == ModelPerformanceTier.HEAVY ||
                                selectedWhisper?.performanceTier == ModelPerformanceTier.VERY_HEAVY
                            ) {
                                Text(
                                    text = stringResource(R.string.heavy_model_runtime_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp)
                                )
                            }
                            if (usingDefaultModel) {
                                val defaultName = buildList {
                                    addAll(models.map { it.id to it.displayName })
                                    addAll(fastModels.map { it.id to it.displayName })
                                }.firstOrNull { it.first == prefs.defaultProcessingModelId }?.second
                                Text(
                                    text = if (defaultName != null) stringResource(R.string.model_default_hint_named, defaultName)
                                    else stringResource(R.string.model_default_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp, start = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (busy) {
            ActiveProcessingBanner(
                state = process,
                onClick = onProcessing,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        } else if (video != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                PrimaryActionButton(
                    text = stringResource(
                        if (preflightBusy) R.string.checking_requirements
                        else R.string.start_processing
                    ),
                    onClick = vm::requestProcessing,
                    enabled = canStart,
                    icon = Icons.Rounded.PlayArrow,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    HomeErrorDialog(
        error = error,
        onDismiss = vm::dismissError,
        onRetry = vm::requestProcessing,
        onModels = onModels
    )
}

@Composable
private fun ReadinessHero(
    resourcesReady: Boolean,
    onManageModels: () -> Unit
) {
    val statusColors = LocalStatusColors.current
    val scheme = MaterialTheme.colorScheme
    val indicatorColor by animateColorAsState(
        targetValue = if (resourcesReady) statusColors.ready else statusColors.warning,
        animationSpec = tween(350),
        label = "readinessIndicator"
    )
    GlassCard(highlighted = !resourcesReady) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !resourcesReady, onClick = onManageModels)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(indicatorColor.copy(alpha = 0.16f))
                    .border(1.dp, indicatorColor.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (resourcesReady) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = stringResource(
                        if (resourcesReady) R.string.ready_for_processing
                        else R.string.processing_resources_need_attention
                    ),
                    tint = indicatorColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(
                        if (resourcesReady) R.string.ready_for_processing
                        else R.string.processing_resources_need_attention
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                Text(
                    text = stringResource(
                        if (resourcesReady) R.string.processing_resources_ready_explanation
                        else R.string.processing_resources_need_attention_explanation
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!resourcesReady) {
                StatusPill(
                    label = stringResource(R.string.prepare_required_resources),
                    color = indicatorColor,
                    icon = Icons.Rounded.Tune
                )
            }
        }
    }
}

@Composable
private fun ActiveProcessingBanner(
    state: ProcessingState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = state.overallProgressPercent()
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(400),
        label = "progressAnim"
    )
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = scheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.25.dp, scheme.primary.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(scheme.primary, CircleShape)
                    )
                    Text(
                        text = stringResource(R.string.processing_background_status),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface
                    )
                }
                Text(
                    text = "${animatedProgress.toInt()}%",
                    fontWeight = FontWeight.ExtraBold,
                    color = scheme.primary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            LinearProgressIndicator(
                progress = { (animatedProgress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(100.dp)),
                trackColor = scheme.surfaceContainerHighest,
                color = scheme.primary
            )
            Text(
                text = stringResource(R.string.tap_to_open_processing),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeErrorDialog(
    error: com.transvibe.app.domain.error.ProcessingError?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onModels: () -> Unit
) {
    error ?: return
    val ui = ProcessingUiErrorMapper.map(error)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(ui.title),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(ui.explanation),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(ui.nextStep),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            if (ui.retryable) TextButton(onClick = { onDismiss(); onRetry() }) {
                Text(stringResource(R.string.retry_processing), fontWeight = FontWeight.Bold)
            }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            if (ui.opensResources) TextButton(onClick = { onDismiss(); onModels() }) {
                Text(stringResource(R.string.prepare_required_resources))
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun VideoDropzone(
    videoName: String?,
    videoSize: Long?,
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = scheme.primary.copy(alpha = 0.55f)
    GlassCard(highlighted = videoName != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = CornerRadius(22.dp.toPx()),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                        )
                    )
                }
                .clickable(enabled = enabled, onClick = onPick)
                .padding(12.dp) // هذا الهامش يصنع المسافة بمقدار ٣ ملم من الإطار المتقطع
                .background(
                    color = scheme.surface,
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                scheme.primary.copy(alpha = 0.25f),
                                scheme.primaryContainer
                            )
                        ),
                        CircleShape
                    )
                    .border(1.dp, scheme.primary.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.VideoFile,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = scheme.primary
                )
            }
            if (videoName == null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.choose_video),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.video_supported_formats),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    text = videoName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (videoSize != null) {
                    MetadataBadge(Icons.Rounded.VideoFile, human(videoSize), highlighted = true)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SecondaryActionButton(
                        text = stringResource(R.string.change_video),
                        onClick = onPick,
                        enabled = enabled,
                        icon = Icons.Rounded.VideoFile,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.remove_video),
                        onClick = onClear,
                        enabled = enabled,
                        icon = Icons.Rounded.Close,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowStepHeader(
    step: String,
    icon: ImageVector,
    title: String
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(scheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                fontWeight = FontWeight.ExtraBold,
                color = scheme.onPrimary,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(scheme.primaryContainer.copy(alpha = 0.65f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit
) = DropdownChoice(options, selected, onSelected)

@Composable
private fun SourceLanguageChoices(
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit
) = DropdownChoice(options, selected, onSelected)
