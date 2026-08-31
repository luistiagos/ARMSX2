// SPDX-License-Identifier: GPL-3.0+
package com.armsx2.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for AppIcon enum and options.
 */
class AppIconTest {

    @Test
    fun testFromIdResolution() {
        assertEquals(AppIcon.Default, AppIcon.fromId("default"))
        assertEquals(AppIcon.Classic, AppIcon.fromId("classic"))
        assertEquals(AppIcon.Gold, AppIcon.fromId("gold"))
        assertEquals(AppIcon.Retro, AppIcon.fromId("retro"))
        assertEquals(AppIcon.Minimal, AppIcon.fromId("minimal"))
        assertEquals(AppIcon.Default, AppIcon.fromId(null))
        assertEquals(AppIcon.Default, AppIcon.fromId("invalid_id"))
    }

    @Test
    fun testAllIconsHaveValidProperties() {
        for (icon in AppIcon.entries) {
            assertNotNull(icon.id)
            assertNotNull(icon.titleKey)
            if (icon != AppIcon.Default) {
                assertNotNull(icon.componentAlias)
            }
        }
    }
}
