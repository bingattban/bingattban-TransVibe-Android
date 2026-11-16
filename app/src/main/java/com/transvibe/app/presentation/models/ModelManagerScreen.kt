package com.transvibe.app.presentation.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transvibe.app.R
import com.transvibe.app.domain.asr.FastAsrModelInfo
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.ModelIntegrityState
import com.transvibe.app.domain.model.ModelOrigin
import com.transvibe.app.domain.model.TranslationResourceState
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.presentation.components.GlassCard
import com.transvibe.app.presentation.components.MetadataBadge
import com.transvibe.app.presentation.components.ScreenHeroHeader
import com.transvibe.app.presentation.components.ScrollablePillTabs
import com.transvibe.app.presentation.components.SectionHeader
import com.transvibe.app.presentation.components.downloadStatusLabel
import com.transvibe.app.presentation.components.human
import com.transvibe.app.presentation.components.modelIntegrityLabel
import com.transvibe.app.presentation.components.translationStateLabel
import java.util.Locale

@Composable
fun ModelManagerScreen(vm: ModelsViewModel) {
    val resource by vm.resources.collectAsStateWithLifecycle()
    val packStorage by vm.translationPackStorage.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val installedTranslationPacks by vm.installedTranslationPacks.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(0) }
    val sections = listOf(
        "0" to "Whisper",
        "1" to "Moonshine",
        "2" to "SenseVoice",
        "3" to stringResource(R.string.translation_packs)
    )

    LaunchedEffect(Unit) {
        vm.refreshTranslationPacks()
        vm.refreshFastAsrModels()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScreenHeroHeader(
                title = stringResource(R.string.models_title),
                subtitle = stringResource(R.string.models_subtitle),
                icon = Icons.Rounded.Tune
            )

            ScrollablePillTabs(
                tabs = sections,
                selected = section.toString(),
                onSelected = { section = it.toIntOrNull() ?: 0 }
            )
        }

        when (section) {
            0 -> WhisperModelsPage(vm, models, resource, selectedModelId)
            1 -> FastAsrCategoryPage(
                vm,
                vm.fastAsrModelsList.filter { it.id.startsWith("moonshine") },
                stringResource(R.string.model_moonshine_title),
                stringResource(R.string.model_moonshine_hint)
            )
            2 -> FastAsrCategoryPage(
                vm,
                vm.fastAsrModelsList.filter { it.id.startsWith("sensevoice") },
                stringResource(R.string.model_sensevoice_title),
                stringResource(R.string.model_sensevoice_hint)
            )
            else -> TranslationPacksPage(
                vm,
                packStorage,
                installedTranslationPacks.map { it.language }
            )
        }
    }
}

@Composable
private fun WhisperModelsPage(
    vm: ModelsViewModel,
    models: List<WhisperModel>,
    resource: com.transvibe.app.domain.model.AiResourceSnapshot,
    selectedModelId: String
) {
    val customImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importUserModel) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeader(
                stringResource(R.string.model_whisper_title),
                stringResource(R.string.model_whisper_hint)
            )
        }
        item {
            FilledTonalButton(
                onClick = {
                    customImporter.launch(
                        arrayOf("application/octet-stream", "*/*")
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                Text(
                    text = stringResource(R.string.model_whisper_import),
                    modifier = Modifier.padding(start = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        items(models, key = { it.id }) { model ->
            ModelCard(
                vm = vm,
                model = model,
                integrity = resource.modelState(model.id),
                selected = selectedModelId == model.id
            )
        }
    }
}

@Composable
private fun FastAsrCategoryPage(
    vm: ModelsViewModel,
    models: List<FastAsrModelInfo>,
    title: String,
    subtitle: String
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionHeader(title, subtitle) }
        items(models, key = { it.id }) { FastAsrModelCard(vm, it) }
    }
}

@Composable
private fun TranslationPacksPage(
    vm: ModelsViewModel,
    packStorage: com.transvibe.app.domain.model.TranslationPackStorage,
    installed: List<LanguageCode>
) {
    val displayLocale = LocalConfiguration.current.locales[0] ?: Locale.forLanguageTag("ar")
    val visiblePacks = buildList {
        add(LanguageCode.ARABIC)
        add(LanguageCode.ENGLISH)
        installed.filter {
            it != LanguageCode.AUTO && it != LanguageCode.ARABIC && it != LanguageCode.ENGLISH
        }.distinctBy { it.value }
            .sortedBy { translationLanguageLabel(it, displayLocale) }
            .forEach(::add)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeader(
                stringResource(R.string.translation_packs),
                stringResource(R.string.translation_packs_subtitle)
            )
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.pack_storage,
                            packStorage.installedPackCount,
                            human(packStorage.approximateBytes)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(visiblePacks, key = { "translation-pack-${it.value}" }) { language ->
            TranslationPackCard(
                vm = vm,
                language = language,
                languageLabel = translationLanguageLabel(language, displayLocale)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    vm: ModelsViewModel,
    model: WhisperModel,
    integrity: ModelIntegrityState,
    selected: Boolean
) {
    val ds by vm.downloadState(model).collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importModel(model, it) } }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { vm.exportModel(model, it) } }
    val active = ds.status in setOf(
        DownloadProgress.Status.DOWNLOADING,
        DownloadProgress.Status.RETRYING,
        DownloadProgress.Status.IMPORTING,
        DownloadProgress.Status.VERIFYING
    )
    val hasProgress = ds.totalBytes > 0 && (active || ds.status == DownloadProgress.Status.PAUSED)
    val visibleStatus = if (active || ds.status == DownloadProgress.Status.PAUSED) {
        downloadStatusLabel(ds.status)
    } else {
        modelIntegrityLabel(integrity)
    }
    val installed = integrity == ModelIntegrityState.READY
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = scheme.onSurface
                    )
                    if (model.recommended) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = scheme.tertiaryContainer.copy(alpha = 0.75f),
                            border = BorderStroke(1.dp, scheme.tertiary.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = stringResource(R.string.recommended),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onTertiaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                if (installed) {
                    FilledTonalButton(
                        onClick = { if (!selected) vm.selectModel(model.id) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (selected) scheme.primary else scheme.primaryContainer,
                            contentColor = if (selected) scheme.onPrimary else scheme.onPrimaryContainer
                        )
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = stringResource(
                                if (selected) R.string.model_selected
                                else R.string.model_activate
                            ),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = if (selected) 6.dp else 0.dp)
                        )
                    }
                } else {
                    ModelInstallBadge(installed = false, status = visibleStatus)
                }
            }

            Text(
                text = modelDescription(model),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetadataBadge(Icons.Rounded.Storage, human(model.fileSizeBytes))
                MetadataBadge(
                    Icons.Rounded.Memory,
                    human(model.estimatedRamBytes ?: 0L) + " " + stringResource(R.string.ram_suffix)
                )
                if (model.origin == ModelOrigin.OFFICIAL_TRUSTED) {
                    MetadataBadge(
                        Icons.Rounded.CloudDownload,
                        stringResource(R.string.model_trusted),
                        highlighted = true
                    )
                }
            }

            if (hasProgress) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { ds.percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(100.dp)),
                        trackColor = scheme.surfaceContainerHighest,
                        color = scheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${ds.percent.coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = scheme.primary
                        )
                        Text(
                            text = "${human(ds.downloadedBytes)} / ${human(ds.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (model.origin == ModelOrigin.OFFICIAL_TRUSTED && !installed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            when (ds.status) {
                                DownloadProgress.Status.DOWNLOADING,
                                DownloadProgress.Status.RETRYING -> vm.pause(model)
                                DownloadProgress.Status.PAUSED -> vm.download(model)
                                else -> vm.download(model)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(
                                if (ds.status == DownloadProgress.Status.PAUSED) R.string.resume
                                else R.string.download
                            ),
                            modifier = Modifier.padding(start = 6.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            importer.launch(
                                arrayOf("application/octet-stream", "*/*")
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.import_action),
                            modifier = Modifier.padding(start = 6.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    model.origin == ModelOrigin.USER_IMPORTED ||
                        integrity == ModelIntegrityState.READY -> {
                        OutlinedButton(
                            onClick = { vm.delete(model) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = scheme.error
                            ),
                            border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = stringResource(R.string.delete),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedButton(
                            onClick = { exporter.launch(model.fileName) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(R.string.model_export_action),
                                modifier = Modifier.padding(start = 5.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    ds.status == DownloadProgress.Status.FAILED -> Button(
                        onClick = { vm.retryDownload(model) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.retry),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    ds.status == DownloadProgress.Status.DOWNLOADING ||
                        ds.status == DownloadProgress.Status.RETRYING -> OutlinedButton(
                        onClick = { vm.cancelDownload(model) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    integrity == ModelIntegrityState.METADATA_MISSING ||
                        integrity == ModelIntegrityState.METADATA_STALE ||
                        integrity == ModelIntegrityState.VERIFYING -> OutlinedButton(
                        onClick = vm::refreshResources,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.recheck),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun FastAsrModelCard(vm: ModelsViewModel, model: FastAsrModelInfo) {
    val state by vm.fastAsrState(model.id).collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importFastAsrModel(model.id, it) } }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let { vm.exportFastAsrModel(model.id, it) } }
    val installed = state.status == DownloadProgress.Status.INSTALLED
    val active = state.status in setOf(
        DownloadProgress.Status.DOWNLOADING,
        DownloadProgress.Status.VERIFYING,
        DownloadProgress.Status.RETRYING,
        DownloadProgress.Status.IMPORTING
    )
    val scheme = MaterialTheme.colorScheme

    GlassCard(highlighted = installed) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = scheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.primaryContainer.copy(alpha = 0.65f))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.model_fast_labels,
                                model.supportedLanguages.joinToString(" / ") { it.uppercase(Locale.ROOT) }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                ModelInstallBadge(
                    installed = installed,
                    status = when (state.status) {
                        DownloadProgress.Status.INSTALLED -> stringResource(R.string.model_status_installed)
                        DownloadProgress.Status.DOWNLOADING -> stringResource(R.string.model_status_downloading)
                        DownloadProgress.Status.IMPORTING -> stringResource(R.string.model_status_importing)
                        DownloadProgress.Status.VERIFYING -> stringResource(R.string.model_status_verifying)
                        DownloadProgress.Status.FAILED -> stringResource(R.string.model_status_failed)
                        else -> stringResource(R.string.model_status_missing)
                    }
                )
            }
            Text(
                text = if (LocalConfiguration.current.locales[0].language == "en") {
                    stringResource(R.string.model_fast_description_generic)
                } else model.descriptionAr,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetadataBadge(Icons.Rounded.Storage, human(model.totalBytes))
                MetadataBadge(
                    Icons.Rounded.Memory,
                    human(model.estimatedRamBytes) + " RAM"
                )
            }
            if (active) {
                LinearProgressIndicator(
                    progress = { state.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(100.dp)),
                    trackColor = scheme.surfaceContainerHighest,
                    color = scheme.primary
                )
                Text(
                    text = "${state.percent.coerceIn(0, 100)}%  •  ${human(state.downloadedBytes)} / ${human(state.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            state.message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (installed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { vm.deleteFastAsrModel(model.id) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = scheme.error
                        ),
                        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = { exporter.launch(model.id + ".zip") },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.model_export_action),
                            modifier = Modifier.padding(start = 5.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { vm.downloadFastAsrModel(model.id) },
                        enabled = !active,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = if (state.status == DownloadProgress.Status.FAILED) stringResource(R.string.retry)
                            else stringResource(R.string.download),
                            modifier = Modifier.padding(start = 6.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            importer.launch(
                                arrayOf("application/zip", "application/octet-stream", "*/*")
                            )
                        },
                        enabled = !active,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.import_action),
                            modifier = Modifier.padding(start = 6.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelInstallBadge(installed: Boolean, status: String) {
    val scheme = MaterialTheme.colorScheme
    val container = if (installed) scheme.primaryContainer else scheme.surfaceContainerHigh
    val content = if (installed) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = content
        )
    }
}

@Composable
private fun TranslationPackCard(
    vm: ModelsViewModel,
    language: LanguageCode,
    languageLabel: String
) {
    val state by vm.translationStatus(language).collectAsStateWithLifecycle()
    val busy by vm.translationBusy.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val isReady = language == LanguageCode.ENGLISH || state == TranslationResourceState.READY

    GlassCard(highlighted = isReady) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    scheme.primary.copy(alpha = 0.25f),
                                    scheme.primaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Translate,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.translation_pack_language,
                            languageLabel
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface
                    )
                    Text(
                        text = translationStateLabel(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                MetadataBadge(
                    Icons.Rounded.CloudDownload,
                    translationStateLabel(state),
                    highlighted = isReady
                )
            }
            Text(
                text = stringResource(
                    when {
                        language == LanguageCode.ENGLISH -> R.string.pack_built_in
                        state == TranslationResourceState.READY -> R.string.pack_ready
                        state == TranslationResourceState.MISSING -> R.string.pack_missing
                        state == TranslationResourceState.DOWNLOADING -> R.string.pack_downloading
                        state == TranslationResourceState.ERROR -> R.string.pack_error
                        else -> R.string.pack_needs_update
                    }
                ),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            )
            if (state == TranslationResourceState.DOWNLOADING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(100.dp)),
                    trackColor = scheme.surfaceContainerHighest,
                    color = scheme.primary
                )
            }
            if (language == LanguageCode.ENGLISH) {
                Unit
            } else if (state == TranslationResourceState.READY) {
                OutlinedButton(
                    onClick = { vm.removeTranslationPack(language) },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = scheme.error
                    ),
                    border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = stringResource(R.string.remove_pack),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Button(
                    onClick = { vm.downloadTranslationPack(language) },
                    enabled = !busy && state != TranslationResourceState.DOWNLOADING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (state == TranslationResourceState.ERROR) R.string.retry
                            else R.string.download_pack
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun modelDescription(model: WhisperModel): String =
    if (LocalConfiguration.current.locales[0].language == "en") {
        stringResource(R.string.model_whisper_description_generic, model.displayName)
    } else model.descriptionAr

private fun translationLanguageLabel(language: LanguageCode, displayLocale: Locale): String {
    val localizedName = Locale.forLanguageTag(language.value).getDisplayLanguage(displayLocale).trim()
    return localizedName.ifBlank { language.value.uppercase(Locale.ROOT) }
}