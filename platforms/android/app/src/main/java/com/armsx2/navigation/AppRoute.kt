package com.armsx2.navigation

import androidx.compose.runtime.mutableStateOf
import com.armsx2.GameInfo

sealed interface AppRoute {
    data object Home : AppRoute
    data class Settings(
        val category: SettingsCategory = SettingsCategory.General,
        val game: GameInfo? = null,
    ) : AppRoute
    // Carries an optional game so the per-game BIOS picker can key on it directly
    // (from the library long-press) without the game being loaded; null = global,
    // opened from the drawer (falls back to the currently loaded game if any).
    data class BiosManager(val game: GameInfo? = null) : AppRoute
    data class MemoryCardManager(val game: GameInfo? = null) : AppRoute
    /**
     * Gerir as pastas de ROM como TELA, nao como assistente.
     *
     * A linha da gaveta abria o assistente de primeira execucao (cinco paginas com "Proximo" e
     * "Voltar", pedindo local dos dados e BIOS antes de deixar sair) so para acrescentar uma pasta.
     */
    data object RomFolders : AppRoute
    /**
     * A fila de download, como TELA.
     *
     * Ela ja morou empilhada acima da grade da biblioteca (TASK-0038), onde empurrava 12.628
     * cartoes para baixo. Na versao anterior a fila nunca dividiu tela com o catalogo: vivia na
     * aba "Salvos" do `BottomNavigationView`, e tocar para baixar levava para la.
     */
    data object Downloads : AppRoute
    data object SaveManager : AppRoute
    data object ControllerManager : AppRoute
    data object PatchManager : AppRoute
    data object TextureManager : AppRoute
    data object Achievements : AppRoute
    data object Language : AppRoute
    data object News : AppRoute
    data object Friends : AppRoute
    data object About : AppRoute
}

enum class SettingsCategory {
    General,
    Info,
    Performance,
    Graphics,
    Audio,
    Controls,
    Hotkeys,
    Network,
    OnScreen,
    Skins,
    Advanced,
    Patches,
    About,
}

object UiNavigator {
    /**
     * A tela inicial e a biblioteca -- e ela ja E o catalogo.
     *
     * Houve uma versao com duas grades: a biblioteca (o que esta no aparelho) e uma tela separada
     * de catalogo (os 12.628 do manifesto), abrindo nesta ultima para imitar a primeira aba do
     * `BottomNavigationView` do app anterior. Duas grades para a mesma coisa nao se justificam: a
     * biblioteca agora carrega o catalogo inteiro, com uma tarja dizendo o que ja esta baixado e um
     * filtro "so os baixados" no menu de tres pontos para quem quiser so o seu.
     */
    val route = mutableStateOf<AppRoute>(AppRoute.Home)
    val drawerOpen = mutableStateOf(false)

    fun navigate(destination: AppRoute) {
        val changed = route.value != destination
        route.value = destination
        drawerOpen.value = false
        // "Entering a settings menu / sub-screen" blip — but not for just returning Home.
        if (changed && destination != AppRoute.Home) {
            com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.SUBMENU)
        }
    }

    fun home() = navigate(AppRoute.Home)

    fun back(): Boolean {
        if (drawerOpen.value) {
            drawerOpen.value = false
            return true
        }
        when (route.value) {
            AppRoute.Language -> {
                route.value = AppRoute.Settings(SettingsCategory.General)
                return true
            }
            AppRoute.About -> {
                route.value = AppRoute.Settings(SettingsCategory.General)
                return true
            }
            AppRoute.Home -> Unit
            else -> {
                route.value = AppRoute.Home
                return true
            }
        }
        drawerOpen.value = true
        return true
    }
}
