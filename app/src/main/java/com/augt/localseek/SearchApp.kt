package com.augt.localseek

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.augt.localseek.ui.common.ErrorBoundary
import com.augt.localseek.ui.performance.PerformanceDashboard
import com.augt.localseek.ui.settings.SettingsScreen
import com.augt.localseek.ui.SearchScreen
import com.augt.localseek.ui.SearchViewModel
import com.augt.localseek.ui.qrels.QrelsLabelingScreen
import com.augt.localseek.ui.qrels.QrelsSessionListScreen
import com.augt.localseek.ui.qrels.QrelsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class AppRoute {
    SEARCH,
    SETTINGS,
    PERFORMANCE,
    QRELS_LIST,
    QRELS_LABELING
}

@Composable
fun SearchApp(viewModel: SearchViewModel, modifier: Modifier = Modifier) {
    var route by rememberSaveable { mutableStateOf(AppRoute.SEARCH) }
    val qrelsViewModel: QrelsViewModel = viewModel()

    Scaffold(modifier = modifier) { innerPadding ->
        ErrorBoundary {
            when (route) {
                AppRoute.SEARCH -> SearchScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding),
                    onNavigateToSettings = { route = AppRoute.SETTINGS }
                )

                AppRoute.SETTINGS -> SettingsScreen(
                    onNavigateBack = { route = AppRoute.SEARCH },
                    onNavigateToPerformance = { route = AppRoute.PERFORMANCE },
                    onNavigateToQrels = { route = AppRoute.QRELS_LIST }
                )

                AppRoute.PERFORMANCE -> PerformanceDashboard(
                    onNavigateBack = { route = AppRoute.SETTINGS }
                )

                AppRoute.QRELS_LIST -> QrelsSessionListScreen(
                    viewModel = qrelsViewModel,
                    onNavigateBack = { route = AppRoute.SETTINGS },
                    onSessionClick = { session ->
                        qrelsViewModel.selectSession(session)
                        route = AppRoute.QRELS_LABELING
                    }
                )

                AppRoute.QRELS_LABELING -> QrelsLabelingScreen(
                    viewModel = qrelsViewModel,
                    onNavigateBack = { route = AppRoute.QRELS_LIST }
                )
            }
        }
    }
}