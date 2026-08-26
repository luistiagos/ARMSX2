# UI Redesign — RetroSystem PS2

## Goal

Replace legacy ARMSX2 game selector (ListView) with Material3 home screen. Maintain emulation core untouched.

## New Activity Flow

```
BootSplashActivity
    └── OnboardingActivity (first run only — BIOS + storage setup)
            └── HomeActivity (main hub)
                    ├── NavigationDrawer (left)
                    ├── BottomNavigationView (Catalog / My Games tabs)
                    └── Settings icon (toolbar top-right) → SettingsActivity
```

`MainActivity` remains for legacy emulation launch path (`startEmulation()` JNI entry point).

---

## HomeActivity

**Layout:** `activity_home.xml`

### Toolbar
- `MaterialToolbar` (`@id/toolbar_home`) — standalone, NOT bound via `setSupportActionBar()`
- Left: hamburger → opens `DrawerLayout`
- Right: gear icon → launches `SettingsActivity`
- Menu inflated via `toolbar.inflateMenu(R.menu.menu_home_toolbar)` + `setOnMenuItemClickListener()`

> **Why standalone toolbar:** `setSupportActionBar()` transfers ownership to AppCompat, which silently ignores `inflateMenu()` calls. Icons never appear. Must NOT call `setSupportActionBar()` when using `inflateMenu()` directly.

### Navigation Drawer
- Header: `nav_header.xml` — 180dp height, cover image with gradient overlay, title "RetroSystem PS2"
- Menu items: Home, Catalog, My Games, Settings

### BottomNavigationView
- Tab 0: Catalog (remote ROM list with covers)
- Tab 1: My Games (locally downloaded ROMs)
- Uses `ViewFlipper` or `RecyclerView` swap to switch content

### Game Grid
- `RecyclerView` with `GridLayoutManager` (2 columns default)
- Items: `item_game.xml` — cover image + title + badge
- Covers downloaded async via `ExecutorService` (2 threads), cached to `covers/` subdir
- Placeholder shown while loading; error placeholder on failure

### Search
- `EditText` with `TextWatcher` — filters `allEntries` list in real time
- Filters both catalog and downloaded tabs

---

## SettingsActivity

**Layout:** `activity_settings_new.xml`  
**Navigation:** `ViewFlipper` (index-based section switching, NOT Fragments)

### Sections (ViewFlipper children)
0. Main menu (section list)
1. General
2. Graphics
3. Performance
4. Controllers
5. Storage
6. Memory Cards
7. DEV9 (network/HDD)
8. Achievements (RetroAchievements)
9. App Icon
10. Stats / Debug

### Footer
Single **Back** button (`btn_back`) — calls `finish()`.
No "About" button (removed).

### Section Cards
Each section uses `include_settings_card_*.xml` fragments included into the ViewFlipper pane.
Settings read/write via `NativeApp.getSetting(key)` / `NativeApp.setSetting(key, value)`.

> **Critical:** `NativeApp.initializeOnce()` must run before any `getSetting()` call. Moved to `App.onCreate()` to avoid crash when SettingsActivity opens without MainActivity.

---

## Nav Header

**File:** `res/layout/nav_header.xml`

```
FrameLayout 180dp
├── ImageView header_image (centerCrop — game cover or default art)
├── ImageView header_image_blur (gradient_header_fade_up overlay, alpha 0.9)
└── LinearLayout (bottom-gravity, 16dp padding)
    ├── TextView "RetroSystem PS2" (bold, 20sp)
    └── TextView subtitle (14sp, colorOnPrimaryContainer)
```

---

## Onboarding

**Layout:** `activity_onboarding.xml` + `onboarding_page_*.xml`

3-page flow:
1. Welcome — app intro
2. Storage — request permissions, set data directory
3. BIOS — pick BIOS file from device

Completes → `HomeActivity`. Never shown again (SharedPref flag).

---

## Removed / Changed vs Legacy

| Legacy | New |
|--------|-----|
| `activity_game_selector.xml` ListView | `RecyclerView` grid with covers |
| `setSupportActionBar()` + `onCreateOptionsMenu()` | Standalone `MaterialToolbar` + `inflateMenu()` |
| About button in Settings footer | Removed |
| App name "ARMSX2" everywhere | "RetroSystem PS2" (user-visible only) |
