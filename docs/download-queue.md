# Save / Download Queue

## Terminology

All user-facing text uses "Save / Saved" instead of "Download / Downloaded".  
Internally the code still uses `download*` names for stability (no rename of JNI, SharedPrefs, file paths).

---

## User Flow

1. User taps a game in **Catalog** tab.
2. Dialog: **"Save and play this game?"** — buttons **Cancel** / **Save**.
3. On **Save**: entry is added to `DownloadQueueManager` queue → app switches to **Saved** tab automatically.
4. Download runs in background (no blocking popup).
5. On complete: entry moves from queue section into the **Saved Games** grid.

---

## Saved Tab Layout

```
NestedScrollView (scroll_saved)
└── LinearLayout (vertical)
    ├── [QUEUE SECTION] ll_queue_section  — visible only when queue non-empty
    │   ├── Section header: "SAVING"
    │   └── ll_queue_items  — one item_download_queue.xml card per entry
    │
    ├── tv_saved_section_header  — "SAVED GAMES" — visible when completed games exist
    └── rv_home_saved  — GridLayoutManager(3 cols), most recent first
        └── item_catalog.xml tiles (tap to launch)
```

Empty state (`tv_home_saved_empty`) shown only when both queue and saved list are empty.

---

## Queue Item Card (`item_download_queue.xml`)

Two action buttons always present:
- `btn_queue_action` — primary state-specific action (hidden when QUEUED)
- `btn_queue_cancel` — always visible ✕, removes entry from queue regardless of state

| State | Status label | Progress bar | btn_queue_action | btn_queue_cancel |
|-------|-------------|--------------|------------------|-----------------|
| QUEUED | "Waiting…" | hidden | hidden | ✕ remove |
| DOWNLOADING | "X.X MB of Y.Y MB (Z%)" | visible, fills | ⏸ pause | ✕ cancel |
| PAUSED | "Paused" | visible, frozen | ▶ resume | ✕ cancel |

---

## DownloadQueueManager

**File:** `catalog/DownloadQueueManager.java`  
Singleton (`get()`). Wraps one `RomDownloadManager` instance; processes entries FIFO.

### States (`DownloadQueueManager.State`)

| State | Meaning |
|-------|---------|
| `QUEUED` | In queue, waiting for active download to finish |
| `DOWNLOADING` | Active download in progress |
| `PAUSED` | Active download paused by user |
| `DONE` | Completed — entry removed from queue, `isDownloaded = true` |
| `ERROR` | Failed — removed from queue silently |

### Key methods

```java
DownloadQueueManager.get().setRomsDir(romsDir);  // call once, e.g. HomeActivity.onCreate()
DownloadQueueManager.get().enqueue(entry);        // add to queue
DownloadQueueManager.get().pause(entry);          // pause active
DownloadQueueManager.get().resume(entry);         // resume paused
DownloadQueueManager.get().remove(entry);         // cancel + remove
DownloadQueueManager.get().getActiveQueue();      // snapshot List<CatalogEntry>
DownloadQueueManager.get().addListener(listener); // QueueListener callbacks on main thread
```

### QueueListener

```java
interface QueueListener {
    void onQueueChanged();       // entry added/removed/state changed
    void onProgress(CatalogEntry entry);  // progress update for active download
}
```

`HomeActivity` implements `QueueListener`. `onQueueChanged()` triggers `rebuildQueueViews()` and moves completed entries into `savedEntries`.

---

## CatalogEntry fields (queue-related)

| Field | Type | Purpose |
|-------|------|---------|
| `queueState` | `DownloadQueueManager.State` | null = not in queue |
| `isDownloading` | boolean | true while DOWNLOADING |
| `isPaused` | boolean | true while PAUSED |
| `downloadProgress` | float 0–1 | progress fraction |
| `downloadedBytes` | long | bytes received |
| `totalBytes` | long | total file size |
| `savedAtMs` | long | `System.currentTimeMillis()` on complete — used for sort order |

---

## Sort Order (Saved Games grid)

Most recently saved first: `savedEntries` sorted by `savedAtMs` descending.  
Pre-existing entries (detected via `CatalogParser.markDownloaded()` on app start) have `savedAtMs = 0` and appear last.

---

## Background Download

`RomDownloadManager` runs on a dedicated `Thread("RomDownload")`.  
Supports HTTP 206 resume (Range header) — interrupted downloads continue from last byte on retry.  
URL resolution: queries `find_by_file` endpoint first, falls back to HuggingFace dataset URL.

---

## Download Resume on App Restart

If the app is killed mid-download, the `.part` file remains in `romsDir`.  
On next `HomeActivity.onCreate()`, `restoreInterruptedDownloads()` scans for `*.part` files and re-enqueues the matching `CatalogEntry`.  
`RomDownloadManager` detects the existing partial file and sends a `Range: bytes=N-` header — the server responds HTTP 206 and the download continues from the last byte.

---

## Catalog Queue Indicators

In-queue entries show two overlays on their catalog tile (`item_catalog.xml`):
- `iv_catalog_queue_icon` — hourglass icon (top-left corner), visible when QUEUED, DOWNLOADING, or PAUSED
- `pb_catalog_download` — thin progress bar at bottom of cover image, visible only when DOWNLOADING

Tapping an in-queue tile navigates to the Saved tab instead of showing the confirm dialog.

---

## Removed

- `CatalogDownloadActivity` — transparent activity with blocking popup dialog — no longer used by `HomeActivity`. Still exists for legacy `CatalogActivity` compatibility.
