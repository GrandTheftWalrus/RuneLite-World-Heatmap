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
            min = 30
    )
    @ConfigItem(
            keyName = "ImageAutosaveFrequency",
            name = "Type A&B image autosave frequency",
            position = 0,
            description = "This determines how often (in number of steps) to automatically save both the 'Type A' and 'Type B' world heatmap TIF images. Default value of 2000 ticks (20 minutes). Minimum value of 50.",
            section = settings
    )
    default int typeABImageAutosaveFrequency() {
        return 2000;
    }

    @ConfigItem(
            keyName = "ImageAutosave",
            name = "Type A&B image autosave",
            position = 1,
            description = "This determines whether to automatically save both the 'Type A' and 'Type B' world heatmap TIF images. Default value of false.",
            section = settings
    )
    default boolean typeABImageAutosave() {
        return false;
    }

    @Range(
            min = 30
    )
    @ConfigItem(
            keyName = "HeatmapBackupFrequency",
            name = "Data backup frequency",
            position = 2,
            description = "This determines how often (in ticks of game time) to make a new backup of the heatmap data, with the time and date appended to the file. These files remain in the Heatmap 'Results/Backups' folder until deleted by the user (so don't set this field too low or there will be way too many backups). Default value 36000 ticks (6 hours). Minimum value 50.",
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
        return 0.65;
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
            max = 9
    )
    @ConfigItem(
            keyName = "speedMemoryTradeoff",
            name = "Speed-memory tradeoff",
            position = 5,
            description = "Corresponds to the vertical size of chunks used in writing the heatmap image. Increasing this will increase the speed of writing the image, but will also increase the memory usage. Lower this value if you are running out of memory when writing the image.",
            section = settings
    )
    default int speedMemoryTradeoff() {
        return 4;
    }

    @ConfigItem(
            keyName = "writeFullMapImage",
            name = "Write full world map image (SLOW)",
            position = 6,
            description = "Warning: This takes much longer than writing just the overworld image. If checked, this will write the full world map image (including underground/non-overworld areas). If unchecked, this will write just the overworld image. Does not apply to image autosaves. Default value of false.",
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
            description = "Enable/disable the 'Type A' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTypeAEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapTypeBEnabled",
            name = "TYPE_B",
            position = 1,
            description = "Enable/disable the 'Type B' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTypeBEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapXPGainedEnabled",
            name = "XP_GAINED",
            position = 2,
            description = "Enable/disable the 'XP_GAINED' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapXPGainedEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapTeleportPathsEnabled",
            name = "TELEPORT_PATHS",
            position = 3,
            description = "Enable/disable the 'XP_HOUR' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTeleportPathsEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapTelportedToEnabled",
            name = "TELEPORTED_TO",
            position = 4,
            description = "Enable/disable the 'TELEPORTED_TO' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTeleportedToEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapTeleportedFromEnabled",
            name = "TELEPORTED_FROM",
            position = 5,
            description = "Enable/disable the 'TELEPORTED_FROM' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapTeleportedFromEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapLootValueEnabled",
            name = "LOOT_VALUE",
            position = 6,
            description = "Enable/disable the 'LOOT_VALUE' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapLootValueEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapPlacesSpokenAtEnabled",
            name = "PLACES_SPOKEN_AT",
            position = 7,
            description = "Enable/disable the 'PLACES_SPOKEN_AT' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapPlacesSpokenAtEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapRandomEventSpawnsEnabled",
            name = "RANDOM_EVENT_SPAWNS",
            position = 8,
            description = "Enable/disable the 'RANDOM_EVENT_SPAWNS' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapRandomEventSpawnsEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapDeathsEnabled",
            name = "DEATHS",
            position = 9,
            description = "Enable/disable the 'DEATHS' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapDeathsEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapNPCDeathsEnabled",
            name = "NPC_DEATHS",
            position = 10,
            description = "Enable/disable the 'NPC_DEATHS' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapNPCDeathsEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapBobTheCatSightingEnabled",
            name = "BOB_THE_CAT_SIGHTING",
            position = 11,
            description = "Enable/disable the 'BOB_THE_CAT_SIGHTING' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapBobTheCatSightingEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapDamageTakenEnabled",
            name = "DAMAGE_TAKEN",
            position = 12,
            description = "Enable/disable the 'DAMAGE_TAKEN' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapDamageTakenEnabled() {
        return true;
    }

    @ConfigItem(
            keyName = "isHeatmapDamageGivenEnabled",
            name = "DAMAGE_GIVEN",
            position = 13,
            description = "Enable/disable the 'DAMAGE_GIVEN' heatmap",
            section = heatmapsOnOff
    )
    default boolean isHeatmapDamageGivenEnabled() {
        return true;
    }

}
