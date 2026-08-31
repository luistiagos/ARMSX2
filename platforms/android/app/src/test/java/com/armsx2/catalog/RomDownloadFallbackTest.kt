package com.armsx2.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomDownloadFallbackTest {

    private val manager = RomDownloadManager()

    @Test
    fun `getIACollectionsFor routes digits to numberssymbols collection`() {
        val colls = manager.getIACollectionsFor("12Riven - The Psi-Climinal of Integral (Japan).iso")
        assertTrue(colls.contains("sony_playstation2_numberssymbols"))
        assertTrue(colls.contains("ps2japanredump1"))
    }

    @Test
    fun `getIACollectionsFor routes letters with multi-parts correctly`() {
        val collsD = manager.getIACollectionsFor("Devil May Cry (USA).iso")
        assertTrue(collsD.contains("sony_playstation2_d_part1"))
        assertTrue(collsD.contains("sony_playstation2_d_part2"))

        val collsS = manager.getIACollectionsFor("Silent Hill 2 (Europe) (En,Fr,De,Es,It).iso")
        assertTrue(collsS.contains("sony_playstation2_s_part1"))
        assertTrue(collsS.contains("sony_playstation2_s_part2"))
        assertTrue(collsS.contains("sony_playstation2_s_part3"))
        assertTrue(collsS.contains("sony_playstation2_s_part4"))
        assertTrue(collsS.contains("ps2-eu-roms321com"))

        val collsA = manager.getIACollectionsFor("Ace Combat 04 (USA).iso")
        assertTrue(collsA.contains("sony_playstation2_a"))
    }

    @Test
    fun `matchIAEntry exact match prioritizes identical title and region`() {
        val entries = listOf(
            RomDownloadManager.IAFileEntry(
                "12Riven - The Psi-Climinal of Integral (Japan).zip",
                true,
                false,
                1000L
            ),
            RomDownloadManager.IAFileEntry(
                "Other Game (USA).iso",
                true,
                false,
                2000L
            )
        )

        val source = manager.matchIAEntry(
            "12Riven - The Psi-Climinal of Integral (Japan).iso",
            "12Riven - The Psi-Climinal of Integral (Japan)",
            "12riven - the psi-climinal of integral (japan)",
            "test_coll",
            entries,
            false
        )

        assertNotNull(source)
        assertEquals("https://archive.org/download/test_coll/12Riven%20-%20The%20Psi-Climinal%20of%20Integral%20%28Japan%29.zip", source.url)
        assertEquals("12Riven - The Psi-Climinal of Integral (Japan).zip", source.fileName)
    }

    @Test
    fun `matchIAEntry ignores private entries without cookie`() {
        val entries = listOf(
            RomDownloadManager.IAFileEntry(
                "Locked Game (USA).iso",
                true,
                true, // private = true
                1000L
            )
        )

        val source = manager.matchIAEntry(
            "Locked Game (USA).iso",
            "Locked Game (USA)",
            "locked game (usa)",
            "test_coll",
            entries,
            false // hasCookie = false
        )

        assertNull(source)

        val sourceWithCookie = manager.matchIAEntry(
            "Locked Game (USA).iso",
            "Locked Game (USA)",
            "locked game (usa)",
            "test_coll",
            entries,
            true // hasCookie = true
        )

        assertNotNull(sourceWithCookie)
    }

    @Test
    fun `matchIAEntry matches base title when tags differ`() {
        val entries = listOf(
            RomDownloadManager.IAFileEntry(
                "12Riven - The Psi-Climinal of Integral.iso",
                true,
                false,
                1000L
            )
        )

        val source = manager.matchIAEntry(
            "12Riven - The Psi-Climinal of Integral (Japan).iso",
            "12Riven - The Psi-Climinal of Integral (Japan)",
            "12riven - the psi-climinal of integral (japan)",
            "test_coll",
            entries,
            false
        )

        assertNotNull(source)
        assertEquals("12Riven - The Psi-Climinal of Integral (Japan).iso", source.fileName)
    }
}
