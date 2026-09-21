package com.armsx2.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Valida a detecção de pastas privadas do próprio app (TASK-0097).
 *
 * `isAppPrivateRomsPath` identifica se um caminho é a pasta `roms` privada do app
 * (interna, cartão SD ou sob a raiz de cópia de assets), permitindo higienizar
 * `romsDirs` para que guarde apenas as pastas adicionadas deliberadamente pelo usuário.
 */
class AppPrivateRomsPathTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pkg = "come.nanodata.armsx2"

    @Test
    fun `caminho nulo ou em branco devolve false`() {
        assertFalse(MainActivityRuntime.isAppPrivateRomsPath(null, pkg))
        assertFalse(MainActivityRuntime.isAppPrivateRomsPath("", pkg))
        assertFalse(MainActivityRuntime.isAppPrivateRomsPath("   ", pkg))
    }

    @Test
    fun `reconhece pasta sob assetCopyRoot ativo`() {
        val root = temp.newFolder("asset_root")
        val roms = File(root, "roms").apply { mkdirs() }

        assertTrue(
            MainActivityRuntime.isAppPrivateRomsPath(
                path = roms.absolutePath,
                packageName = pkg,
                currentAssetCopyRoot = root.absolutePath,
            ),
        )
    }

    @Test
    fun `reconhece pasta de volumes de arquivos externos do app`() {
        val ext1 = temp.newFolder("ext1")
        val ext2 = temp.newFolder("ext2")
        val roms1 = File(ext1, "roms").apply { mkdirs() }
        val roms2 = File(ext2, "roms").apply { mkdirs() }

        assertTrue(
            MainActivityRuntime.isAppPrivateRomsPath(
                path = roms1.absolutePath,
                packageName = pkg,
                externalFilesDirs = listOf(ext1.absolutePath, ext2.absolutePath),
            ),
        )
        assertTrue(
            MainActivityRuntime.isAppPrivateRomsPath(
                path = roms2.absolutePath,
                packageName = pkg,
                externalFilesDirs = listOf(ext1.absolutePath, ext2.absolutePath),
            ),
        )
    }

    @Test
    fun `reconhece pasta interna de dataDir`() {
        val dataDir = temp.newFolder("data_dir")
        val roms = File(dataDir, "roms").apply { mkdirs() }

        assertTrue(
            MainActivityRuntime.isAppPrivateRomsPath(
                path = roms.absolutePath,
                packageName = pkg,
                internalDataDir = dataDir.absolutePath,
            ),
        )
    }

    @Test
    fun `reconhece padrao de caminho com Android data package files roms`() {
        val path = "/storage/emulated/0/Android/data/$pkg/files/roms"
        assertTrue(
            MainActivityRuntime.isAppPrivateRomsPath(
                path = path,
                packageName = pkg,
            ),
        )
    }

    @Test
    fun `nao considera pasta externa do usuario como pasta do app`() {
        val userFolder1 = temp.newFolder("MyRoms")
        val userFolder2 = "/storage/emulated/0/PS2_GAMES"
        val userFolder3 = "/storage/ABCD-1234/RetroGames"

        assertFalse(
            MainActivityRuntime.isAppPrivateRomsPath(
                path = userFolder1.absolutePath,
                packageName = pkg,
            ),
        )
        assertFalse(
            MainActivityRuntime.isAppPrivateRomsPath(
                path = userFolder2,
                packageName = pkg,
            ),
        )
        assertFalse(
            MainActivityRuntime.isAppPrivateRomsPath(
                path = userFolder3,
                packageName = pkg,
            ),
        )
    }
}
