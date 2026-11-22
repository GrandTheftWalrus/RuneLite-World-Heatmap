package com.worldheatmap;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Tints scene tiles with their heatmap colour.
 * Scene iteration adapted from skretzo's Invalid Movement plugin (BSD-2-Clause).
 */
class HeatmapSceneOverlay extends Overlay
{
	private final Client client;
	private final WorldHeatmapConfig config;
	private final HeatmapOverlayCache cache;

	@Inject
	HeatmapSceneOverlay(Client client, WorldHeatmapConfig config, HeatmapOverlayCache cache)
	{
		this.client = client;
		this.config = config;
		this.cache = cache;

		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Heatmap heatmap = cache.getHeatmap();
		if (!config.showSceneOverlay() || heatmap == null || client.getLocalPlayer() == null)
		{
			return null;
		}

		final LocalPoint playerLocation = client.getLocalPlayer().getLocalLocation();
		final int playerX = playerLocation.getSceneX();
		final int playerY = playerLocation.getSceneY();
		final int radius = config.overlaySceneRadius() < 0 ? Integer.MAX_VALUE / 2 : config.overlaySceneRadius();

		final WorldView worldView = client.getTopLevelWorldView();
		final int z = worldView.getPlane();
		final Tile[][] tiles = worldView.getScene().getTiles()[z];

		final int startX = Math.max(playerX - radius, 0);
		final int endX = Math.min(playerX + radius, tiles.length);
		final int startY = Math.max(playerY - radius, 0);
		final int endY = Math.min(playerY + radius, tiles[0].length);

		final int sensitivity = config.heatmapSensitivity();
		final int alpha = config.overlayOpacity();
		final long minVal = cache.getMinVal();
		final long maxVal = cache.getMaxVal();

		for (int x = startX; x < endX; x++)
		{
			for (int y = startY; y < endY; y++)
			{
				final Tile tile = tiles[x][y];
				if (tile == null)
				{
					continue;
				}

				final WorldPoint wp = tile.getWorldLocation();
				final long value = heatmap.get(wp.getX(), wp.getY(), wp.getPlane());
				if (value <= 0)
				{
					continue;
				}

				final Polygon poly = Perspective.getCanvasTilePoly(client, tile.getLocalLocation());
				if (poly == null)
				{
					continue;
				}

				graphics.setColor(Utils.heatmapColor(value, sensitivity, minVal, maxVal, alpha));
				graphics.fill(poly);
			}
		}
		return null;
	}
}
