package com.worldheatmap;

import net.runelite.client.config.*;

@ConfigGroup(WorldHeatmapConfig.GROUP)
public interface WorldHeatmapConfig extends Config {
    String GROUP = "worldheatmap";

    @ConfigSection(
            name = "Main Settings",
            description = "Settings for main plugin functionality",
            position = 0
    )
    String settings = "settings";

    @Range(
            min = 100
    )
    @ConfigItem(
            keyName = "ImageAutosaveFrequency",
            name = "Type A&B image autosave frequency",
            position = 0,
            description = "Determines how often (in number of steps) to automatically save both the 'Type A' and 'Type B' world heatmap TIF images. Default value of 36000 ticks (6 hours)",
            section = settings
    )
    default int typeABImageAutosaveFrequency() {
        return 36000;
    }

    @ConfigItem(
            keyName = "ImageAutosave",
            name = "Type A&B image autosave",
            position = 1,
            description = "Determines whether to automatically save both the 'Type A' and 'Type B' world heatmap TIF images.",
            section = settings
    )
    default boolean typeABImageAutosave() {
        return false;
    }

    @Range(
            min = 100
    )
    @ConfigItem(
            keyName = "HeatmapBackupFrequency",
            name = "Data backup frequency",
            position = 2,
            description = "Determines how often (in ticks of game time) to make a new backup of the heatmap data, in the Heatmap 'Results/Backups' folder (don't set this field too low or there will be too many backups piling up). Default value 36000 ticks (6 hours). Minimum value 50.",
            section = settings
    )
    default int heatmapBackupFrequency() {
        return 36000;
    }

    @Range(
            min = 0,
            max = 1
    )
    @ConfigItem(
            keyName = "HeatmapAlpha",
            name = "Heatmap colour alpha",
            position = 3,
            description = "The opacity of the heatmap colours drawn over the world map image",
            section = settings
    )
    default double heatmapAlpha() {
        return 1.0;
    }

    @Range(
            min = 1,
            max = 6
    )
    @ConfigItem(
            keyName = "heatmapSensitivity",
            name = "Heatmap curve sensitivity",
            position = 4,
            description = "Increasing this makes the heatmap's colour gradient more sensitive to step counts.",
            section = settings
    )
    default int heatmapSensitivity() {
        return 4;
    }

    @Range(
            max = 8
    )
    @ConfigItem(
            keyName = "speedMemoryTradeoff",
            name = "Speed-memory tradeoff",
            position = 5,
            description = "Corresponds to the vertical size of chunks used in writing the heatmap image. Higher values = faster image writing, but increases memory usage. Try lowering this value if the plugin is crashing whilst writing images.",
            section = settings
    )
    default int speedMemoryTradeoff() {
        return 4;
    }

    @ConfigItem(
            keyName = "writeFullMapImage",
            name = "Write full world map image (SLOW)",
            position = 7,
            description = "Warning: This takes much longer than writing just the overworld image. If checked, this will write the full world map image (including underground/non-overworld areas). Does not apply to image autosaves.",
            section = settings
    )
    default boolean isWriteFullImageEnabled() {
        return false;
    }

    @ConfigSection(
            name = "Per-Heatmap On/Off",
            description = "Enabling/disabling individual heatmaps",
            position = 1
    )
    String heatmapsOnOff = "heatmapsOnOff";

    @ConfigItem(
            keyName = "isHeatmapTypeAEnabled",
            name = "TYPE_A",
            position = 0,
            description = "Increments a tile each time you walk over it",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTypeAEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapTypeBEnabled",
            name = "TYPE_B",
            position = 1,
            description = "Increments a tile each tick you stand on it",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTypeBEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapXPGainedEnabled",
            name = "XP_GAINED",
            position = 2,
            description = "Records the total number of XP gained in each tile",
            section = heatmapsOnOff
    )
    default boolean isHeatmapXPGainedEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapTeleportPathsEnabled",
            name = "TELEPORT_PATHS",
            position = 3,
            description = "Records the paths taken when teleporting",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTeleportPathsEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapTelportedToEnabled",
            name = "TELEPORTED_TO",
            position = 4,
            description = "Records the locations teleported to",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTeleportedToEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapTeleportedFromEnabled",
            name = "TELEPORTED_FROM",
            position = 5,
            description = "Records the locations teleported from",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTeleportedFromEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapLootValueEnabled",
            name = "LOOT_VALUE",
            position = 6,
            description = "Records the total value of loot spawned on each tile",
            section = heatmapsOnOff
    )
    default boolean isHeatmapLootValueEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapPlacesSpokenAtEnabled",
            name = "PLACES_SPOKEN_AT",
            position = 7,
            description = "Records the number of times you've spoken in public chat at each tile",
            section = heatmapsOnOff
    )
    default boolean isHeatmapPlacesSpokenAtEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapRandomEventSpawnsEnabled",
            name = "RANDOM_EVENT_SPAWNS",
            position = 8,
            description = "Records the number of witnessed random event spawns on each tile",
            section = heatmapsOnOff
    )
    default boolean isHeatmapRandomEventSpawnsEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapDeathsEnabled",
            name = "DEATHS",
            position = 9,
            description = "Records the number of times you've died on each tile",
            section = heatmapsOnOff
    )
    default boolean isHeatmapDeathsEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapNPCDeathsEnabled",
            name = "NPC_DEATHS",
            position = 10,
            description = "Records the number of NPC deaths you've witnesssed on each tile",
            section = heatmapsOnOff
    )
    default boolean isHeatmapNPCDeathsEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapBobTheCatSightingEnabled",
            name = "BOB_THE_CAT_SIGHTING",
            position = 11,
            description = "Records the tiles on which you've found Bob the Cat (this is regular bob, not evil bob)",
            section = heatmapsOnOff
    )
    default boolean isHeatmapBobTheCatSightingEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapDamageTakenEnabled",
            name = "DAMAGE_TAKEN",
            position = 12,
            description = "Records the total you've damage taken on each tile",
            section = heatmapsOnOff
    )
    default boolean isHeatmapDamageTakenEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapDamageGivenEnabled",
            name = "DAMAGE_GIVEN",
            position = 13,
            description = "Records the total damage you've dealt whilst standing on each tile",
            section = heatmapsOnOff
    )
    default boolean isHeatmapDamageGivenEnabled() {
        return true;
    }

}
