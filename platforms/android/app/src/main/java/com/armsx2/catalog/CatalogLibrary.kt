package com.armsx2.catalog

import androidx.compose.runtime.mutableIntStateOf
import com.armsx2.GameInfo

/**
 * O catálogo, visto de fora — o registro que a biblioteca consulta.
 *
 * A biblioteca do RetroSystem PS2 é **uma grade só**: os jogos varridos das pastas do aparelho e as
 * 12.628 entradas do manifesto convivem nela, os baixados marcados. Quem monta essa lista é o
 * `HomeViewModel`; o problema é que o cartão, lá embaixo, também precisa do estado de download para
 * desenhar o distintivo — e um cartão não deveria receber o catálogo inteiro por parâmetro só para
 * isso.
 *
 * Este objeto é o meio-termo: um lugar único onde as entradas ficam indexadas por nome de arquivo,
 * mais um [version] que o Compose observa. Sem o [version] nada disso apareceria: o downloader muta
 * o próprio [CatalogEntry] (progresso, estado da fila), e mutação de objeto Java é invisível para o
 * Compose — o mesmo motivo pelo qual a primeira versão da tela do catálogo mostrava "Toque para
 * baixar" enquanto o arquivo crescia no disco.
 */
object CatalogLibrary {

    /** Bumped a cada mudança na fila. Ler dentro de um composable o inscreve nas mudanças. */
    val version = mutableIntStateOf(0)

    var entries: List<CatalogEntry> = emptyList()
        private set

    private var byFileName: Map<String, CatalogEntry> = emptyMap()

    fun install(list: List<CatalogEntry>) {
        entries = list
        byFileName = list.associateBy { it.fileName }
        version.intValue++
    }

    /** A entrada de catálogo por trás de uma linha da biblioteca, ou null se é um arquivo local. */
    fun entryFor(game: GameInfo): CatalogEntry? = game.catalogFileName?.let { byFileName[it] }

    fun bump() {
        version.intValue++
    }
}
