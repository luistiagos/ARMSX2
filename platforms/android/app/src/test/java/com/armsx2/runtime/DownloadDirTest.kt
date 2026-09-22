package com.armsx2.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Valida a gestão de pasta própria de download e a robustez da sonda de escrita (TASK-0099).
 */
class DownloadDirTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `validateSystemDirWritable funciona em diretorio valido`() {
        val folder = temp.newFolder("valid_dir")
        assertTrue(MainActivityRuntime.validateSystemDirWritable(folder.absolutePath))
    }

    @Test
    fun `validateSystemDirWritable tolera arquivo de sonda orfao`() {
        val folder = temp.newFolder("orphan_probe_dir")
        val orphanProbe = File(folder, ".armsx2-write-probe")
        assertTrue(orphanProbe.createNewFile())
        assertTrue("Arquivo de sonda pré-existente deve existir", orphanProbe.exists())

        // Anteriormente createNewFile() falhava se o arquivo já existia.
        // A implementação corrigida limpa o órfão e testa a escrita com sucesso.
        assertTrue(MainActivityRuntime.validateSystemDirWritable(folder.absolutePath))
        assertFalse("Arquivo de sonda deve ter sido removido após o teste", orphanProbe.exists())
    }

    @Test
    fun `validateSystemDirWritable falha em arquivo regular em vez de diretorio`() {
        val file = temp.newFile("regular_file.txt")
        assertFalse(MainActivityRuntime.validateSystemDirWritable(file.absolutePath))
    }

    @Test
    fun `hasCustomDownloadDir reflete estado de downloadDir`() {
        MainActivityRuntime.downloadDir.value = null
        assertFalse(MainActivityRuntime.hasCustomDownloadDir())

        MainActivityRuntime.downloadDir.value = ""
        assertFalse(MainActivityRuntime.hasCustomDownloadDir())

        MainActivityRuntime.downloadDir.value = "   "
        assertFalse(MainActivityRuntime.hasCustomDownloadDir())

        val folder = temp.newFolder("custom_download")
        MainActivityRuntime.downloadDir.value = folder.absolutePath
        assertTrue(MainActivityRuntime.hasCustomDownloadDir())

        MainActivityRuntime.downloadDir.value = null
    }

    @Test
    fun `customDownloadDirPosix devolve caminho apenas se gravavel`() {
        val folder = temp.newFolder("posix_download")
        MainActivityRuntime.downloadDir.value = folder.absolutePath
        assertEquals(folder.absolutePath, MainActivityRuntime.customDownloadDirPosix())

        // Caminho inválido / arquivo não diretório
        val file = temp.newFile("not_a_dir")
        MainActivityRuntime.downloadDir.value = file.absolutePath
        assertNull(MainActivityRuntime.customDownloadDirPosix())

        MainActivityRuntime.downloadDir.value = null
    }
}
