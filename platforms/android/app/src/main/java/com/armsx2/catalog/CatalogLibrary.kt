package com.armsx2.catalog

import com.armsx2.GameInfo

/**
 * O catálogo, visto de fora — o registro que a biblioteca consulta.
 *
 * A biblioteca do RetroSystem PS2 é **uma grade só**: os jogos varridos das pastas do aparelho e as
 * 12.628 entradas do manifesto convivem nela, os baixados marcados. Quem monta essa lista é o
 * `HomeViewModel`; este objeto é o índice por nome de arquivo que sustenta a fusão e o toque num
 * cartão do catálogo.
 *
 * **Este objeto não é a fonte do estado de download.** Ele já teve um `version: MutableIntState`
 * que era incrementado a cada mudança da fila, na esperança de que ler esse inteiro dentro de um
 * composable o inscrevesse nas mudanças. Não funcionava no APK publicado: o arquivo crescia no
 * disco e a tarja do cartão continuava em `↓` (TASK-0038). Quem carrega o estado da fila hoje é
 * `HomeUiState.queue`/`downloads`, com itens **imutáveis** — valor que muda, e não objeto mutado
 * com um contador ao lado.
 */
object CatalogLibrary {

    var entries: List<CatalogEntry> = emptyList()
        private set

    private var byFileName: Map<String, CatalogEntry> = emptyMap()

    /**
     * As versoes de cada titulo, por [CatalogParser.groupKey].
     *
     * O manifesto tem uma linha por ARQUIVO: "007 - Nightfire" sao cinco entradas (USA, duas
     * europeias, Japan, Korea) com a mesma arte. Este indice e o que permite a biblioteca mostrar
     * uma celula por jogo e abrir a escolha da versao no toque (TASK-0047).
     *
     * A ordem dentro de cada grupo e a do manifesto, que e curada a mao (TASK-0015): e ela que
     * decide qual versao empresta capa e titulo para a celula.
     */
    private var byGroupKey: Map<String, List<CatalogEntry>> = emptyMap()

    fun install(list: List<CatalogEntry>) {
        entries = list
        byFileName = list.associateBy { it.fileName }
        byGroupKey = list.groupBy { CatalogParser.groupKey(it.fileName) }
    }

    /** As versoes de um titulo, na ordem do manifesto. Vazia se a chave nao existe. */
    fun variantsFor(groupKey: String?): List<CatalogEntry> =
        groupKey?.let { byGroupKey[it] }.orEmpty()

    /** A entrada de catálogo por trás de uma linha da biblioteca, ou null se é um arquivo local. */
    fun entryFor(game: GameInfo): CatalogEntry? = game.catalogFileName?.let { byFileName[it] }
}
