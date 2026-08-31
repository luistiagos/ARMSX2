// SPDX-License-Identifier: GPL-3.0+
package com.armsx2.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.armsx2.R
import com.armsx2.runtime.MainActivityRuntime

/**
 * Available launcher app icons.
 */
enum class AppIcon(
    val id: String,
    val titleKey: String,
    val componentAlias: String?,
    @get:DrawableRes val iconRes: Int,
    @get:DrawableRes val previewRes: Int,
) {
    Default(
        id = "default",
        titleKey = "app.icon.default",
        componentAlias = null,
        iconRes = R.mipmap.ic_launcher,
        previewRes = R.mipmap.ic_launcher,
    ),
    Classic(
        id = "classic",
        titleKey = "app.icon.classic",
        componentAlias = "com.armsx2.BootSplashActivityClassic",
        iconRes = R.mipmap.ic_launcher_classic,
        previewRes = R.mipmap.ic_launcher_classic,
    ),
    Gold(
        id = "gold",
        titleKey = "app.icon.gold",
        componentAlias = "com.armsx2.BootSplashActivityGold",
        iconRes = R.mipmap.ic_launcher_gold,
        previewRes = R.mipmap.ic_launcher_gold,
    ),
    Retro(
        id = "retro",
        titleKey = "app.icon.retro",
        componentAlias = "com.armsx2.BootSplashActivityRetro",
        iconRes = R.mipmap.ic_launcher_retro,
        previewRes = R.mipmap.ic_launcher_retro,
    ),
    Minimal(
        id = "minimal",
        titleKey = "app.icon.minimal",
        componentAlias = "com.armsx2.BootSplashActivityMinimal",
        iconRes = R.mipmap.ic_launcher_minimal,
        previewRes = R.mipmap.ic_launcher_minimal,
    );

    companion object {
        fun fromId(id: String?): AppIcon =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Default
    }
}

/**
 * Manages launcher app icon selection via PackageManager component enabled setting
 * and activity-alias switching.
 */
object AppIconManager {
    private const val PREFS_KEY = "ui.app_icon"
    private const val LEGACY_PREFS_NAME = "armsx2"
    private const val LEGACY_PREF_KEY = "app_icon_selection"

    /** Current selected launcher icon (reactive Compose state). */
    val currentIcon = mutableStateOf(AppIcon.Default)

    /**
     * Load the saved app icon preference on startup (including migration from legacy v1 settings).
     */
    fun load(context: Context) {
        val prefs = MainActivityRuntime.prefs
        var savedId = runCatching { prefs.getString(PREFS_KEY, null) }.getOrNull()

        // Migration from legacy version 1 armsx2.xml if present
        if (savedId == null) {
            runCatching {
                val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                val legacyId = legacyPrefs.getString(LEGACY_PREF_KEY, null)
                if (legacyId != null) {
                    savedId = when {
                        legacyId.contains("classic", ignoreCase = true) -> AppIcon.Classic.id
                        legacyId.contains("gold", ignoreCase = true) -> AppIcon.Gold.id
                        legacyId.contains("retro", ignoreCase = true) -> AppIcon.Retro.id
                        legacyId.contains("minimal", ignoreCase = true) -> AppIcon.Minimal.id
                        else -> AppIcon.Default.id
                    }
                    prefs.edit { putString(PREFS_KEY, savedId) }
                }
            }
        }

        currentIcon.value = AppIcon.fromId(savedId)
    }

    /**
     * Switch the active launcher icon dynamically using PackageManager.
     * Enables the selected activity alias and disables the rest.
     */
    fun setAppIcon(context: Context, icon: AppIcon): Boolean {
        currentIcon.value = icon
        runCatching { MainActivityRuntime.prefs.edit { putString(PREFS_KEY, icon.id) } }

        val pm = context.packageManager
        val packageName = context.packageName
        val baseComponentName = ComponentName(packageName, "com.armsx2.BootSplashActivity")
        val targetAlias = icon.componentAlias

        // needs proper testing on launcher alias reload across various Android vendors
        return try {
            if (targetAlias == null) {
                pm.setComponentEnabledSetting(
                    baseComponentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            } else {
                pm.setComponentEnabledSetting(
                    ComponentName(packageName, targetAlias),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }

            for (entry in AppIcon.entries) {
                val alias = entry.componentAlias
                if (entry == AppIcon.Default) {
                    if (targetAlias != null) {
                        pm.setComponentEnabledSetting(
                            baseComponentName,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP,
                        )
                    }
                } else if (alias != null && alias != targetAlias) {
                    pm.setComponentEnabledSetting(
                        ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Optional: Update recent tasks description with the active icon bitmap.
     */
    fun applyTaskDescription(activity: Activity) {
        try {
            val icon = currentIcon.value
            val bitmap = BitmapFactory.decodeResource(activity.resources, icon.iconRes) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val description = ActivityManager.TaskDescription.Builder()
                    .setLabel(activity.getString(R.string.app_name))
                    .setIcon(icon.iconRes)
                    .build()
                activity.setTaskDescription(description)
            } else {
                @Suppress("DEPRECATION")
                val description = ActivityManager.TaskDescription(
                    activity.getString(R.string.app_name),
                    bitmap,
                )
                @Suppress("DEPRECATION")
                activity.setTaskDescription(description)
            }
        } catch (_: Throwable) {
        }
    }
}
