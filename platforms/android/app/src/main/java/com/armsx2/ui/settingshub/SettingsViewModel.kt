package com.armsx2.ui.settingshub

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsx2.DiscIdentity
import com.armsx2.EmuState
import com.armsx2.GameInfo
import com.armsx2.config.ConfigStore
import com.armsx2.config.Settings
import com.armsx2.config.SettingsScope
import com.armsx2.navigation.SettingsCategory
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.InGameOverlay
import kotlinx.coroutines.launch

data class SettingsUiState(
    val category: SettingsCategory = SettingsCategory.General,
    val game: GameInfo? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    var uiState = androidx.compose.runtime.mutableStateOf(SettingsUiState())
        private set

    val settings get() = InGameOverlay.settingsState

    fun load(category: SettingsCategory, game: GameInfo?) {
        val serial = game?.settingsKey
        InGameOverlay.currentSerial.value = serial
        // The CRC is half the name of gamesettings/<serial>_<CRC>.ini, and that file is the ONLY
        // thing ComputePerGameOverrides reads — so without it a per-game choice made from the
        // library never pins against the GameDB (TASK-0089). Resolved here rather than at save
        // time for two reasons: DiscIdentity reads the disc's boot ELF and documents itself as
        // "never call from the main thread", and the save path runs its job ON the main thread
        // (SettingsApplyQueue). The serial guard drops a slow result that lands after the user has
        // moved to a different game.
        InGameOverlay.currentCrc.value = null
        if (game != null && serial != null) {
            viewModelScope.launch {
                val resolved = DiscIdentity.resolve(game.uri, serial)
                if (InGameOverlay.currentSerial.value == serial) InGameOverlay.currentCrc.value = resolved
            }
        }
        InGameOverlay.settingsScope.value = if (serial == null) SettingsScope.Global else SettingsScope.Game
        settings.value = if (serial == null) ConfigStore.loadGlobal() else ConfigStore.resolveForGame(serial)
        uiState.value = SettingsUiState(
            category = if (game != null && category == SettingsCategory.General) SettingsCategory.Performance else category,
            game = game,
        )
    }

    fun selectCategory(category: SettingsCategory) {
        // Nav tick when flipping to a different settings tab (controller bumpers, tap, or search jump).
        if (category != uiState.value.category) com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.NAV)
        uiState.value = uiState.value.copy(category = category)
    }

    /**
     * Reset ONLY the tab currently being shown.
     *
     * This used to reset the entire scope — `Settings()` globally, or clearing the whole
     * per-game override blob — so pressing Reset on the Renderer page also wiped Audio,
     * Network, Performance and Fixes. (Controller settings survived only because they live
     * in ControllerMappings, not because Reset was scoped.) Categories that own no Settings
     * fields are a no-op; Controls keeps its own reset row for binds/tunables.
     */
    fun resetCurrentScope(category: SettingsCategory) {
        // A settings edit may still be coalesced in SettingsApplyQueue (TASK-0084), and this
        // method writes the SAME native/INI state from the UI thread. Landing the pending job
        // first keeps the two in the order the user performed them — otherwise an edit made just
        // before Reset would apply AFTER it and quietly undo the reset.
        com.armsx2.config.SettingsApplyQueue.flush()
        // Takes the category the SCREEN is showing, not uiState.category: the screen remaps
        // General -> Performance under a game scope, so reading it here would reset the wrong
        // (or no) tab.
        if (!categoryHasResettableSettings(category)) return
        val serial = uiState.value.game?.settingsKey
        if (serial != null) {
            // Per-game: drop just this tab's override keys, so those settings fall back to
            // global while every other per-game tweak the user made is preserved.
            ConfigStore.loadOverrides(serial)?.let { overrides ->
                val pruned = pruneOverrides(overrides, categoryOverrideKeys(category))
                if (pruned == null) ConfigStore.clearOverrides(serial)
                else ConfigStore.saveOverrides(serial, pruned)
            }
            settings.value = ConfigStore.resolveForGame(serial)
        } else {
            settings.value = settings.value.resetCategory(category)
            ConfigStore.saveGlobal(settings.value)
        }
        // Push the reset into the emulator's native config + per-game INI. Pruning the override
        // JSON above only updates the on-screen values and the store; the native per-game INI
        // (gamesettings/<serial>_<CRC>.ini) still holds the pruned keys, and VMManager reloads the
        // game layer from it on every commit/boot so the stale keys keep winning — which is why
        // Reset "did nothing". Rewriting that INI clears the tab's keys while preserving the
        // [Patches]/[Cheats] enable lists.
        val running = MainActivityRuntime.nativeReady.value &&
            MainActivityRuntime.eState.value != EmuState.STOPPED
        val gameSerial = serial?.takeIf { it.isNotBlank() }
        runCatching {
            when {
                // In-game: re-apply live so the change shows immediately, then regenerate the
                // running game's INI for the next boot (mirrors InGameOverlay.saveSettings).
                gameSerial != null && running -> {
                    settings.value.applyTo()
                    ConfigStore.resolveForGame(gameSerial).writeGameSettingsIni(ConfigStore.loadGlobal())
                }
                // From the library (no VM): the INI can't be reached through a running game, so
                // rewrite it by serial. A no-op when the game never wrote one — then the pruned
                // JSON alone already resolves to global at the next boot.
                gameSerial != null -> ConfigStore.resolveForGame(gameSerial)
                    .writeGameSettingsIni(ConfigStore.loadGlobal(), gameSerial, InGameOverlay.currentCrc.value)
                // Global scope with a game live: re-apply the reset globals to the base layer.
                running -> settings.value.applyTo()
            }
        }
    }
}
