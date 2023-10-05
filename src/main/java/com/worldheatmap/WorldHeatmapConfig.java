package com.worldheatmap;

import net.runelite.client.config.*;

@ConfigGroup(WorldHeatmapConfig.GROUP)
public interface WorldHeatmapConfig extends Config
{
	String GROUP = "worldheatmap";

	@ConfigSection(
		name = "Stuff",
		description = "The 'Type A' heatmap's tiles are incremented each time they are stepped on",
		position = 0
	)
	String settings = "settings";

	@Range(
		min = 30
	)
	@ConfigItem(
		keyName = "ImageAutosaveFrequency",
		name = "A&B Image Autosave Frequency",
		position = 0,
		description = "This determines how often (in number of steps) to automatically save both the 'Type A' and 'Type B' world heatmap TIF images. Default value of 2000 ticks (20 minutes). Minimum value of 50.",
		section = settings
	)
	default int typeABImageAutosaveFrequency()
	{
		return 2000;
	}

	@ConfigItem(
		keyName = "ImageAutosave",
		name = "Type A&B Image Autosave",
		position = 1,
		description = "This determines whether to automatically save both the 'Type A' and 'Type B' world heatmap TIF images. Default value of false.",
		section = settings
	)
	default boolean typeABImageAutosave()
	{
		return false;
	}

	@Range(
		min = 30
	)
	@ConfigItem(
		keyName = "HeatmapBackupFrequency",
		name = "Backup Frequency",
		position = 2,
		description = "This determines how often (in ticks of game time) to make a new backup of the heatmap data, with the time and date appended to the file. These files remain in the Heatmap 'Results/Backups' folder until deleted by the user (so don't set this field too low or there will be way too many backups). Default value 36000 ticks (6 hours). Minimum value 50.",
		section = settings
	)
	default int heatmapBackupFrequency()
	{
		return 36000;
	}

	@Range(
		min = 0,
		max = 1
	)
	@ConfigItem(
		keyName = "HeatmapAlpha",
		name = "Heatmap Colour Alpha",
		position = 3,
		description = "The opacity of the heatmap colours drawn over the world map image",
		section = settings
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
		position = 4,
		description = "Increasing this makes the heatmap's colour gradient more sensitive to step counts.",
		section = settings
	)
	default int heatmapSensitivity()
	{
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
	default int speedMemoryTradeoff()
	{
		return 4;
	}

}
