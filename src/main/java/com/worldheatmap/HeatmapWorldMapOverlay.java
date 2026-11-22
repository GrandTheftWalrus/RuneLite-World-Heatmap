package com.worldheatmap;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.api.worldmap.WorldMapData;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the whole heatmap on the world map, using the per-region images from HeatmapOverlayCache.
 * World map clipping adapted from skretzo's Invalid Movement plugin (BSD-2-Clause).
 */
class HeatmapWorldMapOverlay extends Overlay
{
	private static final int REGION_SIZE = HeatmapOverlayCache.REGION_SIZE;

	private final Client client;
	private final WorldHeatmapConfig config;
	private final HeatmapOverlayCache cache;

	@Inject
	HeatmapWorldMapOverlay(Client client, WorldHeatmapConfig config, HeatmapOverlayCache cache)
	{
		this.client = client;
		this.config = config;
		this.cache = cache;

		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.MANUAL);
		drawAfterLayer(InterfaceID.Worldmap.MAP_CONTAINER);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final Map<Integer, BufferedImage> regionImages = cache.getRegionImages();
		if (!config.showWorldMapOverlay() || regionImages.isEmpty())
		{
			return null;
		}

		final Widget mapView = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		final WorldMap worldMap = client.getWorldMap();
		if (mapView == null || mapView.isHidden() || worldMap == null)
		{
			return null;
		}

		final Rectangle bounds = mapView.getBounds();
		final float pixelsPerTile = worldMap.getWorldMapZoom();
		final Point position = worldMap.getWorldMapPosition();
		final WorldMapData mapData = worldMap.getWorldMapData();
		if (bounds == null || pixelsPerTile <= 0 || position == null || mapData == null)
		{
			return null;
		}

		// Same projection as WorldMapOverlay.mapWorldPointToGraphicsPoint, but for tile edges rather than centres
		final int widthInTiles = (int) Math.ceil(bounds.getWidth() / pixelsPerTile);
		final int heightInTiles = (int) Math.ceil(bounds.getHeight() / pixelsPerTile);
		final int westTile = position.getX() - widthInTiles / 2;
		final int southTile = position.getY() - heightInTiles / 2;

		final int minRegionX = (westTile - 1) >> 6;
		final int maxRegionX = (westTile + widthInTiles + 1) >> 6;
		final int minRegionY = (southTile - 1) >> 6;
		final int maxRegionY = (southTile + heightInTiles + 1) >> 6;

		final Shape oldClip = graphics.getClip();
		final Object oldInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setClip(getWorldMapClipArea(bounds));
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		try
		{
			for (int rx = minRegionX; rx <= maxRegionX; rx++)
			{
				for (int ry = minRegionY; ry <= maxRegionY; ry++)
				{
					final BufferedImage image = regionImages.get((rx << 8) | ry);
					if (image == null)
					{
						continue;
					}

					final int baseX = rx << 6;
					final int baseY = ry << 6;
					if (!isOnDisplayedSurface(mapData, baseX, baseY))
					{
						continue;
					}

					final int left = Math.round(bounds.x + (baseX - westTile) * pixelsPerTile);
					final int right = Math.round(bounds.x + (baseX + REGION_SIZE - westTile) * pixelsPerTile);
					final int top = Math.round(bounds.y + bounds.height - (baseY + REGION_SIZE - southTile) * pixelsPerTile);
					final int bottom = Math.round(bounds.y + bounds.height - (baseY - southTile) * pixelsPerTile);

					graphics.drawImage(image, left, top, right - left, bottom - top, null);
				}
			}
		}
		finally
		{
			graphics.setClip(oldClip);
			if (oldInterpolation != null)
			{
				graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
			}
		}
		return null;
	}

	/**
	 * Whether any part of the region belongs to the map surface currently shown (e.g. the main surface vs. a dungeon map)
	 */
	private static boolean isOnDisplayedSurface(WorldMapData mapData, int baseX, int baseY)
	{
		final int last = REGION_SIZE - 1;
		return mapData.surfaceContainsPosition(baseX + REGION_SIZE / 2, baseY + REGION_SIZE / 2)
			|| mapData.surfaceContainsPosition(baseX, baseY)
			|| mapData.surfaceContainsPosition(baseX + last, baseY)
			|| mapData.surfaceContainsPosition(baseX, baseY + last)
			|| mapData.surfaceContainsPosition(baseX + last, baseY + last);
	}

	private Area getWorldMapClipArea(Rectangle baseRectangle)
	{
		final Widget overview = client.getWidget(InterfaceID.Worldmap.OVERVIEW_CONTAINER);
		final Widget surfaceSelector = client.getWidget(InterfaceID.Worldmap.MAPLIST_BOX_GRAPHIC0);

		Area clipArea = new Area(baseRectangle);

		if (overview != null && !overview.isHidden())
		{
			clipArea.subtract(new Area(overview.getBounds()));
		}

		if (surfaceSelector != null && !surfaceSelector.isHidden())
		{
			clipArea.subtract(new Area(surfaceSelector.getBounds()));
		}

		return clipArea;
	}
}
