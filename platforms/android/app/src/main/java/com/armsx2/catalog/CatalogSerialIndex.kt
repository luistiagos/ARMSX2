package com.armsx2.catalog

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Mapeamento de nome de ROM/jogo para o código Serial oficial do PS2 (Redump/GameIndex).
 *
 * Permite que todas as entradas do catálogo resolvam suas capas oficiais em alta resolução
 * no repositório `ps2-covers` usando o serial (ex: "10 Pin - Champions Alley" -> "SLES-53150").
 */
object CatalogSerialIndex {

    private val map = ConcurrentHashMap<String, String>()
    @Volatile private var isLoaded = false

    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            try {
                context.assets.open("catalog_serials.txt").use { isStream ->
                    BufferedReader(InputStreamReader(isStream, "UTF-8")).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line?.trim() ?: continue
                            if (l.isEmpty() || l.startsWith("#")) continue
                            val idx = l.indexOf('|')
                            if (idx > 0 && idx < l.length - 1) {
                                val key = l.substring(0, idx).trim().lowercase()
                                val serial = l.substring(idx + 1).trim().uppercase()
                                map[key] = serial
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback silencioso se o asset não estiver disponível
            }
            isLoaded = true
        }
    }

    /**
     * Retorna o serial correspondente ao nome do arquivo ou título do jogo.
     */
    fun serialFor(fileNameOrTitle: String?): String? {
        if (fileNameOrTitle.isNullOrBlank()) return null
        val clean = fileNameOrTitle.substringAfterLast('/').substringBeforeLast('.').trim().lowercase()
        map[clean]?.let { return it }

        // Tenta sem parênteses de região/idioma e sem colchetes de tags/edições (ex: "God of War (USA) [Legendado PT-BR]" -> "god of war")
        var baseTitle = clean.replace(Regex("\\s*\\([^)]*\\)"), "")
        baseTitle = baseTitle.replace(Regex("\\s*\\[[^\\]]*\\]"), "").trim()
        if (baseTitle.isNotEmpty()) {
            map[baseTitle]?.let { return it }

            // Tenta alternando artigo "the " (ex: "the getawy" <-> "getaway")
            if (baseTitle.startsWith("the ")) {
                val noThe = baseTitle.substring(4).trim()
                map[noThe]?.let { return it }
            } else {
                val withThe = "the $baseTitle"
                map[withThe]?.let { return it }
            }
        }
        return null
    }
}
