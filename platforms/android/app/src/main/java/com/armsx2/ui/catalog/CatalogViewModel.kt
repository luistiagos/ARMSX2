package com.armsx2.ui.catalog

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsx2.catalog.CatalogEntry
import com.armsx2.catalog.CatalogParser
import com.armsx2.catalog.DownloadQueueManager
import com.armsx2.runtime.MainActivityRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Estado da tela do catálogo.
 *
 * `all` é a lista inteira do manifesto (12.628 entradas) e `visible` é o recorte da busca. Os dois
 * existem porque filtrar 12 mil itens a cada tecla é barato, mas reparsear não é: o parse acontece
 * uma vez.
 */
data class CatalogState(
    val loading: Boolean = true,
    val all: List<CatalogEntry> = emptyList(),
    val visible: List<CatalogEntry> = emptyList(),
    val query: String = "",
    /**
     * Contador de republicacao. Existe por uma razao so, e ela nao e obvia.
     *
     * `mutableStateOf` compara por igualdade ESTRUTURAL. O download muta o proprio `CatalogEntry`
     * (progresso, estado da fila), e republicar com `copy(visible = ArrayList(visible))` produz um
     * `CatalogState` que e `equals` ao anterior -- lista nova, mas com os MESMOS objetos, e
     * `CatalogEntry` nao sobrescreve `equals`, entao a comparacao e por identidade e da igual. O
     * Compose descarta a atribuicao e a tela nunca se mexe: o arquivo crescia no disco e o cartao
     * seguia dizendo "Toque para baixar".
     *
     * Este campo quebra essa igualdade.
     */
    val tick: Int = 0,
)

class CatalogViewModel(app: Application) : AndroidViewModel(app), DownloadQueueManager.QueueListener {

    val state = mutableStateOf(CatalogState())

    private var loaded = false

    /**
     * Onde as ROMs baixadas são gravadas.
     *
     * `roms` dentro da raiz de dados do app: é onde o app anterior as punha, e é o caminho que o
     * core alcança sem permissão nenhuma. Deliberadamente NÃO é a pasta de ROMs que o usuário
     * escolheu no assistente — aquela pode estar num cartão SD via SAF, onde um download de 10 GB
     * com retomada não tem como escrever de forma confiável.
     */
    private fun romsDir(): File {
        val root = MainActivityRuntime.assetCopyRoot(getApplication())
        return File(root, "roms").apply { mkdirs() }
    }

    fun load() {
        if (loaded) return
        loaded = true
        DownloadQueueManager.get().addListener(this)
        viewModelScope.launch {
            val dir = romsDir()
            DownloadQueueManager.get().setRomsDir(dir)
            // O parse lê um asset de 926 KB e monta 12.628 objetos: fora da thread principal, senão
            // a tela abre travada.
            val entries = withContext(Dispatchers.IO) {
                CatalogParser.parse(getApplication()).also { CatalogParser.markDownloaded(it, dir) }
            }
            // O que ja estava baixado antes desta sessao nao e novidade: entra em `announced`
            // agora para nao disparar uma revarredura da biblioteca no primeiro evento da fila.
            entries.forEach { if (it.isDownloaded) announced.add(it.fileName) }
            state.value = CatalogState(loading = false, all = entries, visible = entries)
        }
    }

    fun search(query: String) {
        val s = state.value
        val q = query.trim()
        state.value = s.copy(
            query = query,
            visible = if (q.isEmpty()) s.all else s.all.filter { it.title.contains(q, ignoreCase = true) },
        )
    }

    // As quatro acoes que o modal do cartao oferece. Uma por intencao, em vez de um `onCardAction`
    // que adivinha pelo estado: quem sabe o que o usuario escolheu e a tela, e um botao escrito
    // "Cancelar download" nao pode depender de o estado ainda ser o mesmo de quando foi desenhado.

    fun start(entry: CatalogEntry) {
        DownloadQueueManager.get().enqueue(entry)
        republish()
    }

    fun pause(entry: CatalogEntry) {
        DownloadQueueManager.get().pause(entry)
        republish()
    }

    fun resume(entry: CatalogEntry) {
        DownloadQueueManager.get().resume(entry)
        republish()
    }

    /** Para a transferencia, tira da fila e apaga o `.part` -- `remove` faz as tres coisas. */
    fun cancel(entry: CatalogEntry) {
        DownloadQueueManager.get().remove(entry)
        republish()
    }

    // --- DownloadQueueManager.QueueListener -------------------------------------------------
    //
    // O manager muta o próprio CatalogEntry (progresso, estado da fila). Compose não observa
    // mutação de objeto Java, então cada aviso republica a lista com uma nova identidade — é o que
    // faz a barra de progresso andar.

    /** Nomes ja vistos como concluidos, para avisar a biblioteca uma vez so por jogo. */
    private val announced = HashSet<String>()

    override fun onQueueChanged() {
        // Um download que termina acrescenta um arquivo a pasta que a biblioteca varre. A varredura
        // e guardada em cache por chave de diretorio -- e a chave nao muda quando so o CONTEUDO da
        // pasta muda -- entao sem os dois passos abaixo o jogo baixado nao aparece nem ao voltar
        // para a biblioteca nem no proximo arranque: so no botao de recarregar.
        state.value.all.filter { it.isDownloaded && announced.add(it.fileName) }
            .takeIf { it.isNotEmpty() }
            ?.let {
                // Invalida o cache (vale para o proximo arranque) e revarre agora, se a biblioteca
                // estiver montada.
                MainActivityRuntime.prefs.edit()
                    .remove("gamesCacheKey")
                    .remove("gamesCacheDir")
                    .apply()
                com.armsx2.ui.home.HomeInputController.refreshLibrary()
            }
        republish()
    }

    override fun onProgress(entry: CatalogEntry?) = republish()

    private fun republish() {
        val s = state.value
        state.value = s.copy(visible = ArrayList(s.visible), tick = s.tick + 1)
    }

    override fun onCleared() {
        DownloadQueueManager.get().removeListener(this)
        super.onCleared()
    }
}
