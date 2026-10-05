package com.augt.localseek.ui.about

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.augt.localseek.BuildConfig
import com.augt.localseek.R
import com.augt.localseek.ui.components.LsButton
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.tools.ToolsRepository
import com.augt.localseek.tools.UiPrefs
import com.augt.localseek.ui.WebLauncher
import com.augt.localseek.ui.mascot.HazelCanvas
import com.augt.localseek.ui.mascot.HazelMood
import com.augt.localseek.ui.theme.LocalSeekTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/greenaltambe/LocalSeek"
private const val ISSUES_URL = "$REPO_URL/issues"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val notices by produceState<List<NoticeSection>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            try {
                resources.openRawResource(R.raw.third_party_notices)
                    .bufferedReader().use { NoticeParser.parse(it.readText()) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val tools = remember { ToolsRepository(context.applicationContext) }
    val uiPrefs by tools.uiPrefs.collectAsState(initial = UiPrefs())
    val scope = rememberCoroutineScope()
    val unlock = remember { DeveloperUnlock() }
    val unlockedToast = stringResource(R.string.dev_unlocked_toast)
    val alreadyToast = stringResource(R.string.dev_already_unlocked_toast)
    AboutContent(
        mascot = uiPrefs.mascot,
        onVersionTapped = {
            if (unlock.onTap(System.currentTimeMillis())) {
                if (BuildConfig.DEBUG || uiPrefs.developerUnlocked) {
                    Toast.makeText(context, alreadyToast, Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch { tools.updateUiPrefs { copy(developerUnlocked = true) } }
                    Toast.makeText(context, unlockedToast, Toast.LENGTH_LONG).show()
                }
            }
        },
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        gitSha = BuildConfig.GIT_SHA.take(7),
        notices = notices,
        onNavigateBack = onNavigateBack,
        onOpenAppInfo = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            try { context.startActivity(intent) } catch (_: Exception) { /* no settings app: nothing to do */ }
        },
        onOpenUrl = { url ->
            if (!WebLauncher.open(context, url)) Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AboutContent(
    versionName: String,
    versionCode: Int,
    gitSha: String,
    notices: List<NoticeSection>?,
    onNavigateBack: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    mascot: Boolean = false,
    onVersionTapped: () -> Unit = {}
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (mascot) HazelCanvas(HazelMood.HAPPY, size = 88.dp)
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                        Text(
                            stringResource(R.string.about_version, versionName, versionCode),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onVersionTapped)
                        )
                        Text(
                            stringResource(R.string.about_build, gitSha),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.about_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                  }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(
                                    stringResource(R.string.about_no_internet_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.semantics { heading() }
                                )
                            }
                            Text(
                                stringResource(R.string.about_no_internet_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            LsOutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.about_open_app_info))
                            }
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.about_privacy_title)) {
                        listOf(
                            R.string.about_privacy_1, R.string.about_privacy_2, R.string.about_privacy_3,
                            R.string.about_privacy_4, R.string.about_privacy_5
                        ).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.about_tech_title)) {
                        Tech("BM25 / FTS5", "Keyword search")
                        Tech("MiniLM", "Dense embeddings")
                        Tech("Exact vector search", "Binary shortlist above 50k chunks")
                        Tech("Cross-encoder", "Optional reranking (experimental, off by default)")
                        Tech("CLIP", "Optional image search")
                    }
                }
                item {
                    Text(
                        stringResource(R.string.about_licenses_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() }
                    )
                }
                when {
                    notices == null -> item { Text(stringResource(R.string.about_licenses_loading)) }
                    notices.isEmpty() -> item { Text(stringResource(R.string.about_licenses_unavailable)) }
                    else -> items(notices, key = { it.title }) { NoticeCard(it) }
                }
                item {
                    SectionCard(title = stringResource(R.string.about_links_title)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LsButton(onClick = { onOpenUrl(REPO_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.about_link_source), modifier = Modifier.padding(start = 8.dp))
                            }
                            LsOutlinedButton(onClick = { onOpenUrl(ISSUES_URL) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.about_link_issues), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        Text(
                            stringResource(R.string.about_links_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun Tech(name: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoticeCard(section: NoticeSection) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(section.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Text(section.body, style = MaterialTheme.typography.bodyMedium)
                section.verbatim.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (section.body.lines().count { it.isNotBlank() } > 1 || section.verbatim.isNotEmpty()) {
                LsTextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (expanded) R.string.about_licenses_collapse else R.string.about_licenses_expand))
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "About")
@Composable
private fun AboutPreview() {
    LocalSeekTheme {
        AboutContent(
            versionName = "1.0", versionCode = 1, gitSha = "abc1234",
            notices = listOf(
                NoticeSection("CLIP ViT-B/32 On-Device Encoders", "Source: OpenAI CLIP\nLicense: MIT License", listOf("The MIT License (MIT) ...")),
                NoticeSection("Other Libraries", "• AndroidX: Apache License 2.0", emptyList())
            ),
            onNavigateBack = {}, onOpenAppInfo = {}, onOpenUrl = {}
        )
    }
}

@Preview(showBackground = true, fontScale = 2f, name = "About, font scale 200%")
@Composable
private fun AboutLargeFontPreview() {
    LocalSeekTheme {
        AboutContent(
            versionName = "1.0", versionCode = 1, gitSha = "abc1234", notices = emptyList(),
            onNavigateBack = {}, onOpenAppInfo = {}, onOpenUrl = {}
        )
    }
}
