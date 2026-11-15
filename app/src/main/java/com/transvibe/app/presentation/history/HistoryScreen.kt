package com.transvibe.app.presentation.history

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.model.ProcessingHistorySummary
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.presentation.components.EmptyStateIllustration
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.MetadataBadge
import com.transvibe.app.presentation.components.ScreenHeroHeader
import com.transvibe.app.presentation.components.ScrollablePillTabs
import com.transvibe.app.presentation.components.StatusPill
import com.transvibe.app.presentation.theme.LocalStatusColors

@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpen: (ProcessingHistorySummary) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var filter by remember { mutableIntStateOf(0) }

    val filtered = remember(history, filter) {
        when (filter) {
            1 -> history.filter { it.state == ProcessingJobState.COMPLETED }
            2 -> history.filter { it.state in setOf(ProcessingJobState.CANCELLED, ProcessingJobState.INTERRUPTED) }
            3 -> history.filter { it.state == ProcessingJobState.FAILED }
            else -> history
        }
    }

    if (pendingDelete != null) {
        val ids = pendingDelete.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.history_delete_confirm_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = if (ids.size == 1) stringResource(R.string.history_delete_one_confirm)
                    else stringResource(R.string.history_delete_many_confirm, ids.size),
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (ids.size == 1) vm.deleteOne(ids.first())
                        else vm.deleteSelected()
                        pendingDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.history_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    val completedCount = remember(history) { history.count { it.state == ProcessingJobState.COMPLETED } }
    val interruptedCount = remember(history) {
        history.count { it.state in setOf(ProcessingJobState.CANCELLED, ProcessingJobState.INTERRUPTED) }
    }
    val failedCount = remember(history) { history.count { it.state == ProcessingJobState.FAILED } }

    val filterOptions = listOf(
        "0" to "${stringResource(R.string.history_filter_all)} (${history.size})",
        "1" to "${stringResource(R.string.history_filter_completed)} ($completedCount)",
        "2" to "${stringResource(R.string.history_filter_interrupted)} ($interruptedCount)",
        "3" to "${stringResource(R.string.history_filter_failed)} ($failedCount)"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            val isAllSelected = history.isNotEmpty() && selected.size == history.size
            ScreenHeroHeader(
                title = stringResource(R.string.history_title),
                subtitle = stringResource(R.string.history_subtitle),
                icon = Icons.Rounded.History,
                trailing = if (history.isNotEmpty()) {
                    {
                        OutlinedButton(
                            onClick = { if (isAllSelected) vm.clearSelection() else vm.selectAll() },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(
                                    if (isAllSelected) R.string.history_clear_selection
                                    else R.string.history_select_all
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                } else null
            )
        }

        item {
            ScrollablePillTabs(
                tabs = filterOptions,
                selected = filter.toString(),
                onSelected = { filter = it.toIntOrNull() ?: 0 }
            )
        }

        item {
            AnimatedVisibility(
                visible = selected.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selected.size} • ${stringResource(R.string.history_delete_selected)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(
                            onClick = { pendingDelete = selected },
                            modifier = Modifier.heightIn(min = 42.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.history_delete),
                                modifier = Modifier.padding(start = 6.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillParentMaxHeight(0.62f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateIllustration(
                        icon = Icons.Rounded.History,
                        title = stringResource(
                            if (history.isEmpty()) R.string.history_empty_title
                            else R.string.history_filter_empty_title
                        ),
                        subtitle = stringResource(
                            if (history.isEmpty()) R.string.history_empty_subtitle
                            else R.string.history_filter_empty_subtitle
                        )
                    )
                }
            }
        } else {
            items(filtered, key = { it.jobId }) { s ->
                HistoryCard(
                    summary = s,
                    selected = s.jobId in selected,
                    onToggle = { vm.toggleSelected(s.jobId) },
                    onOpen = { onOpen(s) },
                    onDelete = { pendingDelete = setOf(s.jobId) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryCard(
    summary: ProcessingHistorySummary,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    GlassCard(highlighted = selected) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    scheme.primary.copy(alpha = 0.28f),
                                    scheme.primaryContainer
                                )
                            ),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.VideoFile,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = summary.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurface
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MetadataBadge(Icons.Rounded.Memory, summary.modelId)
                        MetadataBadge(Icons.Rounded.Schedule, summary.detectedLanguage.uppercase())
                    }
                }

                HistoryStatePill(summary.state)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        scheme.surfaceContainer.copy(alpha = 0.65f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = scheme.primary
                        )
                    )
                    Text(
                        text = relativeTime(summary.createdAtEpochMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (summary.state == ProcessingJobState.COMPLETED) {
                        IconButton(
                            onClick = onOpen,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = scheme.primary
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(scheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.open_session),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = scheme.error
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(scheme.errorContainer.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.history_delete),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun relativeTime(epochMs: Long): String = DateUtils.getRelativeTimeSpanString(
    epochMs,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE
).toString()

@Composable
private fun HistoryStatePill(state: ProcessingJobState) {
    val colors = LocalStatusColors.current
    val color = when (state) {
        ProcessingJobState.COMPLETED -> colors.ready
        ProcessingJobState.RUNNING, ProcessingJobState.QUEUED -> colors.processing
        ProcessingJobState.FAILED -> colors.error
        ProcessingJobState.CANCELLED, ProcessingJobState.INTERRUPTED -> colors.warning
    }
    StatusPill(label = jobStateLabel(state), color = color)
}

@Composable
private fun jobStateLabel(state: ProcessingJobState): String = stringResource(
    when (state) {
        ProcessingJobState.COMPLETED -> R.string.state_completed
        ProcessingJobState.FAILED -> R.string.state_failed
        ProcessingJobState.CANCELLED -> R.string.state_cancelled
        ProcessingJobState.INTERRUPTED -> R.string.state_interrupted
        ProcessingJobState.RUNNING -> R.string.state_running
        ProcessingJobState.QUEUED -> R.string.state_queued
    }
)