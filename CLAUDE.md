# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A RuneLite (Old School RuneScape client) external plugin that records per-tile counts of player activity (walking, XP, loot, deaths, teleports, damage, etc.) and renders them as heatmap images over the world map. Users can opt in to upload anonymized data to https://osrsworldheatmap.com. Distributed via the RuneLite Plugin Hub (`runelite-plugin.properties`, `build=standard`).

## Commands

- Build: `./gradlew build`
- Run RuneLite with the plugin loaded (dev client): run `com.worldheatmap.WorldHeatmapTest` from the test source set (e.g. from the IDE). It calls `ExternalPluginManager.loadBuiltin(WorldHeatmapPlugin.class)` then `RuneLite.main`. `loadHeatmaps()` uses `assert`s on player metadata, so run with `-ea` to catch them.
- Standalone runnable jar of the above: `./gradlew shadowJar` → `build/libs/worldheatmap-<version>-all.jar`
- There are no unit tests; `WorldHeatmapTest` is only a launcher.

Compiles against `net.runelite:client:latest.release` with `options.release = 11` — stay within Java 11 language/API and the RuneLite client API (no extra runtime dependencies are declared). Lombok is available. Version is set in `build.gradle`.

## Architecture

All code is in `src/main/java/com/worldheatmap/`.

- **`WorldHeatmapPlugin`** — entry point. `@Subscribe` event handlers (GameTick, StatChanged, ActorDeath, HitsplatApplied, NpcSpawned, NpcLootReceived, ChatMessage, ConfigChanged, etc.) increment tiles in `Map<HeatmapType, Heatmap> heatmaps`. Heatmaps load lazily on the first `GameTick` after login (needs account hash, account type, combat level, seasonal type) via `loadHeatmaps()`, gated by the `loading` CompletableFuture; event handlers no-op until it completes. The game-tick handler also kicks off backup/autosave/upload routines.
- **`Heatmap`** — one data type: `HashMap<WorldPoint, Long>` plus metadata (userID/account hash, accountType, seasonalType, combat level, gameTimeTicks). `HeatmapType` enum lists all types (every value appears in the config list, so don't add sentinel values). `heatmapVersion` (currently 103) is written into each file and drives migration logic — bump it when the format or data semantics change.
- **`HeatmapFileManager`** — persistence. A `.heatmaps` file is a zip of `<TYPE>_HEATMAP.csv` entries (CSV: header row of field names, row of field values, then `x,y,z,value` lines; legacy `x,y,value` is still read). Files live under the plugin directory in `Heatmap Files/<accountHash>[_<seasonalType>]/yyyy-MM-dd_HH-mm.heatmaps`; the newest-dated filename is the "current" file. Writes go to a temp file and are moved into place, and entries for types not being written are carried over from the previous file (so disabled heatmap types are preserved). `fixHeatmapsFiles()` runs version-gated backward-compatibility migrations on load (e.g. V1.6.0 file naming fix, V1.6.1 deaths-counted-as-teleports fix).
- **`HeatmapImage`** — implements `RenderedImage` to write huge TIFF images tile-by-tile (memory constraints of RuneLite plugins). It streams the base world map PNG and `offsets.csv` from the `GrandTheftWalrus/gtw-runelite-stuff` GitHub repo, and colors tiles via `processImageRegion` in natural reading order from a sorted queue. The `speedMemoryTradeoff` config controls chunk size. Images go to `Heatmap Images/<accountHash>[_<seasonalType>]/`.
- **Overlays** — `HeatmapSceneOverlay`, `HeatmapMinimapOverlay` and `HeatmapWorldMapOverlay` (scene/minimap/clip code adapted from skretzo's Invalid Movement plugin) all read `HeatmapOverlayCache`, which is refreshed from `onGameTick` and holds the selected `overlayHeatmapType`, its min/max, and 64×64 per-region images (planes summed) for the world map. Colours come from `Utils.heatmapHue`/`heatmapColor`, shared with `HeatmapImage`. `overlayHeatmapType` is an `OverlayHeatmap` (every `HeatmapType` plus `NONE`).
- **World map selector** — `WorldMapHeatmapSelector` adds a button to the world map's bottom bar (dynamic widgets copied from the location selector, re-laid out every `ClientTick`); its open list `WorldMapHeatmapList` is an overlay + mouse listener rather than widgets, because RuneLite's world map icon overlay draws over all world map widgets. Choosing sets the `overlayHeatmapType` config (an open config panel won't refresh until reopened).
- **`WorldHeatmapPanel`** — sidebar UI (write image, reset, open folder, etc.).
- **`Utils`** — line drawing between tiles (fills in running paths), overworld bounds check, and the zip+multipart upload to `osrsworldheatmap.com/api/upload-csv/` via the injected `OkHttpClient`.
- **`WorldHeatmapConfig`** — image/backup/upload/overlay settings (config group `worldheatmap`). Which heatmap types are recorded is a single `Set<HeatmapType> enabledHeatmaps` (multi-select list); check it via `plugin.isHeatmapEnabled(type)`. The old per-type boolean keys are migrated once in `migrateHeatmapToggles()` and then deleted.

### Conventions / gotchas

- File I/O uses RuneLite's `net.runelite.client.util.Filepath` (not `java.io.File`), and the plugin dir comes from `getPluginDirectory()`. Use `LinkBrowser` for opening URLs/folders.
- Heavy work (saving, image writing, uploading, loading) runs on the injected `ScheduledExecutorService executor`; Swing updates go through `SwingUtilities.invokeLater`; client access off the client thread goes through `clientThread`.
- Seasonal worlds (e.g. Leagues) are stored in separate directories keyed by `seasonalType`; empty string means a normal world.
- Teleport detection: movement > 15 tiles between ticks within the overworld (and not just after death); ≤ 3 tiles is treated as walking/running and the intermediate tiles are filled in.
- `changelog.txt` holds historical design notes.
