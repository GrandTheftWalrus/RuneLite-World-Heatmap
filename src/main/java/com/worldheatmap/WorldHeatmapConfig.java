package com.worldheatmap;

import java.util.EnumSet;
import java.util.Set;
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

    @ConfigItem(
            keyName = "isUploadEnabled",
            name = "Contribute to the global heatmap",
            position = 0,
            description = "Enable automatic uploading of anonymized heatmap data to contribute to the global heatmap at osrsworldheatmap.com.",
            section = settings,
			warning = "This will upload your anonymized data to the global heatmap at https://osrsworldheatmap.com, a 3rd party website not controlled or verified by the RuneLite Developers. Individual heatmap data is only displayed in aggregate with all global data, and no personally identifying metadata is stored."
    )
    default boolean isUploadEnabled() {
        return false;
    }

    @Range(
            min = 100
    )
    @ConfigItem(
            keyName = "ImageAutosaveFrequency",
            name = "Type A&B image autosave frequency",
            position = 1,
            description = "Determines how often (in number of steps) to automatically save both the 'Type A' and 'Type B' world heatmap TIF images. Default value of 36000 ticks (6 hours)",
            section = settings
    )
    default int typeABImageAutosaveFrequency() {
        return 36000;
    }

    @ConfigItem(
            keyName = "ImageAutosave",
            name = "Type A&B image autosave",
            position = 2,
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
            position = 3,
            description = "Determines how often (in ticks of game time) to make a new backup of the heatmap data, in the Heatmap 'Results/Backups' folder (don't set this field too low or there will be too many backups piling up). Default value 36000 ticks (6 hours). Minimum value 100.",
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
            position = 4,
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
            position = 5,
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
            position = 6,
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

	@ConfigItem(
			keyName = "isBlueMapEnabled",
			name = "Blue map",
			position = 8,
			description = "If enabled, the world map will be coloured blue instead of grayscale.",
			section = settings
	)
	default boolean isBlueMapEnabled() {
		return true;
	}

	@ConfigItem(
		keyName = "enabledHeatmaps",
		name = "Enabled heatmaps",
		position = 9,
		description = "Which heatmaps are recorded. Ctrl-click to toggle one, Shift-click to select a range. A plain click selects only that heatmap. Changes apply when you click away from the list; disabled heatmaps are saved and unloaded.",
		section = settings
	)
	default Set<Heatmap.HeatmapType> enabledHeatmaps() {
		return EnumSet.allOf(Heatmap.HeatmapType.class);
	}

	@ConfigSection(
		name = "Overlays",
		description = "Live heatmap overlays on the game scene, minimap and world map",
		position = 1
	)
	String overlays = "overlays";

	@ConfigItem(
		keyName = "overlayHeatmapType",
		name = "Overlay heatmap",
		position = 0,
		description = "Which heatmap the overlays display. The heatmap must also be in 'Enabled heatmaps'.",
		section = overlays
	)
	default Heatmap.HeatmapType overlayHeatmapType() {
		return Heatmap.HeatmapType.TYPE_A;
	}

	@ConfigItem(
		keyName = "showSceneOverlay",
		name = "Show on game scene",
		position = 1,
		description = "Tints tiles in the game scene with their heatmap colour",
		section = overlays
	)
	default boolean showSceneOverlay() {
		return false;
	}

	@ConfigItem(
		keyName = "showMinimapOverlay",
		name = "Show on minimap",
		position = 2,
		description = "Draws heatmap colours on the minimap",
		section = overlays
	)
	default boolean showMinimapOverlay() {
		return false;
	}

	@ConfigItem(
		keyName = "showWorldMapOverlay",
		name = "Show on world map",
		position = 3,
		description = "Draws the whole heatmap on the world map",
		section = overlays
	)
	default boolean showWorldMapOverlay() {
		return false;
	}

	@Range(
		min = 0,
		max = 255
	)
	@ConfigItem(
		keyName = "overlayOpacity",
		name = "Overlay opacity",
		position = 4,
		description = "Opacity of the overlay colours (0-255)",
		section = overlays
	)
	default int overlayOpacity() {
		return 100;
	}

	@Range(
		min = -1
	)
	@ConfigItem(
		keyName = "overlaySceneRadius",
		name = "Game scene radius",
		position = 5,
		description = "Radius in tiles around the player to draw on the game scene. -1 draws the whole loaded scene.",
		section = overlays
	)
	default int overlaySceneRadius() {
		return -1;
	}

	@ConfigItem(
		keyName = "overlayUnderObjects",
		name = "Game scene: draw on floor",
		position = 6,
		description = "Draws the game scene overlay underneath players, NPCs, walls, scenery and ground items, so it looks like it's on the floor. Warning: can be expensive in busy areas; lower the game scene radius if it hurts your FPS.",
		section = overlays
	)
	default boolean overlayUnderObjects() {
		return false;
	}

	@Range(
		min = -1
	)
	@ConfigItem(
		keyName = "overlayMinimapRadius",
		name = "Minimap radius",
		position = 7,
		description = "Radius in tiles around the player to draw on the minimap. -1 draws the whole loaded scene.",
		section = overlays
	)
	default int overlayMinimapRadius() {
		return -1;
	}
}
