package com.transvibe.app.presentation.processing

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.ScreenHeroHeader
import com.transvibe.app.presentation.components.StatusPill
import com.transvibe.app.presentation.components.overallProgressPercent
import com.transvibe.app.presentation.theme.LocalStatusColors

private enum class StageVisualState { COMPLETED, ACTIVE, PENDING, FAILED }

private data class StageItem(val stage: ProcessingStage, val label: Int)

private val pipelineStages = listOf(
    StageItem(ProcessingStage.INSPECTING_MEDIA, R.string.pipeline_inspecting_media),
    StageItem(ProcessingStage.PREPARING_AUDIO, R.string.pipeline_preparing_audio),
    StageItem(ProcessingStage.DETECTING_LANGUAGE, R.string.pipeline_detecting_language),
    StageItem(ProcessingStage.RECOGNIZING, R.string.pipeline_recognizing_speech),
    StageItem(ProcessingStage.TRANSLATING, R.string.pipeline_translating),
    StageItem(ProcessingStage.GENERATING_SUBTITLES, R.string.pipeline_generating_subtitles),
    StageItem(ProcessingStage.FINALIZING, R.string.pipeline_finalizing)
)

@Composable
fun ProcessingScreen(
    vm: ProcessingViewModel,
    state: ProcessingState,
    onPlayer: () -> Unit,
    onHome: () -> Unit,
    onContinueInBackground: () -> Unit,
    onModels: () -> Unit
) {
    val stage by vm.stage.collectAsStateWithLifecycle()
    val recent by vm.recentSegments.collectAsStateWithLifecycle()
    val cancelling by vm.cancelling.collectAsStateWithLifecycle()
    // Wait for the durable CANCELLED state; navigation does not race the service's request.
    // This also handles cancellation arriving while the app was backgrounded.
    LaunchedEffect(state) {
        when (processingTerminalRoute(state)) {
            ProcessingTerminalRoute.RESULTS -> onPlayer()
            ProcessingTerminalRoute.HOME -> onHome()
            null -> Unit
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeroHeader(
                title = stringResource(R.string.processing_status),
                subtitle = stringResource(R.string.processing_pipeline_subtitle),
                icon = Icons.Rounded.AutoAwesome
            )
        }
        if (state !is ProcessingState.Error && state !is ProcessingState.Cancelled) {
            item { OverallProgressCard(state) }
        }
        if (recent.isNotEmpty() && state !is ProcessingState.Error) {
            item { RecentSubtitlePreview(recent) }
        }
        item { PipelineCard(stage, state) }
        if (state is ProcessingState.Error) {
            item {
                StructuredErrorCard(
                    state = state,
                    onRetry = vm::retry,
                    onModels = onModels
                )
            }
        }
        if (state !is ProcessingState.Error && state !is ProcessingState.Cancelled && state !is ProcessingState.Completed) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = onContinueInBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.continue_in_background),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = vm::cancel,
                        enabled = !cancelling,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (cancelling) R.string.cancelling_processing else R.string.cancel_processing
                            ),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        if (state is ProcessingState.Error || state is ProcessingState.Cancelled) {
            item {
                OutlinedButton(
                    onClick = onHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.back_home),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun OverallProgressCard(state: ProcessingState) {
    val target = (state.overallProgressPercent() / 100f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(450),
        label = "overall-progress"
    )
    val scheme = MaterialTheme.colorScheme
    GlassCard(highlighted = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(86.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp,
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHighest
                )
                Text(
                    text = "${(animated * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = scheme.primary
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.overall_progress),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = scheme.onSurface
                )
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp)
                        .clip(RoundedCornerShape(100.dp)),
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHighest
                )
                Text(
                    text = stringResource(R.string.processing_pipeline_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PipelineCard(currentStage: ProcessingStage, state: ProcessingState) {
    val normalizedStage = normalizeStage(currentStage)
    val activeIndex = pipelineStages.indexOfFirst { it.stage == normalizedStage }.coerceAtLeast(0)
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.processing_pipeline_subtitle),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            pipelineStages.forEachIndexed { index, item ->
                val visual = when {
                    state is ProcessingState.Error && index == activeIndex -> StageVisualState.FAILED
                    state is ProcessingState.Completed || currentStage == ProcessingStage.COMPLETED -> StageVisualState.COMPLETED
                    index < activeIndex -> StageVisualState.COMPLETED
                    index == activeIndex && state !is ProcessingState.Idle -> StageVisualState.ACTIVE
                    else -> StageVisualState.PENDING
                }
                StageRow(
                    item = item,
                    visual = visual,
                    progress = measurableProgress(item.stage, state),
                    showConnector = index < pipelineStages.lastIndex
                )
            }
        }
    }
}

@Composable
private fun StageRow(
    item: StageItem,
    visual: StageVisualState,
    progress: Float?,
    showConnector: Boolean
) {
    val statusText = when (visual) {
        StageVisualState.COMPLETED -> stringResource(R.string.stage_status_completed)
        StageVisualState.ACTIVE -> stringResource(R.string.stage_status_active)
        StageVisualState.PENDING -> stringResource(R.string.stage_status_pending)
        StageVisualState.FAILED -> stringResource(R.string.stage_status_failed)
    }
    val semantic = LocalStatusColors.current
    val scheme = MaterialTheme.colorScheme
    val tint = when (visual) {
        StageVisualState.COMPLETED -> semantic.ready
        StageVisualState.ACTIVE -> semantic.processing
        StageVisualState.PENDING -> scheme.outline
        StageVisualState.FAILED -> semantic.error
    }
    val containerTint = when (visual) {
        StageVisualState.COMPLETED -> semantic.readyContainer
        StageVisualState.ACTIVE -> semantic.processingContainer
        StageVisualState.PENDING -> scheme.surfaceContainerHigh
        StageVisualState.FAILED -> semantic.errorContainer
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse-$item")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(containerTint, CircleShape)
                    .then(
                        if (visual == StageVisualState.ACTIVE) {
                            Modifier.border(
                                width = 2.dp,
                                color = tint.copy(alpha = pulseAlpha),
                                shape = CircleShape
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (visual) {
                    StageVisualState.COMPLETED -> Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = statusText,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    StageVisualState.FAILED -> Icon(
                        Icons.Rounded.Error,
                        contentDescription = statusText,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    StageVisualState.ACTIVE -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.5.dp,
                        color = tint,
                        trackColor = containerTint.copy(alpha = 0.5f)
                    )
                    StageVisualState.PENDING -> Icon(
                        Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = statusText,
                        tint = tint.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(if (visual == StageVisualState.ACTIVE) 38.dp else 22.dp)
                        .background(
                            if (visual == StageVisualState.COMPLETED) tint.copy(alpha = 0.5f)
                            else tint.copy(alpha = 0.18f)
                        )
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp, bottom = if (showConnector) 8.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(item.label),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (visual == StageVisualState.ACTIVE) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (visual == StageVisualState.PENDING) scheme.onSurfaceVariant else scheme.onSurface
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (visual == StageVisualState.ACTIVE || visual == StageVisualState.COMPLETED) tint else scheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
            if (visual == StageVisualState.ACTIVE && progress != null) {
                val animated by animateFloatAsState(
                    targetValue = (progress / 100f).coerceIn(0f, 1f),
                    animationSpec = tween(350),
                    label = "stage-progress"
                )
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(100.dp)),
                    color = tint,
                    trackColor = containerTint
                )
                Text(
                    text = stringResource(R.string.measured_progress_percent, (animated * 100).toInt().coerceIn(0, 100)),
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun measurableProgress(stage: ProcessingStage, state: ProcessingState): Float? = when {
    stage == ProcessingStage.RECOGNIZING && state is ProcessingState.Recognizing ->
        (((state.progress - 20f) / 55f) * 100f).coerceIn(0f, 100f)
    stage == ProcessingStage.TRANSLATING && state is ProcessingState.Translating ->
        (((state.progress - 75f) / 20f) * 100f).coerceIn(0f, 100f)
    else -> null
}

private fun normalizeStage(stage: ProcessingStage): ProcessingStage = when (stage) {
    ProcessingStage.PREPARING -> ProcessingStage.INSPECTING_MEDIA
    ProcessingStage.EXTRACTING_AUDIO -> ProcessingStage.PREPARING_AUDIO
    ProcessingStage.EXPORTING -> ProcessingStage.GENERATING_SUBTITLES
    else -> stage
}

@Composable
private fun RecentSubtitlePreview(segments: List<SubtitleSegment>) {
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Subtitles,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.recent_subtitle_preview),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface
                    )
                }
                StatusPill(label = "LIVE", color = statusColors.ready)
            }
            segments.takeLast(3).forEach { segment ->
                val text = segment.translatedText ?: segment.englishText ?: segment.originalText
                if (text.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.surfaceContainer,
                        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            textAlign = TextAlign.Center,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StructuredErrorCard(
    state: ProcessingState.Error,
    onRetry: () -> Unit,
    onModels: () -> Unit
) {
    val ui = ProcessingUiErrorMapper.map(state.error)
    val semantic = LocalStatusColors.current
    GlassCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(semantic.errorContainer.copy(alpha = 0.45f))
                .padding(16.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(semantic.error.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Error,
                        contentDescription = stringResource(R.string.stage_status_failed),
                        tint = semantic.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = stringResource(ui.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(ui.explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(ui.nextStep),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = semantic.error
                    )
                }
            }
            if (ui.opensResources) {
                Button(
                    onClick = onModels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = semantic.error)
                ) {
                    Text(
                        text = stringResource(R.string.manage_required_pack),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (ui.retryable) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = semantic.error),
                    border = BorderStroke(1.25.dp, semantic.error.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = stringResource(R.string.retry_processing),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}