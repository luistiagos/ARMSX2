package com.armsx2.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nenhuma chave de tradução pode chegar crua à tela.
 *
 * `I18n.get` devolve a **própria chave** quando não a encontra. É o fallback certo — melhor um
 * identificador do que uma tela em branco — mas ele é silencioso: nada estoura, nada aparece no
 * log, e o defeito só existe para quem olha aquela tela naquele idioma.
 *
 * Foi assim que `catalog.versions.subtitle` chegou ao usuário escrito como `catalog.versions.subtitle`
 * no painel de versões do catálogo, e que `home.saved.exploreCatalog` (erro de digitação de
 * `home.saved.empty.exploreCatalog`) sobreviveu meses. Este segundo é o mais instrutivo: alguém viu
 * a chave crua na tela e **acrescentou ao `pt-BR.json` a chave com o nome errado** em vez de
 * corrigir o ponto de chamada. O sintoma sumiu no único idioma do aparelho de desenvolvimento e
 * continuou em inglês e nos outros 17.
 *
 * Este teste é o que faltava: ele lê o código como texto e reprova a chave que ninguém definiu.
 */
class I18nKeysTest {

    /** `"chave.pontuada" to "texto"` — a forma como `I18n.kt` declara cada entrada. */
    private val definitionRe = Regex("\"([A-Za-z0-9_.]+)\"\\s+to\\s+\"")

    /**
     * `str("chave")` ou `I18n.get("chave")`, **só com literal**.
     *
     * Chave montada em runtime (`str("prefix." + x)`) fica de fora, e tem de ficar: não dá para
     * decidir estaticamente o que ela vale. O que este teste cobre é o caso que de fato apareceu
     * duas vezes — a chave escrita à mão, com um erro de digitação ou nunca definida.
     */
    private val usageRe = Regex("(?:\\bstr|I18n\\.get)\\(\\s*\"([A-Za-z0-9_.]+)\"")

    private fun sourceRoot(): File {
        // O working dir do teste é o módulo (`app/`), mas isso já mudou entre versões do AGP;
        // subir até achar `src/main/java` é estável e falha alto se a árvore mudar de forma.
        var dir = File(".").absoluteFile
        repeat(4) {
            val candidate = File(dir, "src/main/java")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("nao encontrei src/main/java a partir de ${File(".").absolutePath}")
    }

    @Test
    fun `toda chave usada com literal esta definida`() {
        val root = sourceRoot()
        val i18nFile = File(root, "com/armsx2/i18n/I18n.kt")
        assertTrue("I18n.kt nao encontrado em ${i18nFile.absolutePath}", i18nFile.isFile)

        val defined = definitionRe.findAll(i18nFile.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("nenhuma chave lida de I18n.kt -- o formato mudou?", defined.size > 100)

        val missing = sortedMapOf<String, MutableSet<String>>()
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                usageRe.findAll(file.readText()).forEach { m ->
                    val key = m.groupValues[1]
                    if (key !in defined) {
                        missing.getOrPut(key) { sortedSetOf() }
                            .add(file.relativeTo(root).path.replace('\\', '/'))
                    }
                }
            }

        assertTrue(
            buildString {
                append("Chave(s) de traducao usadas e NAO definidas em I18n.kt.\n")
                append("I18n.get devolve a propria chave, entao isto aparece cru na tela:\n\n")
                missing.forEach { (key, files) -> append("  $key\n      ${files.joinToString(", ")}\n") }
                append("\nCorrija o PONTO DE CHAMADA se for erro de digitacao; acrescente a chave\n")
                append("ao BASE_EN se ela realmente faltar. Nao adicione a chave errada a um JSON\n")
                append("de traducao -- isso esconde o defeito no idioma que voce testa.\n")
            },
            missing.isEmpty(),
        )
    }
}
