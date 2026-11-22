package com.worldheatmap;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.game.SpriteOverride;

/**
 * Custom sprites added to the game's sprite cache, so widgets can use them. The IDs are negative so they can't clash
 * with the game's own sprites.
 */
@Getter
@RequiredArgsConstructor
enum WorldHeatmapSprites implements SpriteOverride
{
	GLOBE(-20321, "/world_heatmap_globe.png");

	private final int spriteId;
	private final String fileName;
}
