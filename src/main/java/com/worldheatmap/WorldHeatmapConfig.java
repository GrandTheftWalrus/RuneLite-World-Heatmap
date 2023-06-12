package com.worldheatmap;

import net.runelite.client.config.*;

@ConfigGroup(WorldHeatmapConfig.GROUP)
public interface WorldHeatmapConfig extends Config
{
	String GROUP = "worldheatmap";

	@ConfigSection(
		name = "'Type A' Heatmap",
		description = "The 'Type A' heatmap's tiles are incremented each time they are stepped on",
		position = 0
	)
	String typeA = "type_a";

	@Range(
		min = 100
	)
	@ConfigItem(
		keyName = "typeAImageAutosaveFrequency",
		name = "Image Autosave Frequency",
		position = 0,
		description = "This determines how often (in number of steps) to automatically save the 'Type A' world heatmap PNG image. Default value of 1000 tiles (which equates to 5 mins of running, or 10 mins of walking). Minimum value of 100.",
		section = typeA
	)
	default int typeAImageAutosaveFrequency()
	{
		return 1000;
	}

	@ConfigItem(
		keyName = "typeAAutosaveOnOff",
		name = "Autosave Image to Disk",
		position = 1,
		description = "Should the 'Type A' internal heatmap matrix be automatically saved at the frequency specified above?",
		section = typeA
	)
	default boolean typeAImageAutosaveOnOff()
	{
		return true;
	}

	@Range(
		min = 100
	)
	@ConfigItem(
		keyName = "typeAHeatmapBackupFrequency",
		name = "Type A Backup Frequency",
		position = 2,
		description = "This determines how often (in number of steps) to make a new backup of the 'Type A' heatmap matrix, with the time and date appended to the image file. These files remain in the Heatmap 'Results/Backups' folder until deleted by the user (so don't set this field too low or there will be way too many backups), but the size of each file is only about on the order of 10-100kb. Default value 12,000 tiles (which is 1 hour of sprinting, or 2 hours of walking). Minimum value 100.",
		section = typeA
	)
	default int typeAHeatmapBackupFrequency()
	{
		return 12000;
	}

	@ConfigSection(
		name = "'Type B' Heatmap",
		description = "The 'Type B' heatmap's tiles are incremented each time they are stepped on, as well as once for every game tick that you are standing on them.",
		position = 1
	)
	String typeB = "type_b";

	@Range(
		min = 500
	)
	@ConfigItem(
		keyName = "typeBImageAutosaveFrequency",
		name = "Image Autosave Frequency",
		position = 0,
		description = "This determines how often (in number of steps + ticks) to automatically save the 'Type B' world heatmap PNG image to disk. Default value of 1000. Minimum value of 500.",
		section = typeB
	)
	default int typeBImageAutosaveFrequency()
	{
		return 1000;
	}

	@ConfigItem(
		keyName = "typeBAutosaveOnOff",
		name = "Autosave Image to Disk",
		position = 1,
		description = "Should the 'Type B' internal heatmap matrix be automatically saved at the frequency specified above?",
		section = typeB
	)
	default boolean typeBImageAutosaveOnOff()
	{
		return true;
	}

	@Range(
		min = 100
	)
	@ConfigItem(
		keyName = "typeBHeatmapBackupFrequency",
		name = "Type B Backup Frequency",
		position = 2,
		description = "This determines how often (in number of game ticks) to make a new backup of the 'Type B' heatmap matrix, with the time and date appended to the image file. These files remain in the '~/.runelite/worldheatmap/Heatmap Files/Backups' folder until deleted by the user (so don't set this field too low or there will be way too many backups), but the size of each file is only about on the order of 100kb ish. Default value of 6000. Minimum value of 100.",
		section = typeB
	)
	default int typeBHeatmapBackupFrequency()
	{
		return 36000;
	}

	@ConfigSection(
		name = "Other",
		description = "Other stuff",
		position = 2
	)
	String other = "other";

	@Range(
		min = 0,
		max = 1
	)
	@ConfigItem(
		keyName = "HeatmapAlpha",
		name = "Heatmap Colour Alpha",
		position = 0,
		description = "The opacity of the heatmap colours drawn over the world map image",
		section = other
	)
	default double heatmapAlpha()
	{
		return 0.65;
	}

	@Range(
		min = 1,
		max = 6
	)
	@ConfigItem(
		keyName = "heatmapSensitivity",
		name = "Heatmap sensitivity",
		position = 2,
		description = "Increasing this makes the heatmap's colour gradient more sensitive to step counts.",
		section = other
	)
	default int heatmapSensitivity()
	{
		return 4;
	}

}
