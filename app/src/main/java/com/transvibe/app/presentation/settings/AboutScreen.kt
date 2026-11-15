package com.transvibe.app.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transvibe.app.R
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.ScreenHeroHeader

private data class AboutTableRow(
    val name: String,
    val version: String,
    val purpose: String,
    val source: String,
    val license: String
)

private val tableColumnWidths = listOf(225.dp, 95.dp, 270.dp, 145.dp, 220.dp)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val modelRows = listOf(
        AboutTableRow(
            stringResource(R.string.about_whisper_model_name),
            "-",
            stringResource(R.string.about_whisper_model_function),
            "OpenAI",
            stringResource(R.string.about_license_mit)
        ),
        AboutTableRow(
            "Silero VAD",
            "-",
            stringResource(R.string.about_silero_function),
            "Silero",
            stringResource(R.string.about_license_mit)
        ),
        AboutTableRow(
            "Google ML Kit Translation",
            "17.0.3",
            stringResource(R.string.about_mlkit_function),
            "Google",
            stringResource(R.string.about_license_google_closed)
        )
    )
    val toolRows = listOf(
        AboutTableRow("whisper.cpp", "1.9.4", stringResource(R.string.about_whisper_cpp_function), "ggerganov", stringResource(R.string.about_license_mit)),
        AboutTableRow("Sherpa-ONNX", "1.13.5", stringResource(R.string.about_sherpa_function), "k2-fsa", stringResource(R.string.about_license_apache)),
        AboutTableRow("ONNX Runtime", "1.27.0", stringResource(R.string.about_onnx_function), "Microsoft", stringResource(R.string.about_license_open)),
        AboutTableRow("FFmpegKit Free81", "8.1.9", stringResource(R.string.about_ffmpeg_function), "FFmpeg", stringResource(R.string.about_license_lgpl_gpl)),
        AboutTableRow("Android Media3 / ExoPlayer", "1.11.1", stringResource(R.string.about_media3_function), "Google / Android", stringResource(R.string.about_license_open)),
        AboutTableRow("Kotlin", "2.3.21", stringResource(R.string.about_kotlin_function), "JetBrains", stringResource(R.string.about_license_open)),
        AboutTableRow("Jetpack Compose UI", "1.11.4", stringResource(R.string.about_compose_function), "Google / Android", stringResource(R.string.about_license_open)),
        AboutTableRow("Material 3", "1.4.0", stringResource(R.string.about_material_function), "Google / Android", stringResource(R.string.about_license_open))
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeroHeader(
                title = stringResource(R.string.about),
                subtitle = "TransVibe v1.0.0",
                icon = Icons.Rounded.Info,
                trailing = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainer)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.about_back),
                            tint = scheme.onSurface
                        )
                    }
                }
            )
        }

        // Brand & Developer Showcase Card
        item {
            GlassCard(highlighted = true) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.transvibe_welcome_logo),
                            contentDescription = stringResource(R.string.app_name),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, scheme.primary.copy(alpha = 0.5f), CircleShape)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "TransVibe",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = scheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(100.dp),
                                color = scheme.primaryContainer,
                                border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = "${stringResource(R.string.about_app_version)} 1.0.0",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = scheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.surfaceContainer.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, scheme.tertiary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(scheme.tertiaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Code,
                                    contentDescription = null,
                                    tint = scheme.onTertiaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(R.string.about_programmer),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.about_programmer_value),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = scheme.onSurface
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))

                    AboutDetail(
                        stringResource(R.string.about_app_concept),
                        stringResource(R.string.about_app_concept_body)
                    )
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.about_ai_models_table),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = scheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { AboutTable(modelRows, stringResource(R.string.about_col_model_name)) }

        item {
            Text(
                text = stringResource(R.string.about_tools_table),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = scheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { AboutTable(toolRows, stringResource(R.string.about_col_tool_name)) }
        item { Spacer(modifier = Modifier.height(10.dp)) }
    }
}

@Composable
private fun AboutDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutTable(rows: List<AboutTableRow>, nameHeader: String) {
    GlassCard {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.about_swipe_table),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val headers = listOf(
                nameHeader,
                stringResource(R.string.about_col_version),
                stringResource(R.string.about_col_function),
                stringResource(R.string.about_col_source),
                stringResource(R.string.about_col_open_closed)
            )
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AboutTableLine(headers, header = true, alternate = false)
                rows.forEachIndexed { index, row ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    AboutTableLine(
                        listOf(row.name, row.version, row.purpose, row.source, row.license),
                        header = false,
                        alternate = index % 2 == 1
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutTableLine(cells: List<String>, header: Boolean, alternate: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        header -> scheme.primaryContainer.copy(alpha = 0.65f)
        alternate -> scheme.surfaceContainer.copy(alpha = 0.55f)
        else -> scheme.surfaceContainerLow
    }
    Row(modifier = Modifier.background(background), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { index, text ->
            AboutTableCell(
                value = text,
                width = tableColumnWidths[index],
                isHeader = header
            )
        }
    }
}

@Composable
private fun AboutTableCell(value: String, width: Dp, isHeader: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = value,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        style = if (isHeader) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
        color = if (isHeader) scheme.onPrimaryContainer else scheme.onSurface
    )
}
