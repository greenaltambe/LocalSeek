package com.augt.localseek.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.notify.IndexProgress
import com.augt.localseek.notify.IndexProgressText
import com.augt.localseek.ui.theme.ExpressiveMotion
import com.augt.localseek.ui.theme.LocalSeekTheme
import com.augt.localseek.ui.theme.rememberAnimationsEnabled

/** What the slim banner under the app bar shows. */
sealed interface IndexBannerState {
    data object Hidden : IndexBannerState
    /** [progress] is null until the worker published its first numbers. */
    data class Running(val progress: IndexProgress?) : IndexBannerState
    /** Waiting to start (also: a timed-out run whose resume is queued). */
    data object Queued : IndexBannerState
    /** Shown for [IndexBanner.UP_TO_DATE_MS], then the UI sends a dismiss. [files] is the last known count. */
    data class UpToDate(val files: Int?) : IndexBannerState
    data object Failed : IndexBannerState
}

/** What WorkManager currently says about indexing work. */
sealed interface IndexObservation {
    data class Running(val progress: IndexProgress?) : IndexObservation
    data object Queued : IndexObservation
    /** Nothing running or queued. [hadFailure] / [hadSuccess] come from finished work still in WorkManager's history. */
    data class Idle(val hadFailure: Boolean, val hadSuccess: Boolean) : IndexObservation
}

/** Pure state machine for the indexing banner: running -> up to date -> hidden, and failure -> resume. */
object IndexBanner {

    const val UP_TO_DATE_MS = 3_000L

    fun observe(works: List<IndexWorkSnapshot>): IndexObservation {
        works.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { return IndexObservation.Running(it.progress) }
        if (works.any { it.state == WorkInfo.State.ENQUEUED && IndexingStatusMapper.TAG_ONE_TIME in it.tags }) {
            return IndexObservation.Queued
        }
        return IndexObservation.Idle(
            hadFailure = works.any { it.state == WorkInfo.State.FAILED },
            hadSuccess = works.any { it.state == WorkInfo.State.SUCCEEDED }
        )
    }

    /**
     * Next banner state. A finished run turns the banner into "up to date" only when it was Running before, so a
     * fresh app start with old successful work in history stays hidden. When the history holds both a failure and a
     * success it cannot be ordered (WorkManager exposes no finish time), so success wins after a run; a lone failure
     * is shown with "Resume", also on app start.
     */
    fun reduce(previous: IndexBannerState, observation: IndexObservation): IndexBannerState = when (observation) {
        is IndexObservation.Running -> IndexBannerState.Running(observation.progress)
        IndexObservation.Queued -> IndexBannerState.Queued
        is IndexObservation.Idle -> when {
            previous is IndexBannerState.Running ->
                if (observation.hadFailure && !observation.hadSuccess) IndexBannerState.Failed
                else IndexBannerState.UpToDate(previous.progress?.shownDone)
            previous is IndexBannerState.UpToDate -> previous
            observation.hadFailure && !observation.hadSuccess -> IndexBannerState.Failed
            previous is IndexBannerState.Failed && observation.hadFailure -> previous
            else -> IndexBannerState.Hidden
        }
    }

    /** The 3 s "up to date" timer fired (or the user tapped it away). Other states are unaffected. */
    fun dismissUpToDate(state: IndexBannerState): IndexBannerState =
        if (state is IndexBannerState.UpToDate) IndexBannerState.Hidden else state

    fun isVisible(state: IndexBannerState): Boolean = state != IndexBannerState.Hidden
}

/**
 * Slim banner pinned directly under the top app bar. Tapping it opens Settings > Indexing; failure and queued states
 * carry an action button. Slides in and out (just fades when animations are removed).
 */
@Composable
fun IndexBannerView(
    state: IndexBannerState,
    mascot: Boolean,
    onOpenIndexing: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animate = rememberAnimationsEnabled()
    AnimatedVisibility(
        visible = IndexBanner.isVisible(state),
        modifier = modifier,
        enter = if (animate) expandVertically(ExpressiveMotion.layout()) + fadeIn() else fadeIn(),
        exit = if (animate) shrinkVertically(ExpressiveMotion.layout()) + fadeOut() else fadeOut()
    ) {
        // keep drawing the last visible content while the exit animation runs
        BannerContent(state, mascot, onOpenIndexing, onResume)
    }
}

@Composable
private fun BannerContent(state: IndexBannerState, mascot: Boolean, onOpenIndexing: () -> Unit, onResume: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = when (state) {
        is IndexBannerState.Failed -> scheme.errorContainer
        is IndexBannerState.UpToDate -> scheme.tertiaryContainer
        else -> scheme.secondaryContainer
    }
    val content = when (state) {
        is IndexBannerState.Failed -> scheme.onErrorContainer
        is IndexBannerState.UpToDate -> scheme.onTertiaryContainer
        else -> scheme.onSecondaryContainer
    }
    val openLabel = stringResource(R.string.banner_open_indexing)
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.clickable(onClickLabel = openLabel, onClick = onOpenIndexing)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BannerIcon(state, mascot)
                Text(
                    text = bannerText(state, mascot),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                when (state) {
                    IndexBannerState.Failed -> LsTextButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.banner_resume))
                    }
                    IndexBannerState.Queued -> LsTextButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.banner_run_now))
                    }
                    else -> Unit
                }
            }
            if (state is IndexBannerState.Running) {
                val fraction = state.progress?.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun BannerIcon(state: IndexBannerState, mascot: Boolean) {
    val modifier = Modifier.size(20.dp)
    when (state) {
        is IndexBannerState.UpToDate -> Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = modifier)
        IndexBannerState.Failed -> Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = modifier)
        IndexBannerState.Queued -> Icon(Icons.Default.Schedule, contentDescription = null, modifier = modifier)
        else -> if (mascot) {
            Icon(painterResource(R.drawable.ic_stat_acorn), contentDescription = null, modifier = modifier)
        } else {
            Icon(Icons.Default.Schedule, contentDescription = null, modifier = modifier)
        }
    }
}

@Composable
private fun bannerText(state: IndexBannerState, mascot: Boolean): String = when (state) {
    is IndexBannerState.Running -> {
        val p = state.progress
        when {
            mascot && p?.percent != null -> stringResource(R.string.banner_indexing_playful, p.percent!!)
            mascot -> stringResource(R.string.banner_indexing_playful_unknown)
            p == null -> stringResource(R.string.banner_indexing_plain)
            else -> {
                val (done, total) = IndexProgressText.counts(p)
                if (total != null) stringResource(R.string.banner_indexing_counts, done, total)
                else stringResource(R.string.banner_indexing_so_far, done)
            }
        }
    }
    IndexBannerState.Queued -> stringResource(R.string.banner_queued)
    is IndexBannerState.UpToDate -> if (mascot && state.files != null) {
        stringResource(R.string.banner_up_to_date_playful, IndexProgressText.count(state.files))
    } else stringResource(R.string.banner_up_to_date)
    IndexBannerState.Failed -> stringResource(R.string.banner_failed)
    IndexBannerState.Hidden -> ""
}

@Preview(showBackground = true)
@Composable
private fun IndexBannerPreview() {
    LocalSeekTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BannerContent(IndexBannerState.Running(IndexProgress(4200, 6000)), mascot = false, onOpenIndexing = {}, onResume = {})
            BannerContent(IndexBannerState.UpToDate(2615), mascot = true, onOpenIndexing = {}, onResume = {})
            BannerContent(IndexBannerState.Failed, mascot = false, onOpenIndexing = {}, onResume = {})
        }
    }
}

@Preview(showBackground = true, fontScale = 2f, name = "Banner, font scale 200%")
@Composable
private fun IndexBannerLargeFontPreview() {
    LocalSeekTheme {
        BannerContent(IndexBannerState.Running(IndexProgress(4200, 6000)), mascot = false, onOpenIndexing = {}, onResume = {})
    }
}
