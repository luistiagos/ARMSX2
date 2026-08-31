package com.armsx2.catalog

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * O que sai de dentro de um `.7z`/`.zip` baixado, e com que nome (TASK-0048).
 *
 * Os arquivos comprimidos aqui são **reais**, montados no próprio teste: é o único jeito de o teste
 * dizer alguma coisa sobre o `commons-compress` + `xz` que o APK carrega. Um teste com dublê
 * passaria igual se a dependência do codec estivesse faltando — e é exatamente assim que ela
 * falharia no aparelho, só no arquivo do usuário.
 */
class RomArchiveExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bytes(size: Int, fill: Byte): ByteArray = ByteArray(size) { fill }

    /** Um `.zip` com as entradas pedidas, na ordem dada. */
    private fun zipOf(dir: File, name: String, entries: List<Pair<String, ByteArray>>): File {
        val archive = File(dir, name)
        ZipOutputStream(archive.outputStream().buffered()).use { out ->
            entries.forEach { (entryName, content) ->
                out.putNextEntry(ZipEntry(entryName))
                out.write(content)
                out.closeEntry()
            }
        }
        return archive
    }

    /** Um `.7z` de verdade — LZMA2, o codec que exige a dependência `org.tukaani:xz`. */
    private fun sevenZOf(dir: File, name: String, entries: List<Pair<String, ByteArray>>): File {
        val archive = File(dir, name)
        val staging = temp.newFolder(name.replace('.', '_'))
        SevenZOutputFile(archive).use { out ->
            entries.forEach { (entryName, content) ->
                val staged = File(staging, entryName.substringAfterLast('/'))
                staged.writeBytes(content)
                out.putArchiveEntry(out.createArchiveEntry(staged, entryName))
                out.write(content)
                out.closeArchiveEntry()
            }
            out.finish()
        }
        return archive
    }

    @Test
    fun `so os formatos comprimidos que sabemos abrir`() {
        assertTrue(RomArchiveExtractor.isArchive("7z"))
        assertTrue(RomArchiveExtractor.isArchive("zip"))
        // O commons-compress DETECTA rar e não descompacta. Aceitá-lo aqui faria a resolução
        // escolher uma fonte que a extração não abre — 2 GB baixados para terminar em erro.
        assertFalse(RomArchiveExtractor.isArchive("rar"))
        assertFalse(RomArchiveExtractor.isArchive("chd"))
        assertFalse(RomArchiveExtractor.isArchive(""))
    }

    @Test
    fun `o comprimido e gravado com a extensao dele, e nao com a do manifesto`() {
        // Enquanto o arquivo está no disco ele é um .7z: é a extensão que diz ao extrator qual
        // leitor usar. A troca para .iso/.chd acontece só no fim da extração.
        assertEquals(
            "10.000 Bullets (Europe) (En,Fr,De,Es,It).7z",
            RomDownloadManager.localFileName(
                "10.000 Bullets (Europe) (En,Fr,De,Es,It).iso",
                "https://x/all/10000%20Bullets.7z?download=1",
            ),
        )
        // O ponto do "10.000" não é separador de extensão.
        assertEquals(
            "10.000 Bullets (Europe).iso",
            RomDownloadManager.withExtension("10.000 Bullets (Europe).7z", "iso"),
        )
    }

    @Test
    fun `zip - sai a maior entrada jogavel, com o nome do manifesto`() {
        val roms = temp.newFolder("roms")
        val archive = zipOf(
            roms,
            "10.000 Bullets (Europe) (En,Fr,De,Es,It).zip",
            listOf(
                "leiame.txt" to bytes(4096, 7),
                // Um .nfo maior que a ROM: a escolha é pelo tamanho ENTRE as jogáveis, não pelo
                // tamanho absoluto.
                "scans/capa.nfo" to bytes(200_000, 9),
                "10,000 Bullets (Europe).iso" to bytes(64_000, 1),
                "Disco extra/bonus.iso" to bytes(8_000, 2),
            ),
        )
        val manifestName = "10.000 Bullets (Europe) (En,Fr,De,Es,It).iso"
        val part = File(roms, "$manifestName.part")

        val out = RomArchiveExtractor.extract(archive, roms, manifestName, part, null)

        assertEquals(manifestName, out.name)
        assertEquals(64_000L, out.length())
        assertEquals(1.toByte(), out.readBytes()[0])
        // O comprimido some: deixado no disco, `markDownloaded` o contaria como jogo baixado —
        // ele casa por nome sem extensão — e o emulador não abre aquilo.
        assertFalse(archive.exists())
        assertFalse(part.exists())
    }

    @Test
    fun `7z - a extensao final e a de dentro do arquivo, nao a da linha do manifesto`() {
        val roms = temp.newFolder("roms")
        val archive = sevenZOf(
            roms,
            "God of War II (USA).7z",
            listOf("God of War II.chd" to bytes(32_000, 5)),
        )
        // A linha do manifesto pede .iso; lá dentro está um .chd, e é a extensão que decide o
        // leitor do CDVD (`GetFileReader`, em pcsx2/CDVD/InputIsoFile.cpp).
        val manifestName = "God of War II (USA).iso"
        val part = File(roms, "$manifestName.part")

        val out = RomArchiveExtractor.extract(archive, roms, manifestName, part, null)

        assertEquals("God of War II (USA).chd", out.name)
        assertEquals(32_000L, out.length())
        assertFalse(archive.exists())
    }

    @Test
    fun `arquivo sem nada que o emulador abra falha, e nao deixa meio arquivo para tras`() {
        val roms = temp.newFolder("roms")
        val archive = zipOf(
            roms,
            "Nada (USA).zip",
            listOf("leiame.txt" to bytes(1024, 3), "capa.png" to bytes(2048, 4)),
        )
        val manifestName = "Nada (USA).iso"
        val part = File(roms, "$manifestName.part")

        val error = runCatching {
            RomArchiveExtractor.extract(archive, roms, manifestName, part, null)
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message!!.contains("Nada (USA).zip"))
        assertFalse(File(roms, manifestName).exists())
        // O extrator não apaga o comprimido: quem decide isso é o chamador, que sabe se ainda vai
        // tentar outra coisa com ele.
        assertTrue(archive.exists())
    }

    @Test
    fun `cancelar interrompe a extracao sem produzir o arquivo final`() {
        val roms = temp.newFolder("roms")
        val archive = zipOf(
            roms,
            "Ico (Europe).zip",
            listOf("Ico (Europe).iso" to bytes(512_000, 6)),
        )
        val manifestName = "Ico (Europe).iso"
        val part = File(roms, "$manifestName.part")
        val cancelled = object : RomArchiveExtractor.Progress {
            override fun onProgress(bytesExtracted: Long, totalBytes: Long) = Unit
            override fun isCancelled(): Boolean = true
        }

        val error = runCatching {
            RomArchiveExtractor.extract(archive, roms, manifestName, part, cancelled)
        }.exceptionOrNull()

        assertTrue(error is RomArchiveExtractor.CancelledException)
        assertFalse(File(roms, manifestName).exists())
    }

    @Test
    fun `a extracao relata progresso com o tamanho descomprimido, nao com o do arquivo`() {
        val roms = temp.newFolder("roms")
        // Conteúdo repetido comprime muito: o .zip fica MUITO menor que os 512 KB de dentro. É
        // justamente por isso que o total do progresso tem de sair do cabeçalho da entrada.
        val archive = zipOf(roms, "Ico (Europe).zip", listOf("Ico (Europe).iso" to bytes(512_000, 6)))
        val manifestName = "Ico (Europe).iso"
        var lastTotal = -1L
        var lastDone = -1L
        val watcher = object : RomArchiveExtractor.Progress {
            override fun onProgress(bytesExtracted: Long, totalBytes: Long) {
                lastDone = bytesExtracted
                lastTotal = totalBytes
            }
            override fun isCancelled(): Boolean = false
        }

        RomArchiveExtractor.extract(archive, roms, manifestName, File(roms, "$manifestName.part"), watcher)

        assertEquals(512_000L, lastTotal)
        assertEquals(512_000L, lastDone)
    }

    @Test
    fun `falta de espaco vira mensagem, e nao excecao`() {
        val roms = temp.newFolder("roms")
        // Devolver mensagem em vez de lançar é o que mantém isto fora do laço de retentativa do
        // download: disco cheio não muda de resposta na segunda tentativa.
        assertNull(RomArchiveExtractor.spaceShortfall(roms, 0))
        assertNull(RomArchiveExtractor.spaceShortfall(roms, 1024))
        assertNotNull(RomArchiveExtractor.spaceShortfall(roms, Long.MAX_VALUE / 2))
    }
}
