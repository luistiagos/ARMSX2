// SPDX-License-Identifier: GPL-3.0+
package com.armsx2.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.armsx2.runtime.MainActivityRuntime

/**
 * The launcher icon is fixed: the app ships one, and there is no picker any more (TASK-0076).
 *
 * What is left here is the way back. Older builds swapped the icon by ENABLING an activity-alias
 * and DISABLING the real BootSplashActivity, and that decision belongs to PackageManager, per
 * install — it is not our SharedPreferences and it survives the APK update. Dropping the picker
 * alone would strand whoever picked "Gold" on the gold icon forever, with no screen to undo it.
 *
 * The aliases stay declared in AndroidManifest.xml (all `enabled="false"`, inert) on purpose:
 * deleting them while an install still has one enabled and BootSplashActivity disabled would leave
 * that install with NO launcher entry at all — and the reset below only runs when the app is
 * launched, which is exactly what that user could no longer do.
 */
object AppIconManager {
    /** Where the picker stored the chosen icon. Read nowhere now; cleared by the reset. */
    private const val PREFS_KEY = "ui.app_icon"
    private const val RESET_KEY = "ui.app_icon.reset_to_default"
    private const val LEGACY_PREFS_NAME = "armsx2"
    private const val LEGACY_PREF_KEY = "app_icon_selection"

    private const val LAUNCHER_COMPONENT = "com.armsx2.BootSplashActivity"

    private val LEGACY_ALIASES = listOf(
        "com.armsx2.BootSplashActivityClassic",
        "com.armsx2.BootSplashActivityGold",
        "com.armsx2.BootSplashActivityRetro",
        "com.armsx2.BootSplashActivityMinimal",
    )

    /**
     * Put the shipped launcher icon back. Runs once per install and is a no-op after that, so the
     * launcher is not asked to refresh on every cold start.
     */
    fun restoreDefaultIcon(context: Context) {
        val prefs = MainActivityRuntime.prefs
        if (runCatching { prefs.getBoolean(RESET_KEY, false) }.getOrDefault(false)) return

        val pm = context.packageManager
        val packageName = context.packageName
        // COMPONENT_ENABLED_STATE_DEFAULT, not ENABLED/DISABLED: DEFAULT drops the stored override
        // and hands the decision back to the manifest — enabled for the real activity, disabled for
        // every alias. The real activity goes first so there is no instant without a launcher entry.
        for (name in listOf(LAUNCHER_COMPONENT) + LEGACY_ALIASES) {
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(packageName, name),
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        runCatching {
            prefs.edit {
                putBoolean(RESET_KEY, true)
                remove(PREFS_KEY)
            }
        }
        // The v1 key, read by the build that came before PREFS_KEY existed.
        runCatching {
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .edit { remove(LEGACY_PREF_KEY) }
        }
    }
}
