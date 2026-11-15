package com.transvibe.app.presentation.navigation

import android.app.Activity
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History as FilledHistory
import androidx.compose.material.icons.filled.Home as FilledHome
import androidx.compose.material.icons.filled.Settings as FilledSettings
import androidx.compose.material.icons.filled.Tune as FilledTune
import androidx.compose.material.icons.outlined.History as OutlinedHistory
import androidx.compose.material.icons.outlined.Home as OutlinedHome
import androidx.compose.material.icons.outlined.Settings as OutlinedSettings
import androidx.compose.material.icons.outlined.Tune as OutlinedTune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.transvibe.app.R
import com.transvibe.app.TransVibeApplication
import com.transvibe.app.presentation.components.AppBackground
import com.transvibe.app.presentation.history.HistoryScreen
import com.transvibe.app.presentation.history.HistoryViewModel
import com.transvibe.app.presentation.home.HomeScreen
import com.transvibe.app.presentation.home.HomeViewModel
import com.transvibe.app.presentation.models.ModelManagerScreen
import com.transvibe.app.presentation.models.ModelsViewModel
import com.transvibe.app.presentation.player.PlayerScreen
import com.transvibe.app.presentation.player.PlayerViewModel
import com.transvibe.app.presentation.processing.ProcessingScreen
import com.transvibe.app.presentation.processing.ProcessingViewModel
import com.transvibe.app.presentation.results.ResultsScreen
import com.transvibe.app.presentation.settings.AboutScreen
import com.transvibe.app.presentation.settings.SettingsScreen
import com.transvibe.app.presentation.settings.SettingsViewModel
import com.transvibe.app.presentation.theme.TransVibeTheme
import com.transvibe.app.presentation.welcome.WelcomeScreen

private object Routes {
    const val HOME = "home"
    const val MODELS = "models"
    const val PROCESSING = "processing"
    const val PLAYER = "player"
    const val RESULTS = "results"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val HISTORY = "history"
}

@Composable
fun TransVibeApp() {
    val app = LocalContext.current.applicationContext as TransVibeApplication
    val factory = remember(app) { app.viewModelFactory }
    val homeVm: HomeViewModel = viewModel(factory = factory)
    val processingVm: ProcessingViewModel = viewModel(factory = factory)
    val modelsVm: ModelsViewModel = viewModel(factory = factory)
    val settingsVm: SettingsViewModel = viewModel(factory = factory)
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val activity = LocalContext.current as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    var showExitConfirmation by remember { mutableStateOf(false) }
    var restoreProcessingOnResume by rememberSaveable { mutableStateOf(true) }
    var processingDetachedToHome by rememberSaveable { mutableStateOf(false) }
    var backNavigationInFlight by remember { mutableStateOf(false) }
    val welcomePrefs = remember(app) { app.getSharedPreferences("transvibe_startup", android.content.Context.MODE_PRIVATE) }
    // An independent, persistent welcome gate keeps the NavHost start destination stable.
    // The welcome screen is completed only by explicit user confirmation.
    var introductionCompleted by rememberSaveable {
        mutableStateOf(welcomePrefs.getBoolean("introduction_completed_v1", false))
    }
    val process by processingVm.state.collectAsStateWithLifecycle()
    val homeMessage by homeVm.message.collectAsStateWithLifecycle()
    val processingMessage by processingVm.message.collectAsStateWithLifecycle()
    val modelsMessage by modelsVm.message.collectAsStateWithLifecycle()
    val settingsPrefs by settingsVm.preferences.collectAsStateWithLifecycle()

    fun goTop(target: String) {
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun returnHomeFromTerminal() {
        // Terminal routes (including cancellation) must not restore a previous processing screen.
        processingVm.clearCurrent()
        processingDetachedToHome = false
        restoreProcessingOnResume = false
        nav.navigate(Routes.HOME) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
            restoreState = false
        }
    }

    fun continueProcessingInBackground() {
        processingDetachedToHome = true
        restoreProcessingOnResume = false

        // Prefer returning to the existing Home entry. This avoids save/restore navigation
        // restoring the Processing destination immediately after the user explicitly detached it.
        val returnedToHome = nav.popBackStack(Routes.HOME, inclusive = false)
        if (!returnedToHome) {
            nav.navigate(Routes.HOME) {
                popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
                restoreState = false
            }
        }
    }

    LaunchedEffect(route) { backNavigationInFlight = false }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) restoreProcessingOnResume = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activeProcessing = when (process) {
        is com.transvibe.app.domain.model.ProcessingState.Preparing,
        is com.transvibe.app.domain.model.ProcessingState.ExtractingAudio,
        is com.transvibe.app.domain.model.ProcessingState.Recognizing,
        is com.transvibe.app.domain.model.ProcessingState.Translating,
        is com.transvibe.app.domain.model.ProcessingState.Exporting -> true
        else -> false
    }

    LaunchedEffect(activeProcessing) { if (!activeProcessing) processingDetachedToHome = false }

    LaunchedEffect(restoreProcessingOnResume, activeProcessing, route, processingDetachedToHome) {
        if (introductionCompleted && restoreProcessingOnResume && activeProcessing && !processingDetachedToHome) {
            if (route != Routes.PROCESSING && route != Routes.PLAYER) {
                nav.navigate(Routes.PROCESSING) { launchSingleTop = true }
            }
            restoreProcessingOnResume = false
        }
    }

    val message = when (route) {
        Routes.HOME -> homeMessage
        Routes.PROCESSING -> processingMessage
        Routes.MODELS -> modelsMessage
        else -> null
    }
    val dismissMessage: () -> Unit = when (route) {
        Routes.HOME -> homeVm::dismissMessage
        Routes.PROCESSING -> processingVm::dismissMessage
        Routes.MODELS -> modelsVm::dismissMessage
        else -> ({})
    }

    BackHandler(enabled = route == Routes.HOME && !showExitConfirmation) { showExitConfirmation = true }
    BackHandler(enabled = route == Routes.PLAYER && !backNavigationInFlight) {
        backNavigationInFlight = true
        returnHomeFromTerminal()
    }
    BackHandler(enabled = route == Routes.PROCESSING && !backNavigationInFlight) {
        if (activeProcessing) continueProcessingInBackground() else {
            backNavigationInFlight = true
            returnHomeFromTerminal()
        }
    }

    val deviceDensity = LocalDensity.current
    val scaledDensity = remember(deviceDensity, settingsPrefs.interfaceTextScale) {
        Density(deviceDensity.density, deviceDensity.fontScale * settingsPrefs.interfaceTextScale)
    }
    LocalizedUi(settingsPrefs.applicationLanguage) {
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            TransVibeTheme(themeMode = settingsPrefs.themeMode) {
                if (showExitConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showExitConfirmation = false },
                        title = { Text(stringResource(R.string.exit_confirm_title), fontWeight = FontWeight.Bold) },
                        text = { Text(stringResource(R.string.exit_confirm_message)) },
                        confirmButton = {
                            TextButton(onClick = { showExitConfirmation = false; activity?.finish() }) {
                                Text(stringResource(R.string.exit_action), fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitConfirmation = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        },
                        shape = RoundedCornerShape(24.dp)
                    )
                }

                if (!introductionCompleted) {
                    WelcomeScreen(onStart = {
                        // Commit the completion state before revealing the normal navigation graph.
                        // A failed write leaves the welcome gate intact, avoiding a false completion.
                        val saved = welcomePrefs.edit()
                            .putBoolean("introduction_completed_v1", true)
                            .commit()
                        if (saved) introductionCompleted = true
                    })
                } else {
                    AppBackground {
                        Scaffold(
                            containerColor = Color.Transparent,
                            bottomBar = {
                                AnimatedVisibility(
                                    visible = route in setOf(Routes.HOME, Routes.MODELS, Routes.HISTORY, Routes.SETTINGS, Routes.ABOUT),
                                    enter = expandVertically(animationSpec = tween(250), expandFrom = Alignment.Bottom) + fadeIn(animationSpec = tween(180)),
                                    exit = shrinkVertically(animationSpec = tween(250), shrinkTowards = Alignment.Bottom) + fadeOut(animationSpec = tween(180))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(26.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 4.dp,
                                            shadowElevation = 6.dp,
                                            border = BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                AppNavItem(
                                                    selected = route == Routes.HOME,
                                                    onClick = { goTop(Routes.HOME) },
                                                    selectedIcon = Icons.Filled.FilledHome,
                                                    unselectedIcon = Icons.Outlined.OutlinedHome,
                                                    label = stringResource(R.string.nav_home),
                                                    hasBadge = activeProcessing && route != Routes.HOME
                                                )
                                                AppNavItem(
                                                    selected = route == Routes.MODELS,
                                                    onClick = { goTop(Routes.MODELS) },
                                                    selectedIcon = Icons.Filled.FilledTune,
                                                    unselectedIcon = Icons.Outlined.OutlinedTune,
                                                    label = stringResource(R.string.nav_models)
                                                )
                                                AppNavItem(
                                                    selected = route == Routes.HISTORY,
                                                    onClick = { goTop(Routes.HISTORY) },
                                                    selectedIcon = Icons.Filled.FilledHistory,
                                                    unselectedIcon = Icons.Outlined.OutlinedHistory,
                                                    label = stringResource(R.string.nav_history)
                                                )
                                                AppNavItem(
                                                    selected = route == Routes.SETTINGS || route == Routes.ABOUT,
                                                    onClick = { if (route == Routes.ABOUT) nav.popBackStack(Routes.SETTINGS, false) else goTop(Routes.SETTINGS) },
                                                    selectedIcon = Icons.Filled.FilledSettings,
                                                    unselectedIcon = Icons.Outlined.OutlinedSettings,
                                                    label = stringResource(R.string.nav_settings)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        ) { pad ->
                            NavHost(
                                navController = nav,
                                startDestination = Routes.HOME,
                                modifier = Modifier.padding(pad),
                                enterTransition = { defaultEnterTransition() },
                                exitTransition = { defaultExitTransition() },
                                popEnterTransition = { defaultPopEnterTransition() },
                                popExitTransition = { defaultPopExitTransition() }
                            ) {
                                composable(Routes.HOME) {
                                    HomeScreen(
                                        vm = homeVm,
                                        process = process,
                                        onCancelProcessing = processingVm::cancel,
                                        onModels = { goTop(Routes.MODELS) },
                                        onProcessing = { processingDetachedToHome = false; nav.navigate(Routes.PROCESSING) { launchSingleTop = true } }
                                    )
                                }
                                composable(Routes.MODELS) { ModelManagerScreen(modelsVm) }
                                composable(Routes.PROCESSING) {
                                    ProcessingScreen(
                                        processingVm,
                                        process,
                                        onPlayer = { nav.navigate(Routes.RESULTS) { launchSingleTop = true } },
                                        onHome = ::returnHomeFromTerminal,
                                        onContinueInBackground = ::continueProcessingInBackground,
                                        onModels = { goTop(Routes.MODELS) }
                                    )
                                }
                                composable(Routes.RESULTS) {
                                    val completed = process as? com.transvibe.app.domain.model.ProcessingState.Completed
                                    ResultsScreen(
                                        session = completed?.result?.session,
                                        onPlay = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                                        onHome = ::returnHomeFromTerminal
                                    )
                                }
                                composable(Routes.PLAYER) {
                                    val playerVm: PlayerViewModel = viewModel(factory = factory)
                                    PlayerScreen(playerVm, onAnother = { returnHomeFromTerminal() })
                                }
                                composable(Routes.SETTINGS) {
                                    SettingsScreen(
                                        vm = settingsVm,
                                        onModels = { goTop(Routes.MODELS) },
                                        onAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } }
                                    )
                                }
                                composable(Routes.ABOUT) {
                                    AboutScreen(onBack = { nav.popBackStack() })
                                }
                                composable(Routes.HISTORY) {
                                    val historyVm: HistoryViewModel = viewModel(factory = factory)
                                    val historyMessage by historyVm.message.collectAsStateWithLifecycle()
                                    HistoryScreen(historyVm) { summary ->
                                        historyVm.openSession(summary.jobId) { nav.navigate(Routes.PLAYER) }
                                    }
                                    historyMessage?.let { text ->
                                        AlertDialog(
                                            onDismissRequest = historyVm::dismissMessage,
                                            confirmButton = { TextButton(onClick = historyVm::dismissMessage) { Text(stringResource(R.string.ok)) } },
                                            text = { Text(text, color = MaterialTheme.colorScheme.onSurface) },
                                            shape = RoundedCornerShape(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                message?.let {
                    AlertDialog(
                        onDismissRequest = dismissMessage,
                        confirmButton = { TextButton(onClick = dismissMessage) { Text(stringResource(R.string.ok)) } },
                        text = { Text(it, color = MaterialTheme.colorScheme.onSurface) },
                        shape = RoundedCornerShape(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.AppNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String,
    hasBadge: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val pillColor by animateColorAsState(
        targetValue = if (selected) scheme.primaryContainer else Color.Transparent,
        animationSpec = tween(220),
        label = "navPill"
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "navIcon"
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .sizeIn(minHeight = 60.dp)
            .clip(RoundedCornerShape(18.dp))
            .semantics { this.selected = selected; role = Role.Tab }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(pillColor)
                .padding(horizontal = 18.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
            if (hasBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(scheme.tertiary)
                )
            }
        }
        Text(
            text = label,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun defaultEnterTransition(): EnterTransition =
    fadeIn(tween(220)) + slideInHorizontally(tween(220), initialOffsetX = { fullWidth -> fullWidth / 14 })

private fun defaultExitTransition(): ExitTransition =
    fadeOut(tween(180)) + slideOutHorizontally(tween(220), targetOffsetX = { fullWidth -> -fullWidth / 14 })

private fun defaultPopEnterTransition(): EnterTransition =
    fadeIn(tween(220)) + slideInHorizontally(tween(220), initialOffsetX = { fullWidth -> -fullWidth / 14 })

private fun defaultPopExitTransition(): ExitTransition =
    fadeOut(tween(180)) + slideOutHorizontally(tween(220), targetOffsetX = { fullWidth -> fullWidth / 14 })

@Composable
private fun LocalizedUi(language: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    // Capture this owner BEFORE replacing LocalContext with a locale-specific Context.
    // rememberLauncherForActivityResult in HomeScreen needs the Activity's registry.
    val activityResultOwner = LocalActivityResultRegistryOwner.current
    val baseConfiguration = LocalConfiguration.current
    if (language == "system") {
        content()
        return
    }
    val configuration = remember(baseConfiguration, language) {
        Configuration(baseConfiguration).apply { setLocales(LocaleList.forLanguageTags(language)) }
    }
    val localizedContext = remember(baseContext, configuration) {
        baseContext.createConfigurationContext(configuration)
    }
    val direction = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    if (activityResultOwner != null) {
        CompositionLocalProvider(
            LocalActivityResultRegistryOwner provides activityResultOwner,
            LocalContext provides localizedContext,
            LocalConfiguration provides configuration,
            LocalLayoutDirection provides direction,
            content = content
        )
    } else {
        // Preserve previous behavior in non-Activity preview/test compositions.
        CompositionLocalProvider(
            LocalContext provides localizedContext,
            LocalConfiguration provides configuration,
            LocalLayoutDirection provides direction,
            content = content
        )
    }
}
