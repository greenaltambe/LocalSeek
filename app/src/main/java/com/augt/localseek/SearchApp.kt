package com.augt.localseek

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import com.augt.localseek.ui.theme.ExpressiveMotion
import com.augt.localseek.ui.theme.rememberAnimationsEnabled
import com.augt.localseek.ui.settings.AppearancePage
import com.augt.localseek.ui.settings.BackupPage
import com.augt.localseek.ui.settings.DeveloperPage
import com.augt.localseek.ui.settings.IndexingPage
import com.augt.localseek.ui.settings.PrivacyPage
import com.augt.localseek.ui.settings.SearchSettingsPage
import com.augt.localseek.ui.settings.SettingsDestination
import com.augt.localseek.ui.settings.SettingsHome
import com.augt.localseek.ui.settings.WebSettingsPage
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.augt.localseek.tools.ToolsRepository
import com.augt.localseek.ui.about.AboutScreen
import com.augt.localseek.ui.onboarding.OnboardingPermissions
import com.augt.localseek.ui.onboarding.OnboardingScreen
import com.augt.localseek.ui.onboarding.OnboardingViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.augt.localseek.ui.performance.PerformanceDashboard
import com.augt.localseek.ui.SearchScreen
import com.augt.localseek.ui.SearchViewModel
import com.augt.localseek.ui.qrels.QrelsLabelingScreen
import com.augt.localseek.ui.qrels.QrelsSessionListScreen
import com.augt.localseek.ui.qrels.QrelsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

internal enum class AppRoute {
    SEARCH,
    SETTINGS,
    SETTINGS_SEARCH,
    SETTINGS_APPEARANCE,
    SETTINGS_WEB,
    SETTINGS_INDEXING,
    SETTINGS_PRIVACY,
    SETTINGS_BACKUP,
    SETTINGS_DEVELOPER,
    ABOUT,
    PERFORMANCE,
    QRELS_LIST,
    QRELS_LABELING;

    /** Where system back (and the predictive back gesture) leads from here. */
    val parent: AppRoute
        get() = when (this) {
            SEARCH -> SEARCH
            SETTINGS -> SEARCH
            QRELS_LABELING -> QRELS_LIST
            PERFORMANCE, QRELS_LIST -> SETTINGS_DEVELOPER
            else -> SETTINGS
        }

    /** Number of back steps to the search screen; drives the slide direction between screens. */
    val depth: Int
        get() = if (this == SEARCH) 0 else parent.depth + 1
}

@Composable
fun SearchApp(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
    onboardingPermissions: OnboardingPermissions = OnboardingPermissions.None
) {
    var route by rememberSaveable { mutableStateOf(AppRoute.SEARCH) }
    val qrelsViewModel: QrelsViewModel = viewModel()
    val context = LocalContext.current
    val toolsRepository = remember { ToolsRepository(context.applicationContext) }
    val oneHanded by toolsRepository.oneHandedMode.collectAsState(initial = false)
    val animate = rememberAnimationsEnabled()

    // null while the flag is still loading, so neither the tour nor the search screen flashes.
    val onboardingCompleted by toolsRepository.onboardingCompleted.collectAsState(initial = null as Boolean?)
    var replayOnboarding by rememberSaveable { mutableStateOf(false) }
    val onboardingViewModel: OnboardingViewModel = viewModel()

    // System back and the predictive back gesture step up one level; the screen shrinks slightly while the gesture is held.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = route != AppRoute.SEARCH) { progress ->
        try {
            progress.collect { event -> backProgress = event.progress }
            route = route.parent
        } catch (e: CancellationException) {
            // gesture cancelled: stay on this screen
        } finally {
            backProgress = 0f
        }
    }

    // Each screen owns its Scaffold and window insets, so there is no outer Scaffold here.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (onboardingCompleted == null) return@Surface
        if (onboardingCompleted == false || replayOnboarding) {
            OnboardingScreen(
                permissions = onboardingPermissions,
                onFinished = { replayOnboarding = false },
                viewModel = onboardingViewModel
            )
            return@Surface
        }
        val goBack = { route = route.parent }
        AnimatedContent(
            targetState = route,
            modifier = Modifier.graphicsLayer {
                if (animate) {
                    val s = 1f - 0.08f * backProgress
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.3f * backProgress
                }
            },
            transitionSpec = {
                if (!animate) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (targetState.depth >= initialState.depth) {
                    (slideInHorizontally(ExpressiveMotion.layout()) { it / 5 } + fadeIn()) togetherWith
                        (slideOutHorizontally(ExpressiveMotion.layout()) { -it / 8 } + fadeOut())
                } else {
                    (slideInHorizontally(ExpressiveMotion.layout()) { -it / 8 } + fadeIn()) togetherWith
                        (slideOutHorizontally(ExpressiveMotion.layout()) { it / 5 } + fadeOut())
                }
            },
            label = "route"
        ) { current ->
            when (current) {
                AppRoute.SEARCH -> SearchScreen(
                    viewModel = viewModel,
                    oneHandedMode = oneHanded,
                    onNavigateToSettings = { route = AppRoute.SETTINGS },
                    onNavigateToIndexing = { route = AppRoute.SETTINGS_INDEXING }
                )

                AppRoute.SETTINGS -> SettingsHome(
                    onNavigateBack = goBack,
                    onNavigate = { dest ->
                        route = when (dest) {
                            SettingsDestination.SEARCH -> AppRoute.SETTINGS_SEARCH
                            SettingsDestination.APPEARANCE -> AppRoute.SETTINGS_APPEARANCE
                            SettingsDestination.WEB -> AppRoute.SETTINGS_WEB
                            SettingsDestination.INDEXING -> AppRoute.SETTINGS_INDEXING
                            SettingsDestination.PRIVACY -> AppRoute.SETTINGS_PRIVACY
                            SettingsDestination.BACKUP -> AppRoute.SETTINGS_BACKUP
                            SettingsDestination.ABOUT -> AppRoute.ABOUT
                            SettingsDestination.DEVELOPER -> AppRoute.SETTINGS_DEVELOPER
                        }
                    },
                    onReplayOnboarding = {
                        onboardingViewModel.restart()
                        replayOnboarding = true
                        route = AppRoute.SEARCH
                    }
                )

                AppRoute.SETTINGS_SEARCH -> SearchSettingsPage(onBack = goBack)
                AppRoute.SETTINGS_APPEARANCE -> AppearancePage(onBack = goBack)
                AppRoute.SETTINGS_WEB -> WebSettingsPage(onBack = goBack)
                AppRoute.SETTINGS_INDEXING -> IndexingPage(
                    onBack = goBack,
                    onNavigateToPerformance = { if (BuildConfig.DEBUG) route = AppRoute.PERFORMANCE }
                )
                AppRoute.SETTINGS_PRIVACY -> PrivacyPage(onBack = goBack)
                AppRoute.SETTINGS_BACKUP -> BackupPage(onBack = goBack)
                AppRoute.SETTINGS_DEVELOPER -> DeveloperPage(
                    onBack = goBack,
                    onNavigateToPerformance = { if (BuildConfig.DEBUG) route = AppRoute.PERFORMANCE },
                    onNavigateToQrels = { if (BuildConfig.DEBUG) route = AppRoute.QRELS_LIST }
                )

                AppRoute.ABOUT -> AboutScreen(onNavigateBack = goBack)

                AppRoute.PERFORMANCE -> if (BuildConfig.DEBUG) {
                    PerformanceDashboard(onNavigateBack = goBack)
                } else {
                    route = AppRoute.SETTINGS
                }

                AppRoute.QRELS_LIST -> if (BuildConfig.DEBUG) {
                    QrelsSessionListScreen(
                        viewModel = qrelsViewModel,
                        onNavigateBack = goBack,
                        onSessionClick = { session ->
                            qrelsViewModel.selectSession(session)
                            route = AppRoute.QRELS_LABELING
                        }
                    )
                } else {
                    route = AppRoute.SETTINGS
                }

                AppRoute.QRELS_LABELING -> if (BuildConfig.DEBUG) {
                    QrelsLabelingScreen(viewModel = qrelsViewModel, onNavigateBack = goBack)
                } else {
                    route = AppRoute.SETTINGS
                }
            }
        }
    }
}
