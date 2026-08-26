# Architecture Decisions

## Native Engine Initialization

**Decision:** Move `NativeApp.initializeOnce()` from `MainActivity` to `App.onCreate()`.

**Problem:** New flow `HomeActivity → SettingsActivity` bypasses `MainActivity` entirely. `SettingsActivity` calls `NativeApp.getSetting()` on load (Performance tab, etc.) — all are JNI methods that crash if the native library is not initialized.

**Solution:**
```java
// App.java — runs before any Activity
@Override
public void onCreate() {
    super.onCreate();
    Context appContext = getApplicationContext();
    if (!NativeApp.hasNoNativeBinary) {
        java.io.File dataDir = DataDirectoryManager.getDataRoot(appContext);
        if (dataDir != null) NativeApp.setDataRootOverride(dataDir.getAbsolutePath());
        NativeApp.initializeOnce(appContext);
    }
    DynamicColors.applyToActivitiesIfAvailable(this);
}
```

`setDataRootOverride()` must be called before `initializeOnce()` — sets the path where config/memcards/covers are stored.

---

## Toolbar Ownership Model

**Decision:** Use standalone `MaterialToolbar` (do NOT call `setSupportActionBar()`).

**Problem:** Calling `setSupportActionBar(toolbar)` transfers toolbar ownership to AppCompat. After that, `toolbar.inflateMenu()` is silently ignored — the toolbar never shows menu items.

**Solution:** Do not call `setSupportActionBar()`. Instead:
```java
toolbar.inflateMenu(R.menu.menu_home_toolbar);
toolbar.setOnMenuItemClickListener(item -> { ... });
```
Title set via `app:title` XML attribute or `toolbar.setTitle()`.

---

## Settings Navigation: ViewFlipper not Fragments

**Decision:** `SettingsActivity` uses `ViewFlipper` for section switching.

**Rationale:** The existing settings system relies on a `ViewFlipper` with indexed children. Migrating to `NavGraph` + Fragments is a large refactor outside the current scope. `ViewFlipper` is sufficient — transitions are animated via `AnimationUtils`.

---

## Renaming Strategy: User-Visible Only

**Decision:** Rename "ARMSX2" → "RetroSystem PS2" only in user-visible strings.

**Never rename:**
- Package ID: `come.nanodata.armsx2` — Play Store identity + existing user installs
- SharedPrefs keys — would lose all saved settings on update
- JNI method/class names — C++ bridge depends on exact Java class names
- File paths in data directory — would orphan existing user data

**Safe to rename:**
- `strings.xml` values, dialog text, toasts
- Display name in `DiscordBridge`, `ImGuiOverlays`
- User-Agent strings in HTTP clients
- Deploy script console messages

---

## Android Renderer Selection Strategy

**Decision:** Default renderer is `Auto (-1)`. On Android, `Auto` resolves to Vulkan only when the GPU passes `GSDeviceVK::IsSuitableDefaultRenderer()`; otherwise falls back to OpenGL.

**Problem:** Forcing OpenGL globally wastes performance on capable GPUs. Forcing Vulkan globally crashes on many devices (no Vulkan support, software renderers, old Mali).

**Solution:**

1. `GSUtil::GetPreferredRenderer()` — new `#elif defined(__ANDROID__)` branch:
   ```cpp
   if (GSDeviceVK::IsSuitableDefaultRenderer())
       preferred_renderer = GSRendererType::VK;
   // fallback to OGL if Vulkan not suitable
   ```
   `IsSuitableDefaultRenderer()` rejects `llvmpipe`, `SwiftShader`, Intel, and missing Vulkan.

2. `GSopen()` — Android-only runtime fallback:
   ```cpp
   if (!res && automatic_renderer && renderer != GSRendererType::OGL)
       // retry with OGL before failing
   ```
   If Vulkan device creation fails at runtime (rare but real), OpenGL is tried before showing an error to the user.

---

## Performance Defaults — Centralized Profile

**Decision:** All Android performance defaults live in `ApplyAndroidPerformanceDefaults()` and `MigrateAndroidPerformanceDefaults()` in `main.cpp`.

**Problem:** Defaults were duplicated verbatim in `NativeApp_initialize` and `NativeApp_reloadDataRoot` with hardcoded integers.

**Solution:** Single source of truth functions. Versioned via `ANDROID_PERFORMANCE_PROFILE_VERSION`. `MigrateAndroidPerformanceDefaults()` runs on every boot for existing configs and only writes values that changed since the last profile version, then saves once.

See `docs/performance-optimization.md` for the full rationale and value table.

---

## Catalog System

ROM catalog fetched from remote server. Flow:
1. `CatalogActivity` fetches JSON manifest
2. `CatalogParser` deserializes to `List<CatalogEntry>`
3. `HomeActivity` grid shows entries with async cover download
4. `RomDownloadManager` handles download + progress dialog
5. Downloaded ROMs stored in `DataDirectoryManager.getDataRoot()/roms/`

Cover images cached to `getDataRoot()/covers/<game_id>.jpg`.

---

## Build Variants

- `debug` — `come.nanodata.armsx2.debug` — for development, USB deploy
- `unrestricted` — production-like debug build without restrictions
- Gradle task: `installUnrestrictedDebug`
