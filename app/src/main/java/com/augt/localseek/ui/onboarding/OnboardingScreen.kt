package com.augt.localseek.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsButton
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.ml.clip.ClipAssetPackManager
import com.augt.localseek.ml.clip.ClipPackState
import com.augt.localseek.ui.mascot.HazelCanvas
import com.augt.localseek.ui.mascot.HazelMood
import com.augt.localseek.ui.theme.LocalSeekTheme
import com.augt.localseek.ui.theme.rememberPressMorph

/** First-run tour. [onFinished] is called once when the user finishes or skips. */
@Composable
fun OnboardingScreen(
    permissions: OnboardingPermissions,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val packState by viewModel.packState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val mascot by remember { com.augt.localseek.tools.ToolsRepository(context.applicationContext).uiPrefs }
        .collectAsStateWithLifecycle(initialValue = com.augt.localseek.tools.UiPrefs())

    // Permission dialogs and the all-files settings page pause the activity; re-read grants on return.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

    if (state.finished) {
        // Finished or skipped: persist, hand over to the activity (starts indexing), leave.
        LaunchedEffect(Unit) {
            viewModel.markCompleted()
            permissions.onOnboardingFinished()
            onFinished()
        }
    }

    BackHandler(enabled = state.canGoBack && !state.finished) { viewModel.back() }

    OnboardingContent(
        state = state,
        packState = packState,
        isGranted = { step -> resumeTick >= 0 && permissions.isGranted(step) },
        onRequest = permissions::request,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onSkip = viewModel::skipAll,
        onDownloadPack = viewModel::downloadPack,
        onCancelPack = viewModel::cancelPackDownload,
        mascot = mascot.mascot,
        modifier = modifier
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingState,
    packState: ClipPackState,
    isGranted: (PermissionStep) -> Boolean,
    onRequest: (PermissionStep) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onDownloadPack: () -> Unit,
    onCancelPack: () -> Unit,
    modifier: Modifier = Modifier,
    mascot: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.weight(1f)
                )
                LsTextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }

            AnimatedContent(
                targetState = state.page to state.permissionIndex,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                },
                label = "onboardingPage"
            ) { (page, _) ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (page) {
                        OnboardingPage.WELCOME -> InfoPage(
                            icon = Icons.Default.Lock,
                            mascot = mascot,
                            title = R.string.onboarding_welcome_title,
                            body = R.string.onboarding_welcome_body,
                            bullets = listOf(
                                R.string.onboarding_welcome_b1,
                                R.string.onboarding_welcome_b2,
                                R.string.onboarding_welcome_b3,
                                R.string.onboarding_welcome_b4
                            )
                        )
                        OnboardingPage.SEARCHABLE -> InfoPage(
                            icon = Icons.Default.Search,
                            title = R.string.onboarding_what_title,
                            body = R.string.onboarding_what_body,
                            bullets = listOf(
                                R.string.onboarding_what_apps,
                                R.string.onboarding_what_contacts,
                                R.string.onboarding_what_files,
                                R.string.onboarding_what_images
                            )
                        )
                        OnboardingPage.PERMISSIONS -> state.currentPermission?.let { step ->
                            PermissionPage(
                                step = step,
                                position = state.permissionIndex + 1,
                                total = state.permissionSteps.size,
                                granted = isGranted(step),
                                onAllow = { onRequest(step) },
                                onContinue = onNext
                            )
                        }
                        OnboardingPage.IMAGE_PACK -> PackPage(
                            packState = packState,
                            onDownload = onDownloadPack,
                            onCancel = onCancelPack
                        )
                    }
                }
            }

            // The permissions page has its own Allow / Not now buttons, so the bar only offers Back there.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.canGoBack) {
                    LsTextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                if (state.page != OnboardingPage.PERMISSIONS) {
                    val morph = rememberPressMorph()
                    LsButton(
                        onClick = onNext,
                        shape = morph.shape,
                        interactionSource = morph.interactionSource,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(if (state.isLastStep) R.string.onboarding_finish else R.string.onboarding_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun PageTitle(@StringRes title: Int, text: String = stringResource(title)) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 24.dp).semantics { heading() }
    )
}

@Composable
private fun InfoPage(icon: ImageVector, @StringRes title: Int, @StringRes body: Int, bullets: List<Int>, mascot: Boolean = false) {
    if (mascot) HazelCanvas(HazelMood.HAPPY, size = 120.dp) else PageIcon(icon)
    PageTitle(title)
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )
    Column(modifier = Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        bullets.forEach { res ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class PermissionCopy(val icon: ImageVector, @StringRes val title: Int, @StringRes val why: Int)

private fun copyFor(step: PermissionStep) = when (step) {
    PermissionStep.CONTACTS -> PermissionCopy(Icons.Default.Contacts, R.string.onboarding_perm_contacts_title, R.string.onboarding_perm_contacts_why)
    PermissionStep.FILES -> PermissionCopy(Icons.Default.FolderOpen, R.string.onboarding_perm_files_title, R.string.onboarding_perm_files_why)
    PermissionStep.PHOTOS -> PermissionCopy(Icons.Default.Image, R.string.onboarding_perm_photos_title, R.string.onboarding_perm_photos_why)
}

@Composable
private fun PermissionPage(
    step: PermissionStep,
    position: Int,
    total: Int,
    granted: Boolean,
    onAllow: () -> Unit,
    onContinue: () -> Unit
) {
    val copy = copyFor(step)
    PageIcon(copy.icon)
    Text(
        text = stringResource(R.string.onboarding_perm_step, position, total),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp)
    )
    PageTitle(copy.title)
    Card(
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(copy.why), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.onboarding_perm_optional),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Column(
        modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.onboarding_perm_granted), style = MaterialTheme.typography.titleMedium)
            }
            LsButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_next))
            }
        } else {
            LsButton(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_perm_allow))
            }
            LsOutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_perm_not_now))
            }
        }
    }
}

@Composable
private fun PackPage(packState: ClipPackState, onDownload: () -> Unit, onCancel: () -> Unit) {
    PageIcon(Icons.Default.Image)
    PageTitle(R.string.onboarding_pack_title)
    Text(
        text = stringResource(R.string.onboarding_pack_body, ClipAssetPackManager.PACK_APPROX_SIZE_MB),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )
    Card(
        modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (packState) {
                is ClipPackState.Ready -> Text(stringResource(R.string.onboarding_pack_ready), style = MaterialTheme.typography.titleMedium)
                is ClipPackState.Downloading -> {
                    Text(stringResource(R.string.onboarding_pack_downloading, packState.progressPercent), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { (packState.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    LsTextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                is ClipPackState.Failed -> {
                    Text(stringResource(R.string.onboarding_pack_failed), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    LsButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
                }
                is ClipPackState.WaitingForWifi, ClipPackState.RequiresConfirmation -> {
                    Text(stringResource(R.string.onboarding_pack_waiting), style = MaterialTheme.typography.bodyMedium)
                    LsButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_pack_proceed)) }
                }
                is ClipPackState.LowStorage -> Text(stringResource(R.string.onboarding_pack_low_storage), style = MaterialTheme.typography.bodyMedium)
                is ClipPackState.NotInstalled -> {
                    Text(stringResource(R.string.onboarding_pack_wifi), style = MaterialTheme.typography.bodyMedium)
                    LsButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onboarding_pack_download, ClipAssetPackManager.PACK_APPROX_SIZE_MB))
                    }
                }
            }
            Text(
                stringResource(R.string.onboarding_pack_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true, name = "Welcome")
@Composable
private fun OnboardingWelcomePreview() {
    LocalSeekTheme {
        OnboardingContent(
            state = OnboardingState.create(true), packState = ClipPackState.NotInstalled,
            isGranted = { false }, onRequest = {}, onNext = {}, onBack = {}, onSkip = {},
            onDownloadPack = {}, onCancelPack = {}
        )
    }
}

@Preview(showBackground = true, name = "Permission step, font scale 200%", fontScale = 2f)
@Composable
private fun OnboardingPermissionPreview() {
    LocalSeekTheme {
        OnboardingContent(
            state = OnboardingState.create(true).next().next(), packState = ClipPackState.NotInstalled,
            isGranted = { false }, onRequest = {}, onNext = {}, onBack = {}, onSkip = {},
            onDownloadPack = {}, onCancelPack = {}
        )
    }
}

@Preview(showBackground = true, name = "Image pack")
@Composable
private fun OnboardingPackPreview() {
    var s = OnboardingState.create(true)
    repeat(5) { s = s.next() }
    LocalSeekTheme {
        OnboardingContent(
            state = s, packState = ClipPackState.Downloading(40, 100, 289),
            isGranted = { false }, onRequest = {}, onNext = {}, onBack = {}, onSkip = {},
            onDownloadPack = {}, onCancelPack = {}
        )
    }
}
