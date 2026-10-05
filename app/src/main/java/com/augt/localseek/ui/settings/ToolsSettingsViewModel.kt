package com.augt.localseek.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.augt.localseek.tools.Alias
import com.augt.localseek.tools.Aliases
import android.net.Uri
import com.augt.localseek.tools.BackupParseResult
import com.augt.localseek.tools.SettingsBackup
import com.augt.localseek.tools.ThemeSettings
import com.augt.localseek.tools.UiPrefs
import com.augt.localseek.tools.ToolsRepository
import com.augt.localseek.tools.WebEngine
import com.augt.localseek.tools.WebEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the "Search shortcuts" (and later appearance / pins / backup) sections of the settings screen. */
class ToolsSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ToolsRepository(application)

    val aliases: StateFlow<List<Alias>> =
        repository.aliases.stateIn(viewModelScope, SharingStarted.Eagerly, Aliases.DEFAULTS)

    val engines: StateFlow<List<WebEngine>> =
        repository.engines.stateIn(viewModelScope, SharingStarted.Eagerly, WebEngines.DEFAULTS)

    val theme: StateFlow<ThemeSettings> =
        repository.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettings())

    val uiPrefs: StateFlow<UiPrefs> =
        repository.uiPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, UiPrefs())

    fun updateUiPrefs(transform: UiPrefs.() -> UiPrefs) {
        viewModelScope.launch { repository.updateUiPrefs(transform) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { repository.clearRecentSearches() }
    }

    val oneHandedMode: StateFlow<Boolean> =
        repository.oneHandedMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setOneHandedMode(enabled: Boolean) {
        viewModelScope.launch { repository.setOneHandedMode(enabled) }
    }

    fun updateTheme(transform: ThemeSettings.() -> ThemeSettings) {
        viewModelScope.launch { repository.setTheme(theme.value.transform()) }
    }

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun dismissBackupMessage() { _backupMessage.value = null }

    /** Writes a settings-only JSON backup to a Storage Access Framework [uri] (from CreateDocument). */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _backupMessage.value = try {
                val json = SettingsBackup.export(repository.snapshot())
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the file for writing")
                }
                "Settings exported"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    /** Reads, validates and applies a backup from a SAF [uri] (from OpenDocument). Nothing is changed if invalid. */
    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _backupMessage.value = try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        // read at most MAX_BYTES + 1 so an oversized file is rejected without loading all of it
                        val buf = ByteArray(SettingsBackup.MAX_BYTES + 1)
                        var n = 0
                        while (n < buf.size) {
                            val r = input.read(buf, n, buf.size - n)
                            if (r < 0) break
                            n += r
                        }
                        String(buf, 0, n, Charsets.UTF_8)
                    } ?: error("Could not open the file")
                }
                when (val parsed = SettingsBackup.parse(text)) {
                    is BackupParseResult.Invalid -> "Import failed: ${parsed.reason}"
                    is BackupParseResult.Ok -> {
                        val warnings = parsed.warnings + repository.apply(parsed.snapshot)
                        if (warnings.isEmpty()) "Settings imported" else "Settings imported (${warnings.joinToString("; ")})"
                    }
                }
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
        }
    }

    /** Returns false if the input is invalid (blank name or template without %s / not http(s)). */
    fun addEngine(name: String, template: String, iconKey: String? = null): Boolean {
        val engine = WebEngines.custom(name, template, engines.value, iconKey) ?: return false
        viewModelScope.launch { repository.setEngines(engines.value + engine) }
        return true
    }

    /** Stores the icon key on an existing engine (default engines included). */
    fun setEngineIcon(id: String, iconKey: String) {
        viewModelScope.launch {
            repository.setEngines(engines.value.map { if (it.id == id) it.copy(iconKey = iconKey) else it })
        }
    }

    fun removeEngine(id: String) {
        viewModelScope.launch {
            repository.setEngines(engines.value.filterNot { it.id == id })
            // aliases pointing to a removed engine would be dead; drop them too
            repository.setAliases(aliases.value.filterNot { it.target == id })
        }
    }

    fun addAlias(trigger: String, target: String): Boolean {
        val t = trigger.trim()
        if (!Aliases.isValidTrigger(t, aliases.value)) return false
        val known = target == Alias.CALCULATOR || target in Alias.SCOPE_TARGETS || engines.value.any { it.id == target }
        if (!known) return false
        viewModelScope.launch { repository.setAliases(aliases.value + Alias(t, target)) }
        return true
    }

    fun removeAlias(trigger: String) {
        viewModelScope.launch { repository.setAliases(aliases.value.filterNot { it.trigger == trigger }) }
    }

    fun restoreShortcutDefaults() {
        viewModelScope.launch { repository.resetShortcuts() }
    }
}
