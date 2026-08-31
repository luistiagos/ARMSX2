package com.armsx2.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * O que o download grava, e o que a biblioteca reconhece depois.
 *
 * Os dois lados têm de concordar: o arquivo pode chegar com uma extensão diferente da que a linha
 * do manifesto pedia (é o formato que a fonte tinha, e é a extensão que decide o leitor do CDVD),
 * e mesmo assim aquela linha tem de ficar marcada como baixada. Ver a TASK-0045.
 */
class DownloadFormatTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `extensao sai do caminho da URL, sem query nem fragmento`() {
        assertEquals("chd", RomDownloadManager.extensionOf("https://x/roms/God of War (USA).chd"))
        assertEquals("7z", RomDownloadManager.extensionOf("https://x/all/187%20-%20Ride.7z?download=1"))
        assertEquals("iso", RomDownloadManager.extensionOf("https://x/a.iso#frag"))
        assertEquals("", RomDownloadManager.extensionOf("https://x/roms/sem-extensao"))
        assertEquals("", RomDownloadManager.extensionOf(null))
    }

    @Test
    fun `so passa o que o CDVD abre`() {
        assertTrue(RomDownloadManager.isPlayable("chd"))
        assertTrue(RomDownloadManager.isPlayable("iso"))
        assertTrue(RomDownloadManager.isPlayable("cso"))
        // Comprimido nunca é "jogável", mesmo depois de a TASK-0048 passar a descompactar: quem
        // decide o leitor do CDVD é a extensão do arquivo no disco, e nenhum leitor abre um 7z.
        // O que mudou é que agora existe um caminho ANTES disso — ver `RomArchiveExtractorTest`.
        assertFalse(RomDownloadManager.isPlayable("7z"))
        assertFalse(RomDownloadManager.isPlayable("zip"))
        assertFalse(RomDownloadManager.isPlayable("rar"))
        assertFalse(RomDownloadManager.isPlayable(""))
    }

    @Test
    fun `o nome local recebe a extensao do conteudo que chegou`() {
        // O caso real: linha .iso do manifesto resolvida para o CHD da versão USA pelo by_alias.
        assertEquals(
            "007 - Quantum of Solace (Europe, Australia) (En,Fr,De,Es,It).chd",
            RomDownloadManager.localFileName(
                "007 - Quantum of Solace (Europe, Australia) (En,Fr,De,Es,It).iso",
                "https://huggingface.co/x/resolve/main/007 - Quantum of Solace (USA).chd",
            ),
        )
        // URL sem extensão utilizável: o nome do manifesto fica como está — quem recusa é o
        // chamador, com isPlayable.
        assertEquals(
            "God of War (USA).chd",
            RomDownloadManager.localFileName("God of War (USA).chd", "https://x/download?id=9"),
        )
    }

    @Test
    fun `descartar um download pela metade leva a anotacao de origem junto`() {
        val roms = temp.newFolder("roms")
        val part = java.io.File(roms, "God of War (USA).chd.part")
        val marker = java.io.File(roms, "God of War (USA).chd.part.src")
        part.writeBytes(ByteArray(64))
        marker.writeText("https://x/God of War (USA).chd")

        RomDownloadManager.discardPart(part)

        // Deixar o marcador para tras faria o proximo download achar que pode retomar bytes que
        // nao existem mais.
        assertFalse(part.exists())
        assertFalse(marker.exists())
    }

    @Test
    fun `markDownloaded reconhece o jogo salvo em outro formato`() {
        val roms = temp.newFolder("roms")
        // A linha do manifesto pede .iso; no disco está o .chd que a fonte tinha.
        java.io.File(roms, "007 - Quantum of Solace (Europe, Australia) (En,Fr,De,Es,It).chd")
            .writeBytes(ByteArray(1024))
        // Download em curso: não conta.
        java.io.File(roms, "God of War (USA).chd.part").writeBytes(ByteArray(1024))
        // Arquivo vazio: também não conta.
        java.io.File(roms, "Ico (Europe).iso").writeBytes(ByteArray(0))

        val entries = listOf(
            CatalogEntry("007 - Quantum of Solace (Europe, Australia) (En,Fr,De,Es,It).iso", "", "", ""),
            CatalogEntry("God of War (USA).chd", "", "", ""),
            CatalogEntry("Ico (Europe).iso", "", "", ""),
        )
        CatalogParser.markDownloaded(entries, roms)

        assertTrue(entries[0].isDownloaded)
        assertFalse(entries[1].isDownloaded)
        assertFalse(entries[2].isDownloaded)
    }
}
