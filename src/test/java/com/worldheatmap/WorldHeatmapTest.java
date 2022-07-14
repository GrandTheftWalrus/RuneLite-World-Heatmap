package com.worldheatmap;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class WorldHeatmapTest
{
	public static void main(String[] args) throws Exception
	{
		//noinspection unchecked
		ExternalPluginManager.loadBuiltin(WorldHeatmapPlugin.class);
		RuneLite.main(args);
	}
}