package com.transvibe.app.presentation.results

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transvibe.app.R
import com.transvibe.app.data.runtime.SessionPerformanceStore
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.subtitle.SubtitleFileNames
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.PrimaryActionButton
import com.transvibe.app.presentation.components.ScreenHeroHeader
import com.transvibe.app.presentation.components.SecondaryActionButton
import java.util.Locale

@Composable
fun ResultsScreen(session: ProcessingSession?, onPlay: () -> Unit, onHome: () -> Unit) {
    val context = LocalContext.current
    val report = remember(session?.id) { session?.let { SessionPerformanceStore.load(context, it.id) } }
    fun time(ms: Long): String = String.format(Locale.US, "%.2f s", ms / 1000.0)
    val scheme = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeroHeader(
                title = stringResource(R.string.tv_results_title),
                subtitle = session?.videoName ?: stringResource(R.string.player_no_result),
                icon = Icons.Rounded.CheckCircle
            )
        }

        if (report != null) {
            // 2x2 Bento Summary Grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BentoMetricCard(
                            icon = Icons.Rounded.Timer,
                            label = stringResource(R.string.tv_results_total),
                            value = time(report.elapsedMs),
                            highlighted = true,
                            modifier = Modifier.weight(1f)
                        )
                        BentoMetricCard(
                            icon = Icons.Rounded.Subtitles,
                            label = stringResource(R.string.tv_results_segments),
                            value = report.segmentCount.toString(),
                            highlighted = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BentoMetricCard(
                            icon = Icons.Rounded.Psychology,
                            label = stringResource(R.string.tv_results_model),
                            value = report.modelId,
                            modifier = Modifier.weight(1f)
                        )
                        BentoMetricCard(
                            icon = Icons.Rounded.Memory,
                            label = stringResource(R.string.tv_results_backend),
                            value = when {
                                report.gpuBackend.startsWith("GPU|") -> stringResource(R.string.tv_backend_gpu) + " " + report.gpuBackend.substringAfter('|')
                                report.gpuBackend.startsWith("CPU|") -> stringResource(R.string.tv_backend_cpu) + " " + report.gpuBackend.substringAfter('|')
                                else -> stringResource(R.string.tv_backend_unverified)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Performance Breakdown Card with visual progress bars
            item {
                val maxStageMs = maxOf(
                    report.extractionPipelineMs,
                    report.languageDetectionMs,
                    report.recognitionMs,
                    report.translationMs,
                    report.savingMs,
                    report.vadMs,
                    1L
                )
                GlassCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.tv_results_performance),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface
                            )
                        }

                        StageTimingBar(
                            label = stringResource(R.string.tv_results_decode),
                            value = time(report.extractionPipelineMs),
                            fraction = report.extractionPipelineMs.toFloat() / maxStageMs
                        )
                        StageTimingBar(
                            label = stringResource(R.string.tv_results_detect),
                            value = time(report.languageDetectionMs),
                            fraction = report.languageDetectionMs.toFloat() / maxStageMs
                        )
                        StageTimingBar(
                            label = stringResource(R.string.tv_results_asr),
                            value = time(report.recognitionMs),
                            fraction = report.recognitionMs.toFloat() / maxStageMs
                        )
                        StageTimingBar(
                            label = stringResource(R.string.tv_results_translate),
                            value = time(report.translationMs),
                            fraction = report.translationMs.toFloat() / maxStageMs
                        )
                        StageTimingBar(
                            label = stringResource(R.string.tv_results_save),
                            value = time(report.savingMs),
                            fraction = report.savingMs.toFloat() / maxStageMs
                        )
                        StageTimingBar(
                            label = stringResource(R.string.tv_results_vad),
                            value = time(report.vadMs),
                            fraction = report.vadMs.toFloat() / maxStageMs
                        )

                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))

                        Text(
                            text = stringResource(R.string.tv_results_overlap_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Saved Subtitle Files Card
            item {
                GlassCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Description,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.tv_results_files),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurface
                            )
                        }

                        FileSavedRow(
                            badge = "SRT",
                            fileName = SubtitleFileNames.fileName(report.videoName, false),
                            location = report.srtLocation
                        )
                        FileSavedRow(
                            badge = "WebVTT",
                            fileName = SubtitleFileNames.fileName(report.videoName, true),
                            location = report.vttLocation
                        )

                        if (report.exportFailure.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.tv_results_export_problem) + ": " + report.exportFailure,
                                color = scheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        } else if (session != null) {
            item {
                GlassCard {
                    Text(
                        text = stringResource(R.string.tv_results_legacy),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (session != null) {
                    PrimaryActionButton(
                        text = stringResource(R.string.tv_results_play),
                        onClick = onPlay,
                        icon = Icons.Rounded.PlayArrow,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SecondaryActionButton(
                    text = stringResource(R.string.back_home),
                    onClick = onHome,
                    icon = Icons.Rounded.Home,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun BentoMetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    GlassCard(modifier = modifier, highlighted = highlighted) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StageTimingBar(label: String, value: String, fraction: Float) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface
            )
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0.03f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(100.dp)),
            color = scheme.primary,
            trackColor = scheme.surfaceContainerHigh
        )
    }
}

@Composable
private fun FileSavedRow(badge: String, fileName: String, location: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = scheme.primaryContainer
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = scheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = location,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
