package com.armsx2.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.armsx2.GameInfo
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.core.content.edit

enum class HomeSort { Title, RecentlyPlayed, Compatibility }

enum class LibraryLayout { Grid, List, Shelf }

data class HomeUiState(
    val allGames: List<GameInfo> = emptyList(),
    val visibleGames: List<GameInfo> = emptyList(),
    val recentGames: List<GameInfo> = emptyList(),
    val query: String = "",
    val sort: HomeSort = HomeSort.Title,
    val layout: LibraryLayout = LibraryLayout.Grid,
    val scanning: Boolean = false,
    val error: String? = null,
    val selectedIndex: Int = 0,
    val initialized: Boolean = false,
    /** Ver so o que ja esta no aparelho, escondendo as linhas que so existem no catalogo. */
    val onlyDownloaded: Boolean = false,
    /**
     * A fila de download, como valor.
     *
     * O downloader muta o proprio [com.armsx2.catalog.CatalogEntry] -- um objeto Java compartilhado
     * -- e a primeira versao desta tela sinalizava isso com um contador global. Nao funcionava: os
     * parametros que os cartoes recebem continuavam sendo o MESMO objeto a cada quadro, entao a
     * unica coisa capaz de redesenhar a tarja era a invalidacao daquele contador, e ela nao chegava
     * no build publicado (o arquivo crescia no disco com `↓` na tela).
     *
     * Aqui a fila vira uma lista de [DownloadQueueItem] imutaveis, remontada a cada callback. Um
     * progresso diferente produz um item diferente, que produz um `HomeUiState` diferente: o
     * redesenho passa a ser consequencia das regras normais do Compose, nao de efeito colateral.
     */
    val queue: List<DownloadQueueItem> = emptyList(),
    /** A mesma fila indexada por nome de arquivo, para a tarja de cada capa. */
    val downloads: Map<String, DownloadQueueItem> = emptyMap(),
)

/**
 * Uma linha da fila de download, congelada no instante em que foi publicada.
 *
 * Imutavel de proposito -- ver [HomeUiState.queue]. Guarda `fileName` e nao a entrada do catalogo
 * porque e o nome do arquivo que liga as tres pontas: o cartao da grade, o `.part` no disco e a
 * acao de pausar/cancelar.
 */
data class DownloadQueueItem(
    val fileName: String,
    val title: String,
    val coverUrl: String?,
    val state: com.armsx2.catalog.DownloadQueueManager.State,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

class HomeViewModel(application: Application) :
    AndroidViewModel(application),
    com.armsx2.catalog.DownloadQueueManager.QueueListener {

    private val repository = GameLibraryRepository(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanJob: Job? = null
    private var loaded = false
    private var pendingInitialScan = false
    private var directories: List<String> = emptyList()

    /**
     * Os jogos que estao MESMO no aparelho, antes da fusao com o catalogo.
     *
     * Guardados a parte porque duas coisas so podem ver estes: o RetroAchievements, que precisa
     * abrir o arquivo para calcular o hash, e a propria fusao, que usa a lista para saber o que ja
     * foi baixado. `state.allGames` e a uniao dos dois mundos e serviria mal aos dois.
     */
    private var localGames: List<GameInfo> = emptyList()

    /** Entrada do catalogo que o usuario tocou e ainda nao resolveu (o painel esta aberto). */
    val pendingDownload = androidx.compose.runtime.mutableStateOf<com.armsx2.catalog.CatalogEntry?>(null)

    /**
     * Pedido de ir para a tela de downloads, consumido por um efeito da [HomeScreen].
     *
     * NAO navegue direto de dentro do painel. `PadModal` publica seu conteudo num registro global
     * (`PadModals`) e so o retira no `onDispose` da tela que o hospeda; trocar de rota com o painel
     * ainda aberto tira a HomeScreen de cena com a entrada do modal ainda no registro, e a UI trava
     * -- a tela fica sob o veu escuro, surda a toque e a BACK, com o Compose recompondo sem parar
     * (o logcat vira uma fila de GCs de 14 MB a cada 250 ms). Medido no aparelho.
     *
     * Adiando por um quadro, o painel se desfaz primeiro e a navegacao acontece com o registro ja
     * limpo.
     */
    val pendingDownloadsNav = androidx.compose.runtime.mutableStateOf(false)

    var state = androidx.compose.runtime.mutableStateOf(HomeUiState())
        private set

    fun load(romDirectories: List<String>, nativeReady: Boolean) {
        directories = romDirectories
        if (!loaded) {
            loaded = true
            val cached = repository.loadCached()
            val layout = runCatching {
                LibraryLayout.valueOf(
                    MainActivityRuntime.prefs.getString(LayoutPreference, LibraryLayout.Grid.name) ?: LibraryLayout.Grid.name,
                )
            }.getOrDefault(LibraryLayout.Grid)
            pendingInitialScan = romDirectories.isNotEmpty() && cached.key != repository.cacheKey(romDirectories)
            localGames = cached.games
            state.value = buildState(
                base = state.value.copy(
                    allGames = mergeCatalog(cached.games),
                    layout = layout,
                    onlyDownloaded = MainActivityRuntime.prefs.getBoolean(OnlyDownloadedPreference, false),
                    initialized = cached.games.isNotEmpty() || !pendingInitialScan,
                ),
            )
            loadCatalog()
            // Library-wide RetroAchievements progress. Hooked here because this is where the game
            // list lives, and the sync needs paths to hash. No-op unless a web API key is set.
            // Só os jogos locais: uma linha de catálogo não tem arquivo para hashear.
            if (nativeReady) com.armsx2.RaLibrary.onLibraryLoaded(localGames)
            if (nativeReady && pendingInitialScan) refresh()
        } else if (nativeReady && pendingInitialScan) {
            refresh()
        }
    }

    fun refresh() {
        if (directories.isEmpty() || scanJob?.isActive == true) return
        scanJob = scope.launch {
            val initialScan = pendingInitialScan && state.value.allGames.isEmpty()
            state.value = state.value.copy(
                scanning = true,
                initialized = if (initialScan) false else state.value.initialized,
                error = null,
            )
            val result = runCatching { repository.scan(directories) }
            result.onSuccess { games ->
                pendingInitialScan = false
                localGames = games
                com.armsx2.catalog.CatalogParser.markDownloaded(
                    com.armsx2.catalog.CatalogLibrary.entries,
                    romsDir(),
                )
                state.value = buildState(
                    state.value.copy(allGames = mergeCatalog(games), scanning = false, initialized = true),
                )
                com.armsx2.RaLibrary.onLibraryLoaded(games)
            }.onFailure { failure ->
                pendingInitialScan = false
                state.value = state.value.copy(
                    scanning = false,
                    initialized = true,
                    error = failure.message ?: "Unable to scan the selected folders.",
                )
            }
        }
    }

    fun setQuery(value: String) {
        state.value = buildState(state.value.copy(query = value, selectedIndex = 0))
    }

    fun setSort(value: HomeSort) {
        state.value = buildState(state.value.copy(sort = value, selectedIndex = 0))
    }

    fun toggleLayout() {
        val entries = LibraryLayout.entries
        val next = entries[(state.value.layout.ordinal + 1) % entries.size]
        MainActivityRuntime.prefs.edit { putString(LayoutPreference, next.name) }
        state.value = state.value.copy(layout = next)
    }

    fun moveSelection(delta: Int) {
        val count = state.value.visibleGames.size
        if (count == 0) return
        state.value = state.value.copy(selectedIndex = (state.value.selectedIndex + delta).coerceIn(0, count - 1))
    }

    fun setSelection(index: Int) {
        if (state.value.visibleGames.isEmpty()) return
        state.value = state.value.copy(selectedIndex = index.coerceIn(state.value.visibleGames.indices))
    }

    fun selectedGame(): GameInfo? = state.value.visibleGames.getOrNull(state.value.selectedIndex)

    fun launch(game: GameInfo) {
        // Esta linha nao tem arquivo -- e uma entrada do catalogo. Dar boot nela abriria o emulador
        // sobre um caminho inexistente. A intercepcao mora AQUI, e nao em cada cartao, porque
        // `launch` e o funil por onde passam os sete pontos que iniciam um jogo (grade, lista,
        // prateleira, recentes e o controle): cobrir o funil cobre todos.
        if (game.isCatalogOnly) {
            // Já na fila: não há o que perguntar, a decisão de baixar foi tomada no toque anterior.
            // Vai direto para onde estão o progresso e os controles — é o que a versão anterior
            // fazia em `onEntryClick`, trocando para a aba "Salvos" em vez de abrir diálogo.
            val fileName = game.catalogFileName
            if (fileName != null && state.value.downloads.containsKey(fileName)) {
                pendingDownloadsNav.value = true
                return
            }
            pendingDownload.value = com.armsx2.catalog.CatalogLibrary.entryFor(game)
            return
        }
        repository.markPlayed(game)
        state.value = buildState(state.value)
        val launchPath = if (game.uri.scheme == "file") game.uri.path ?: game.uri.toString() else game.uri.toString()
        MainActivityRuntime.launchGame(launchPath, game)
    }

    /** Mark a game hidden (or un-hidden) and refresh the visible list. */
    fun setHidden(game: GameInfo, value: Boolean) {
        com.armsx2.HiddenGames.setHidden(game, value)
        state.value = buildState(state.value)
    }

    /** Toggle whether hidden games are shown (so they can be un-hidden). */
    fun setShowHidden(value: Boolean) {
        com.armsx2.HiddenGames.setShowHidden(value)
        state.value = buildState(state.value)
    }

    /** Drop one game from Recently Played (it returns when next launched). */
    fun removeFromRecent(game: GameInfo) {
        repository.removeFromRecent(game)
        state.value = buildState(state.value)
    }

    /** Empty Recently Played. Games return as they're launched again; the library is untouched. */
    fun clearRecent() {
        repository.clearRecent()
        state.value = buildState(state.value)
    }

    private fun buildState(base: HomeUiState): HomeUiState {
        val recents = repository.recentGames(base.allGames)
        val recentOrder = recents.mapIndexed { index, game -> game.uri.toString() to index }.toMap()
        val forceEn = com.armsx2.EnglishTitles.enabled.value
        val filtered = base.allGames.filter { game ->
            val query = base.query.trim()
            // Exclude games the user marked hidden (long-press → Hide), unless "Show hidden" is on.
            (com.armsx2.HiddenGames.showHidden.value || !com.armsx2.HiddenGames.isHidden(game)) &&
                // "Só os baixados": esconde as linhas que existem apenas no catálogo.
                (!base.onlyDownloaded || !game.isCatalogOnly) &&
                (query.isBlank() ||
                    // Match BOTH names regardless of which is displayed: someone typing
                    // "Katakamuna" should find a game listed as 片神名, and vice versa.
                    game.title.contains(query, ignoreCase = true) ||
                    game.titleEn.contains(query, ignoreCase = true) ||
                    game.titleSort.contains(query, ignoreCase = true) ||
                    game.serial?.contains(query, ignoreCase = true) == true ||
                    game.extension.contains(query, ignoreCase = true))
        }
        // sortKey(), not the displayed title: a Japanese title sorts by its kana reading
        // (GameDB name-sort), because sorting the kanji sorts by codepoint — which is the
        // "sort by name-sort" half of issue #338.
        val sorted = when (base.sort) {
            HomeSort.Title -> filtered.sortedBy { it.sortKey(forceEn).lowercase() }
            HomeSort.RecentlyPlayed -> filtered.sortedWith(
                compareBy<GameInfo> { recentOrder[it.uri.toString()] ?: Int.MAX_VALUE }
                    .thenBy { it.sortKey(forceEn).lowercase() },
            )
            HomeSort.Compatibility -> filtered.sortedWith(
                compareByDescending<GameInfo> { it.compatibility }
                    .thenBy { it.sortKey(forceEn).lowercase() },
            )
        }
        return base.copy(
            visibleGames = sorted,
            recentGames = recents,
            selectedIndex = base.selectedIndex.coerceIn(0, (sorted.size - 1).coerceAtLeast(0)),
        )
    }

    // ---------------------------------------------------------------- catálogo

    /**
     * Onde as ROMs baixadas são gravadas — e, por consequência, onde a biblioteca as encontra.
     *
     * `roms` dentro da raiz de dados do app: é onde o app anterior as punha, é o caminho que o core
     * alcança sem permissão nenhuma, e é a pasta que `seedOwnRomsFolder` semeia em `romsDirs`.
     * Deliberadamente NÃO é a pasta de ROMs que o usuário escolheu no assistente — aquela pode
     * estar num cartão SD via SAF, onde um download de 10 GB com retomada não tem como escrever de
     * forma confiável.
     */
    private fun romsDir(): java.io.File =
        java.io.File(MainActivityRuntime.assetCopyRoot(getApplication()), "roms").apply { mkdirs() }

    private fun loadCatalog() {
        // Assinar a fila NÃO pode ficar atrás do atalho de cache abaixo.
        //
        // `CatalogLibrary` é um `object` de processo e `onCleared()` desassina. Enquanto estas duas
        // linhas moravam dentro do `if`, o primeiro HomeViewModel assinava e qualquer um criado
        // depois — o catálogo já carregado, portanto `return` na primeira linha — ficava mudo para
        // sempre: o download rodava e a tela nunca sabia. Na versão anterior o `addListener` era
        // incondicional no `onCreate`, pareado com o `onDestroy`; é esse contrato que volta aqui.
        val queue = com.armsx2.catalog.DownloadQueueManager.get()
        queue.setRomsDir(romsDir())
        queue.addListener(this)
        republish()

        if (com.armsx2.catalog.CatalogLibrary.entries.isNotEmpty()) return
        scope.launch {
            val dir = romsDir()
            // O parse lê um asset de 926 KB e monta 12.628 objetos: fora da thread principal, senão
            // a biblioteca abre travada.
            val entries = kotlinx.coroutines.withContext(Dispatchers.IO) {
                com.armsx2.catalog.CatalogParser.parse(getApplication())
                    .also { com.armsx2.catalog.CatalogParser.markDownloaded(it, dir) }
            }
            com.armsx2.catalog.CatalogLibrary.install(entries)
            state.value = buildState(state.value.copy(allGames = mergeCatalog(localGames), initialized = true))
        }
    }

    /**
     * A união que forma a grade: o que está no disco, mais o que só existe no catálogo.
     *
     * A chave é o nome do arquivo. Um jogo baixado aparece uma vez só, e aparece na sua forma
     * **local** — com serial sondado do disco, capa curada e boot funcionando — em vez da linha
     * sintética do manifesto. Ou seja: baixar um jogo não acrescenta um cartão à grade, converte o
     * que já estava lá.
     */
    private fun mergeCatalog(local: List<GameInfo>): List<GameInfo> {
        val entries = com.armsx2.catalog.CatalogLibrary.entries
        if (entries.isEmpty()) return local
        // O URI de um jogo local pode ser file:///.../x.chd ou content://...%2Fx.chd — os dois
        // terminam no nome do arquivo, mas o segundo carrega o caminho inteiro no último segmento.
        // A chave é o nome SEM extensão. Um download pode chegar num formato diferente do que a
        // linha do manifesto pedia — a fonte só tinha `.chd` para uma linha `.iso` —, e é a
        // extensão que decide o leitor do CDVD, então quem grava respeita o conteúdo recebido (ver
        // `RomDownloadManager.localFileName`). Casando pelo nome cheio, esse jogo apareceria duas
        // vezes na grade: o arquivo solto no disco e a linha de catálogo ainda dizendo "baixar".
        val byBaseName = entries.associateBy { it.fileName.substringBeforeLast('.').lowercase() }
        // O jogo que ESTÁ no disco também aponta para a sua entrada — é o que lhe dá o ✓ na tarja.
        // Sem isto, um jogo baixado ficava indistinguível de um arquivo que o usuário trouxe por
        // conta própria: os dois sem marca nenhuma, que é justamente a distinção que a grade
        // única precisa mostrar.
        val stamped = local.map { game ->
            val fileName = game.uri.lastPathSegment?.substringAfterLast('/')
            val entry = fileName?.let { byBaseName[it.substringBeforeLast('.').lowercase()] }
            if (entry != null) {
                game.copy(
                    catalogFileName = entry.fileName,
                    // A capa do manifesto vem junto, como rede de proteção: `GameInfo.coverUrl` só
                    // sabe montar URL a partir do serial, e o serial vem de sondar o disco. Sem
                    // isto, todo jogo cuja sonda não devolve serial perde, ao ser baixado, a capa
                    // que a linha de catálogo já estava mostrando.
                    catalogCoverUrl = entry.coverUrl?.takeIf { it.isNotBlank() },
                )
            } else {
                game
            }
        }
        val onDisk = stamped.mapNotNullTo(HashSet()) { it.catalogFileName }
        val fromCatalog = entries.asSequence()
            .filter { it.fileName !in onDisk }
            .map { entry ->
                GameInfo(
                    // Esquema próprio: nunca colide com um arquivo real e deixa a linha
                    // reconhecível em qualquer log. É opaco de propósito — não há caminho.
                    uri = android.net.Uri.fromParts("catalog", entry.fileName, null),
                    title = entry.title,
                    serial = null,
                    extension = entry.fileName.substringAfterLast('.', "").uppercase(),
                    catalogFileName = entry.fileName,
                    needsDownload = true,
                    catalogCoverUrl = entry.coverUrl,
                )
            }
        return stamped + fromCatalog
    }

    /** Ver só o que já está no aparelho. */
    fun setOnlyDownloaded(value: Boolean) {
        MainActivityRuntime.prefs.edit { putBoolean(OnlyDownloadedPreference, value) }
        state.value = buildState(state.value.copy(onlyDownloaded = value, selectedIndex = 0))
    }

    // As quatro ações do painel de download. Uma por intenção: quem sabe o que o usuário escolheu é
    // a tela, e um botão escrito "Cancelar download" não pode depender de o estado ainda ser o
    // mesmo de quando foi desenhado.

    /**
     * Enfileira e **pede** a troca de tela — ver [pendingDownloadsNav] para o porquê do pedido em
     * vez da navegação direta.
     *
     * A troca não é enfeite: sem ela o usuário confirma "Baixar", o painel fecha e a biblioteca fica
     * igual — que foi exatamente o relato que abriu a TASK-0038. A versão anterior resolvia isso
     * trocando para a aba "Salvos" no mesmo gesto (`bottomNav.setSelectedItemId(R.id.nav_saved)`).
     */
    fun startDownload(entry: com.armsx2.catalog.CatalogEntry) {
        com.armsx2.catalog.DownloadQueueManager.get().enqueue(entry)
        pendingDownloadsNav.value = true
    }

    fun pauseDownload(entry: com.armsx2.catalog.CatalogEntry) =
        com.armsx2.catalog.DownloadQueueManager.get().pause(entry)

    fun resumeDownload(entry: com.armsx2.catalog.CatalogEntry) =
        com.armsx2.catalog.DownloadQueueManager.get().resume(entry)

    /** Para a transferência, tira da fila e apaga o `.part` — `remove` faz as três coisas. */
    fun cancelDownload(entry: com.armsx2.catalog.CatalogEntry) =
        com.armsx2.catalog.DownloadQueueManager.get().remove(entry)

    // As mesmas três ações, vindas da SEÇÃO DE FILA, que só conhece o nome do arquivo — o painel
    // por jogo tem a entrada do catálogo em mãos, a seção não. Nomes distintos em vez de sobrecarga
    // porque as duas telas passam as funções por referência (`viewModel::pauseDownload`), e aí uma
    // sobrecarga vira ambiguidade resolvida por tipo esperado — legível hoje, armadilha amanhã.

    /**
     * A entrada por trás de uma linha da fila.
     *
     * Procurada na fila viva, e não no catálogo inteiro: as três ações só fazem sentido sobre o que
     * está enfileirado, e um item que saiu da fila entre o desenho e o toque deve virar no-op em vez
     * de agir sobre outra coisa.
     */
    private fun queued(fileName: String): com.armsx2.catalog.CatalogEntry? =
        com.armsx2.catalog.DownloadQueueManager.get().getActiveQueue()
            .firstOrNull { it.fileName == fileName }

    fun pauseQueued(fileName: String) = queued(fileName)?.let { pauseDownload(it) } ?: Unit

    fun resumeQueued(fileName: String) = queued(fileName)?.let { resumeDownload(it) } ?: Unit

    fun cancelQueued(fileName: String) = queued(fileName)?.let { cancelDownload(it) } ?: Unit

    // ------------------------------------------- DownloadQueueManager.QueueListener

    override fun onQueueChanged() {
        // Um download que TERMINA acrescenta um arquivo à pasta varrida, e é a varredura — não o
        // catálogo — que transforma aquela linha sintética num jogo lançável. A varredura é
        // guardada em cache por chave de diretório, e a chave não muda quando só o conteúdo da
        // pasta muda: sem invalidá-la, o jogo baixado continuaria como "toque para baixar".
        val finished = com.armsx2.catalog.CatalogLibrary.entries.any { entry ->
            entry.isDownloaded &&
                localGames.none { it.uri.lastPathSegment?.substringAfterLast('/') == entry.fileName }
        }
        republish()
        if (finished) {
            MainActivityRuntime.prefs.edit { remove("gamesCacheKey").remove("gamesCacheDir") }
            refresh()
        }
    }

    override fun onProgress(entry: com.armsx2.catalog.CatalogEntry?) = republish()

    /**
     * Congela a fila num valor e publica.
     *
     * Roda na thread principal — `DownloadQueueManager` entrega todo callback por `mainHandler`, e
     * é isso que torna seguro ler os campos mutáveis do `CatalogEntry` aqui: quem os escreveu foi a
     * mesma thread, um instante antes, no callback de progresso.
     */
    private fun republish() {
        val items = com.armsx2.catalog.DownloadQueueManager.get().getActiveQueue().map { entry ->
            DownloadQueueItem(
                fileName = entry.fileName,
                title = entry.title,
                coverUrl = entry.coverUrl?.takeIf { it.isNotBlank() },
                // `queueState` só é nulo fora da fila, e `getActiveQueue()` devolve a fila; um item
                // que perdeu o estado entre a leitura e aqui vale como QUEUED em vez de derrubar a
                // lista inteira num `!!`.
                state = entry.queueState ?: com.armsx2.catalog.DownloadQueueManager.State.QUEUED,
                progress = entry.downloadProgress,
                downloadedBytes = entry.downloadedBytes,
                totalBytes = entry.totalBytes,
            )
        }
        state.value = state.value.copy(queue = items, downloads = items.associateBy { it.fileName })
    }

    override fun onCleared() {
        com.armsx2.catalog.DownloadQueueManager.get().removeListener(this)
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        const val OnlyDownloadedPreference = "library.onlyDownloaded"
        // String-valued now (Grid/List/Shelf). New key so the old boolean pref is
        // ignored and everyone starts at Grid rather than mis-parsing "true"/"false".
        const val LayoutPreference = "library.layout.mode"
    }
}
