# Settings Screen Specification

## Overview

`SettingsActivity` — Material3 settings UI. Navigation via `ViewFlipper` (index-based, no Fragments).

**Layout file:** `activity_settings_new.xml`

---

## Layout Structure

```
activity_settings_new.xml
├── MaterialToolbar (title = "Settings")
├── ViewFlipper (@id/view_flipper_settings)
│   ├── [0] Section menu (list of setting categories)
│   ├── [1] General      — include_settings_card_general.xml
│   ├── [2] Graphics     — include_settings_card_graphics.xml
│   ├── [3] Performance  — include_settings_card_performance.xml
│   ├── [4] Controllers  — include_settings_card_controller.xml
│   ├── [5] Storage      — include_settings_card_storage.xml
│   ├── [6] Memory Cards — include_settings_card_memory.xml
│   ├── [7] DEV9         — include_settings_card_dev9.xml
│   ├── [8] Achievements — include_settings_card_achievements.xml
│   ├── [9] App Icon     — include_settings_card_app_icon.xml
│   └── [10] Stats       — include_settings_card_stats.xml
└── LinearLayout (footer)
    └── MaterialButton btn_back → finish()
```

---

## Footer

**Removed:** About button.  
**Kept:** Back button only (`btn_back`, `wrap_content` width, Material3 style).

```xml
<com.google.android.material.button.MaterialButton
    android:id="@+id/btn_back"
    style="@style/Widget.Material3.Button"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/onboarding_back" />
```

---

## Section: Performance

Contains CPU/renderer performance toggles. Settings read via `NativeApp.getSetting()`.

**Crash guard:** `NativeApp.initializeOnce()` must be called before this section loads.
Ensured by `App.onCreate()` init (see `architecture-decisions.md`).

### Performance Preset Spinner

Spinner `sp_performance_preset` (top of the card) allows the user to pick a preset that writes a coherent set of native settings in one action:

| Position | Value (`AndroidPerformancePreset`) | Label |
|----------|:-----------------------------------:|-------|
| 0 | 0 | Best performance *(default)* |
| 1 | 1 | Balanced |
| 2 | 2 | Best quality |

Labels are localized via `R.array.performance_presets` (EN/PT-BR/AR/ZH-CN).
Implemented in `SettingsActivity.initializePerformancePreset()` and `applyPerformancePreset()`.
See `docs/performance-optimization.md` for the full table of values per preset.

### Renderer Spinner — Default Correction

The initial selection of `sp_renderer` now maps `null`/empty/`-1` to position `0` ("Auto") instead of position `1` ("OpenGL").

---

## Section: Storage

- Data root path display + change button
- Opens system file picker via `ActivityResultLauncher<Intent>`
- Validates path via `DataDirectoryManager`

---

## Section: Controllers

- Lists connected controllers
- Opens `ControllerMappingDialog` per controller
- Mapping persisted via `ControllerMappingManager`

---

## Section: Achievements

- RetroAchievements login via `RetroAchievementsBridge`
- Username / password / token display
- Login dialog: `dialog_retroachievements_login.xml`

---

## Section: App Icon

- Lists available icons from `res/drawable/ic_launcher_*`
- Selection via `dialog_list_item_app_icon.xml` list
- Applied via `AppIconManager`

---

## Navigation Logic

```java
// Show section at index i
viewFlipper.setDisplayedChild(i);

// Back from section → main menu
viewFlipper.setDisplayedChild(0);

// Back from main menu → finish()
finish();
```

Animations set via `viewFlipper.setInAnimation()` / `setOutAnimation()` using `AnimationUtils`.
