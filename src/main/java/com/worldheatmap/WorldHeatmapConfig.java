package com.worldheatmap;

import lombok.experimental.FieldNameConstants;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("example")
public interface WorldHeatmapConfig extends Config
{
	@ConfigItem(
			keyName = "imageAutosaveFrequency",
			name = "Image Autosave Frequency",
			description = "This determines how often (in number of steps) to automatically save the world heatmap PNG image. Minimum value of 100."
	)
	default int imageAutosaveFrequency()
	{
		return 100;
	}

	@ConfigItem(
			keyName = "autosaveOnOff",
			name = "Autosave Image to Disk",
			description = "Should the heatmap be automatically saved at the frequency specified above?"
	)
	default boolean autosaveOnOff() { return true; }

	@ConfigItem(
			keyName = "heatmapBackupFrequency",
			name = "Heatmap Backup Frequency",
			description = "This determines how often (in number of steps) to make backups the heatmap matrix, with the time and date appended to the image file. These files remain in the Heatmap 'Results/Backups' folder until deleted by the user (so don't set this field too low or there will be way too many backups), but the size of each file is only about on the order of 10-100kb. Only accepts values of at least 1000."
	)
	default int heatmapBackupFrequency()
	{
		return 1000;
	}
}
