package com.armsx2.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.armsx2.GameInfo
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

enum class HomeSort { Title, RecentlyPlayed, Compatibility }

enum class LibraryLayout { Grid, List, Shelf }

enum class HomeTab { Catalog, Saved }

data class HomeUiState(
    val currentTab: HomeTab = HomeTab.Catalog,
    val allGames: List<GameInfo> = emptyList(),
    val visibleGames: List<GameInfo> = emptyList(),
    val recentGames: List<GameInfo> = emptyList(),
    val query: String = "",
    val sort: HomeSort = HomeSort.Title,
    val layout: LibraryLayout = LibraryLayout.Grid,
    /** Category the LIST layout is filtered to, or null for everything. Only List uses it —
     *  Grid and Shelf show categories as their own sections instead, the way Recently Played
     *  works, so filtering the whole library there would hide the sections it is made of. */
    val categoryFilter: String? = null,
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
    private var queryJob: Job? = null
    private var loaded = false
    private var pendingInitialScan = false
    private var directories: List<String> = emptyList()

    /**
     * Indice `uri -> jogo` de `allGames`, memorizado pela IDENTIDADE da lista.
     *
     * [buildState] precisa dele so para resolver os no maximo doze Recentes, e roda uma vez por
     * tecla digitada na busca -- montava um `HashMap` de doze mil entradas a cada uma, com um
     * `Uri.toString()` por jogo, para uma lista que nem depende da busca. `allGames` e substituida
     * inteira quando muda (`mergeCatalog` devolve lista nova), entao comparar a referencia basta.
     *
     * Guardado como um objeto so, e `@Volatile`: [buildState] roda ora na Main (setSort, setTab,
     * launch, setHidden), ora em `Dispatchers.Default` (a busca com debounce). Publicando os dois
     * campos juntos, nenhuma thread ve o mapa de uma lista com a chave de outra -- o pior caso
     * vira remontar o indice uma vez a mais.
     */
    private class RecentIndex(val games: List<GameInfo>, val byUri: Map<String, GameInfo>)
    @Volatile private var recentIndex: RecentIndex? = null

    /** Os Recentes de [allGames], na ordem gravada, pelo indice memorizado acima. */
    private fun recentsOf(allGames: List<GameInfo>): List<GameInfo> {
        val order = repository.recentUris()
        if (order.isEmpty()) return emptyList()
        val cached = recentIndex
        val index = if (cached != null && cached.games === allGames) {
            cached
        } else {
            RecentIndex(allGames, allGames.associateBy { it.uri.toString() }).also { recentIndex = it }
        }
        return order.mapNotNull(index.byUri::get)
    }

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
     * Titulo cujas versoes estao sendo escolhidas -- o painel de versoes esta aberto.
     *
     * Guarda a linha da grade e nao a lista de versoes: a lista sai de
     * `CatalogLibrary.variantsFor(catalogGroupKey)` na hora de desenhar, entao nao ha uma segunda
     * copia do catalogo viva enquanto o painel esta fechado.
     */
    val pendingVariants = androidx.compose.runtime.mutableStateOf<GameInfo?>(null)

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
            val currentTab = runCatching {
                HomeTab.valueOf(
                    MainActivityRuntime.prefs.getString(TabPreference, HomeTab.Catalog.name) ?: HomeTab.Catalog.name,
                )
            }.getOrDefault(HomeTab.Catalog)
            pendingInitialScan = romDirectories.isNotEmpty() && cached.key != repository.cacheKey(romDirectories)
            val validCached = cached.games.filter { game ->
                if (game.uri.scheme == "file" || game.uri.scheme == null) {
                    val path = game.uri.path ?: game.uri.toString()
                    java.io.File(path).exists()
                } else {
                    true
                }
            }
            localGames = validCached
            val initialSort = if (currentTab == HomeTab.Saved) HomeSort.RecentlyPlayed else HomeSort.Title
            state.value = buildState(
                base = state.value.copy(
                    currentTab = currentTab,
                    allGames = mergeCatalog(validCached),
                    layout = layout,
                    sort = initialSort,
                    onlyDownloaded = MainActivityRuntime.prefs.getBoolean(OnlyDownloadedPreference, false),
                    initialized = validCached.isNotEmpty() || !pendingInitialScan,
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
        queryJob?.cancel()
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            // 1. Atualiza imediatamente a string de busca na UI
            state.value = state.value.copy(query = "")
            // 2. Reconstrói a lista em thread de background para nunca travar a Main Thread (needs proper testing)
            queryJob = scope.launch {
                val updated = withContext(Dispatchers.Default) {
                    buildState(state.value.copy(query = "", selectedIndex = 0))
                }
                state.value = updated
            }
            return
        }

        // 1. Atualiza imediatamente o texto digitado na UI (0ms de latência no teclado)
        state.value = state.value.copy(query = value)

        // 2. Executa a filtragem de milhares de jogos e ordenação em thread de background com debounce
        queryJob = scope.launch {
            delay(100L)
            val updated = withContext(Dispatchers.Default) {
                buildState(state.value.copy(query = value, selectedIndex = 0))
            }
            state.value = updated
        }
    }

    fun setSort(value: HomeSort) {
        state.value = buildState(state.value.copy(sort = value, selectedIndex = 0))
    }

    fun setTab(tab: HomeTab) {
        queryJob?.cancel()
        MainActivityRuntime.prefs.edit { putString(TabPreference, tab.name) }
        LibraryKeyboard.close()
        // Reset query on tab change and apply tab-appropriate default sort (needs proper testing)
        val tabSort = if (tab == HomeTab.Saved) HomeSort.RecentlyPlayed else HomeSort.Title
        state.value = buildState(
            state.value.copy(
                currentTab = tab,
                query = "",
                sort = tabSort,
                selectedIndex = 0,
            ),
        )
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

    /** List-layout category filter. Null shows everything. */
    fun setCategoryFilter(category: String?) {
        state.value = buildState(state.value.copy(categoryFilter = category, selectedIndex = 0))
    }

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
                setTab(HomeTab.Saved)
                return
            }
            // Mais de um arquivo sob este título: escolher QUAL vem antes de perguntar se baixa.
            // Um título de versão única segue direto para o painel de download, como sempre — o
            // passo extra só existe onde há mesmo uma escolha a fazer.
            if (game.hasMultipleVersions) {
                pendingVariants.value = game
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

    /** Um jogo com as duas chaves de ordenação já resolvidas — ver o comentário em [buildState]. */
    private class SortRow(val game: GameInfo, val key: String, val recentRank: Int)

    private fun buildState(base: HomeUiState): HomeUiState {
        val recents = recentsOf(base.allGames)
        val recentOrder = recents.mapIndexed { index, game -> game.uri.toString() to index }.toMap()
        val forceEn = com.armsx2.EnglishTitles.enabled.value
        val showHidden = com.armsx2.HiddenGames.showHidden.value
        val query = base.query.trim()
        val isSavedTab = base.currentTab == HomeTab.Saved
        val onlyDownloaded = base.onlyDownloaded
        // A category filter narrows the pool before anything else. Cleared implicitly when the
        // category stops existing (renamed or deleted while selected) rather than leaving the
        // library stuck showing nothing with no obvious way back.
        val activeCategory = base.categoryFilter?.takeIf { it in com.armsx2.GameCategories.names() }
        val members = activeCategory?.let { com.armsx2.GameCategories.members(it) }
        val filtered = base.allGames.filter { game ->
            (members == null || game.settingsKey?.let { it in members } == true) &&

            // Exclude games the user marked hidden (long-press → Hide), unless "Show hidden" is on.
            (showHidden || !com.armsx2.HiddenGames.isHidden(game)) &&
                // Na aba Salvos: esconde as linhas que existem apenas no catálogo.
                (!isSavedTab || !game.isCatalogOnly) &&
                // "Só os baixados": esconde as linhas que existem apenas no catálogo.
                (!onlyDownloaded || !game.isCatalogOnly) &&
                (query.isBlank() ||
                    // Match BOTH names regardless of which is displayed: someone typing
                    // "Katakamuna" should find a game listed as 片神名, and vice versa.
                    game.title.contains(query, ignoreCase = true) ||
                    game.titleEn.contains(query, ignoreCase = true) ||
                    game.titleSort.contains(query, ignoreCase = true) ||
                    game.serial?.contains(query, ignoreCase = true) == true ||
                    game.extension.contains(query, ignoreCase = true) ||
                    // O titulo do grupo e o nome SEM os sufixos, entao buscar "Korea", "Disc 2" ou
                    // "En,Fr" deixaria de achar se so o titulo fosse consultado. So percorre as
                    // versoes quando ha grupo e ha busca -- lista vazia para tudo o mais.
                    (game.hasMultipleVersions &&
                        com.armsx2.catalog.CatalogLibrary.variantsFor(game.catalogGroupKey)
                            .any { it.fileName.contains(query, ignoreCase = true) }))
        }
        // sortKey(), not the displayed title: a Japanese title sorts by its kana reading
        // (GameDB name-sort), because sorting the kanji sorts by codepoint — which is the
        // "sort by name-sort" half of issue #338.
        //
        // Decora-ordena-desdecora, e a razão é o custo do seletor, não elegância. `sortedBy` e
        // `thenBy` montam um `Comparator` que chama o seletor **em toda comparação, dos dois
        // lados** — O(n log n) chamadas, não O(n). E `sortKey()` não lê um campo: passa por
        // `CustomNames.nameFor`, que é `prefs.getString(prefixo + chave)` — uma leitura de
        // SharedPreferences sincronizada, sob o mesmo lock que a UI toma para desenhar cada capa,
        // mais a concatenação da chave. Com o catálogo em ~12 mil linhas e a busca ainda curta
        // (é onde o filtro quase não corta), uma tecla digitada custava ~3×10⁵ leituras de prefs
        // e ~1,6×10⁵ `lowercase()`; calculada uma vez por jogo, custa ~1,2×10⁴ de cada.
        //
        // A ordem não muda: as chaves são as mesmas e as duas ordenações são estáveis, então
        // empates continuam saindo na ordem de entrada.
        val keyed = filtered.map { game ->
            SortRow(
                game = game,
                key = game.sortKey(forceEn).lowercase(),
                recentRank = recentOrder[game.uri.toString()] ?: Int.MAX_VALUE,
            )
        }
        val sorted = when (base.sort) {
            HomeSort.Title -> keyed.sortedBy { it.key }
            HomeSort.RecentlyPlayed -> keyed.sortedWith(
                compareBy<SortRow> { it.recentRank }.thenBy { it.key },
            )
            HomeSort.Compatibility -> keyed.sortedWith(
                compareByDescending<SortRow> { it.game.compatibility }.thenBy { it.key },
            )
        }.map { it.game }
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
                com.armsx2.catalog.CatalogSerialIndex.ensureLoaded(getApplication())
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
                    //
                    // E não é a capa da LINHA, é a do GRUPO. A célula do catálogo mostra a arte da
                    // primeira variante que tem uma (ver `fromCatalog` abaixo); baixar uma variante
                    // sem capa própria trocaria essa célula por um arquivo local sem arte nenhuma —
                    // o jogo pioraria ao ser baixado, que é justamente o que esta rede evita.
                    catalogCoverUrl = entry.coverUrl?.takeIf { it.isNotBlank() }
                        ?: com.armsx2.catalog.CatalogLibrary
                            .variantsFor(com.armsx2.catalog.CatalogParser.groupKey(entry.fileName))
                            .firstNotNullOfOrNull { v -> v.coverUrl?.takeIf { it.isNotBlank() } },
                )
            } else {
                game
            }
        }
        val onDisk = stamped.mapNotNullTo(HashSet()) { it.catalogFileName }
        // UMA linha por TÍTULO, não por arquivo.
        //
        // O manifesto tem uma linha por arquivo, e a grade emitia uma célula por linha: "007 -
        // Nightfire" ocupava cinco células (USA, duas europeias, Japan, Korea) com a mesma arte,
        // porque o repositório de capas tem uma arte por jogo e não por lançamento. São 12.628
        // linhas para 6.569 títulos — 48% da grade era repetição, e foi reportado duas vezes.
        // Agrupar aqui, e não na hora de desenhar, é o que faz a busca, a ordenação, o contador e
        // a navegação por controle enxergarem a mesma lista que o usuário vê.
        //
        // Só o que ainda NÃO está no aparelho é agrupado: `onDisk` já saiu para `stamped` como
        // arquivo concreto, com célula própria, porque juntar duas ROMs baixadas do mesmo jogo
        // esconderia qual delas dá boot.
        //
        // `groupBy` preserva a ordem de aparição, e a versão que empresta título e capa é a
        // primeira do grupo com capa — a ordem do manifesto é curada à mão (TASK-0015) e é ela
        // que decide, não uma prioridade por região que sobrescreveria a curadoria em silêncio.
        val fromCatalog = entries.asSequence()
            .filter { it.fileName !in onDisk }
            .groupBy { com.armsx2.catalog.CatalogParser.groupKey(it.fileName) }
            .map { (groupKey, variants) ->
                val cover = variants.firstOrNull { !it.coverUrl.isNullOrBlank() }
                    ?: variants.firstOrNull { com.armsx2.catalog.CatalogSerialIndex.serialFor(it.fileName) != null }
                    ?: variants.first()
                val matchedSerial = com.armsx2.catalog.CatalogSerialIndex.serialFor(cover.fileName)
                    ?: variants.firstNotNullOfOrNull { com.armsx2.catalog.CatalogSerialIndex.serialFor(it.fileName) }
                val coverUrl = cover.coverUrl?.takeIf { it.isNotBlank() }
                    ?: variants.firstNotNullOfOrNull { it.coverUrl?.takeIf { u -> u.isNotBlank() } }
                GameInfo(
                    // Esquema próprio: nunca colide com um arquivo real e deixa a linha
                    // reconhecível em qualquer log. É opaco de propósito — não há caminho.
                    uri = android.net.Uri.fromParts("catalog", cover.fileName, null),
                    title = com.armsx2.catalog.CatalogParser.baseTitle(cover.fileName),
                    serial = matchedSerial,
                    extension = cover.fileName.substringAfterLast('.', "").uppercase(),
                    catalogFileName = cover.fileName,
                    needsDownload = true,
                    catalogCoverUrl = coverUrl,
                    catalogGroupKey = groupKey,
                    catalogVariantCount = variants.size,
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
        setTab(HomeTab.Saved)
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

    /**
     * Apaga o arquivo de ROM do dispositivo e recarrega a biblioteca.
     */
    fun deleteGame(game: GameInfo, context: Context): Boolean {
        if (game.isCatalogOnly) return false
        val uri = game.uri
        var deleted = false

        // 1. Esquema file:// ou caminho cru
        if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path ?: uri.toString()
            val file = java.io.File(path)
            if (file.exists()) {
                deleted = file.delete()
            } else {
                // Arquivo já não existe no disco: considera excluído para remover registro órfão
                deleted = true
            }
        }

        // 2. Esquema content:// do SAF
        if (!deleted && uri.scheme == "content") {
            try {
                val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                if (doc != null && doc.exists()) {
                    deleted = doc.delete()
                } else if (doc != null && !doc.exists()) {
                    deleted = true
                }
            } catch (_: Exception) {}

            if (!deleted) {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    deleted = rows > 0
                } catch (_: Exception) {}
            }

            if (!deleted) {
                val posix = MainActivityRuntime.resolveTreeUriToPosix(uri.toString())
                if (posix != null) {
                    val f = java.io.File(posix)
                    if (f.exists()) deleted = f.delete() else deleted = true
                }
            }
        }

        // 3. Fallback: procurar pelo catalogFileName na pasta do app se for download do catálogo
        if (!deleted && game.catalogFileName != null) {
            val ownDir = java.io.File(MainActivityRuntime.assetCopyRoot(context), "roms")
            val candidate = java.io.File(ownDir, game.catalogFileName)
            if (candidate.exists()) {
                deleted = candidate.delete()
            } else {
                deleted = true
            }
        }

        if (deleted) {
            if (game.catalogFileName != null) {
                com.armsx2.catalog.CatalogLibrary.entries.firstOrNull { it.fileName == game.catalogFileName }?.let {
                    it.isDownloaded = false
                }
            }
            localGames = localGames.filterNot {
                it.uri == game.uri || it.title.equals(game.title, ignoreCase = true) ||
                    (game.catalogFileName != null && it.catalogFileName == game.catalogFileName)
            }
            removeFromRecent(game)
            MainActivityRuntime.prefs.edit {
                remove("gamesCacheKey")
                remove("gamesCacheDir")
                remove("gamesCache")
            }
            state.value = buildState(
                state.value.copy(allGames = mergeCatalog(localGames))
            )
            refresh()
        }
        return deleted
    }

    // ------------------------------------------- DownloadQueueManager.QueueListener

    override fun onQueueChanged() {
        // Um download que TERMINA acrescenta um arquivo à pasta varrida, e é a varredura — não o
        // catálogo — que transforma aquela linha sintética num jogo lançável. A varredura é
        // guardada em cache por chave de diretório, e a chave não muda quando só o conteúdo da
        // pasta muda: sem invalidá-la, o jogo baixado continuaria como "toque para baixar" (needs proper testing).
        val finished = com.armsx2.catalog.CatalogLibrary.entries.any { entry ->
            entry.isDownloaded &&
                localGames.none { local ->
                    val localName = local.uri.lastPathSegment?.substringAfterLast('/') ?: ""
                    localName.substringBeforeLast('.').equals(entry.fileName.substringBeforeLast('.'), ignoreCase = true)
                }
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
        const val TabPreference = "ui.home.currentTab"
        const val OnlyDownloadedPreference = "library.onlyDownloaded"
        // String-valued now (Grid/List/Shelf). New key so the old boolean pref is
        // ignored and everyone starts at Grid rather than mis-parsing "true"/"false".
        const val LayoutPreference = "library.layout.mode"
    }
}
