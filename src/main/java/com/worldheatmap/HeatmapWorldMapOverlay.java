package com.worldheatmap;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.HashMap;
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
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Draws the whole heatmap on the world map, using the per-region images from HeatmapOverlayCache, plus a tooltip
 * with the hovered tile's value and a box with the hovered tile's coordinates.
 * World map clipping adapted from skretzo's Invalid Movement plugin (BSD-2-Clause).
 */
class HeatmapWorldMapOverlay extends Overlay
{
	private static final int REGION_SIZE = HeatmapOverlayCache.REGION_SIZE;
	private static final int COORDS_MARGIN = 4;
	private static final int COORDS_PADDING = 4;

	private final Client client;
	private final WorldHeatmapConfig config;
	private final HeatmapOverlayCache cache;
	private final TooltipManager tooltipManager;
	private final WorldMapHeatmapList selectorList;
	private final GlobalHeatmapLoader globalLoader;

	// Images wrapping the loaded global heatmap's region bytes, for the palette they were made with
	private GlobalHeatmap globalImagesHeatmap;
	private int globalImagesSensitivity;
	private int globalImagesAlpha;
	private Map<Integer, BufferedImage> globalImages = new HashMap<>();

	@Inject
	HeatmapWorldMapOverlay(Client client, WorldHeatmapConfig config, HeatmapOverlayCache cache, TooltipManager tooltipManager,
		WorldMapHeatmapList selectorList, GlobalHeatmapLoader globalLoader)
	{
		this.client = client;
		this.config = config;
		this.cache = cache;
		this.tooltipManager = tooltipManager;
		this.selectorList = selectorList;
		this.globalLoader = globalLoader;

		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.MANUAL);
		drawAfterLayer(InterfaceID.Worldmap.MAP_CONTAINER);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showWorldMapOverlay())
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
		final Area mapClipArea = getWorldMapClipArea(bounds);

		// Global region images' rows run from the south, so they're drawn flipped
		final boolean global = config.overlayUseGlobalHeatmaps();
		final GlobalHeatmap globalHeatmap = global ? globalLoader.getHeatmap() : null;
		final Map<Integer, BufferedImage> regionImages = global ? getGlobalImages(globalHeatmap) : cache.getRegionImages();
		if (!regionImages.isEmpty())
		{
			drawRegions(graphics, regionImages, global, mapClipArea, bounds, pixelsPerTile, westTile, widthInTiles, southTile, heightInTiles, mapData);
		}

		// Tile under the mouse, if it's over the visible part of the map
		final Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || !mapClipArea.contains(mouse.getX(), mouse.getY()) || selectorList.isOverList(mouse.getX(), mouse.getY()))
		{
			return null;
		}
		final int hoveredX = westTile + (int) Math.floor((mouse.getX() - bounds.x) / pixelsPerTile);
		final int hoveredY = southTile + (int) Math.floor((bounds.y + bounds.height - mouse.getY()) / pixelsPerTile);
		if (!mapData.surfaceContainsPosition(hoveredX, hoveredY))
		{
			return null;
		}

		if (global)
		{
			addGlobalValueTooltip(globalHeatmap, hoveredX, hoveredY);
		}
		else
		{
			addValueTooltip(hoveredX, hoveredY);
		}
		drawCoordinates(graphics, bounds, hoveredX + ", " + hoveredY);
		return null;
	}

	private void drawRegions(Graphics2D graphics, Map<Integer, BufferedImage> regionImages, boolean flipped, Area mapClipArea, Rectangle bounds,
		float pixelsPerTile, int westTile, int widthInTiles, int southTile, int heightInTiles, WorldMapData mapData)
	{
		final int minRegionX = (westTile - 1) >> 6;
		final int maxRegionX = (westTile + widthInTiles + 1) >> 6;
		final int minRegionY = (southTile - 1) >> 6;
		final int maxRegionY = (southTile + heightInTiles + 1) >> 6;

		final Shape oldClip = graphics.getClip();
		final Object oldInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setClip(mapClipArea);
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

					if (flipped)
					{
						graphics.drawImage(image, left, top, right, bottom, 0, REGION_SIZE, REGION_SIZE, 0, null);
					}
					else
					{
						graphics.drawImage(image, left, top, right - left, bottom - top, null);
					}
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
	}

	/**
	 * Shows the hovered tile's heatmap value, summed across planes like the drawn colours.
	 */
	private void addValueTooltip(int x, int y)
	{
		final Heatmap heatmap = cache.getHeatmap();
		if (heatmap == null)
		{
			return;
		}

		long value = 0;
		for (int plane = 0; plane < 4; plane++)
		{
			value += heatmap.get(x, y, plane);
		}
		if (value > 0)
		{
			tooltipManager.add(new Tooltip(heatmap.getHeatmapType().formatValue(value)));
		}
	}

	/**
	 * Shows the hovered tile's approximate global value. Global data is floor 0 only.
	 */
	private void addGlobalValueTooltip(GlobalHeatmap heatmap, int x, int y)
	{
		final int level = heatmap == null ? 0 : heatmap.getLevel(x, y);
		if (level > 0)
		{
			final long value = roundToSignificantFigures(heatmap.approximateValue(level), 3);
			tooltipManager.add(new Tooltip("~" + heatmap.getHeatmapType().formatValue(value) + " (global)"));
		}
	}

	private static long roundToSignificantFigures(long value, int figures)
	{
		if (value <= 0)
		{
			return value;
		}
		final int digits = (int) Math.floor(Math.log10(value)) + 1;
		if (digits <= figures)
		{
			return value;
		}
		final long scale = (long) Math.pow(10, digits - figures);
		return Math.round((double) value / scale) * scale;
	}

	/**
	 * Images for each of the global heatmap's regions, wrapping its bytes directly with a 256-colour palette
	 * (level 0 transparent), so they take no extra memory beyond the image objects. Changing the sensitivity or
	 * opacity only needs a new palette.
	 */
	private Map<Integer, BufferedImage> getGlobalImages(GlobalHeatmap heatmap)
	{
		if (heatmap == null)
		{
			globalImagesHeatmap = null;
			globalImages = new HashMap<>();
			return globalImages;
		}

		final int sensitivity = config.heatmapSensitivity();
		final int alpha = config.overlayOpacity();
		if (heatmap == globalImagesHeatmap && sensitivity == globalImagesSensitivity && alpha == globalImagesAlpha)
		{
			return globalImages;
		}

		final int[] palette = new int[256];
		for (int level = 1; level < 256; level++)
		{
			palette[level] = Utils.heatmapColorFromLogRatio(GlobalHeatmap.logRatio(level), sensitivity, alpha).getRGB();
		}
		final IndexColorModel colorModel = new IndexColorModel(8, 256, palette, 0, true, -1, DataBuffer.TYPE_BYTE);

		final Map<Integer, BufferedImage> images = new HashMap<>();
		for (Map.Entry<Integer, byte[]> region : heatmap.getRegions().entrySet())
		{
			final DataBufferByte data = new DataBufferByte(region.getValue(), region.getValue().length);
			final WritableRaster raster = Raster.createInterleavedRaster(data, REGION_SIZE, REGION_SIZE, REGION_SIZE, 1, new int[]{0}, null);
			images.put(region.getKey(), new BufferedImage(colorModel, raster, false, null));
		}

		globalImagesHeatmap = heatmap;
		globalImagesSensitivity = sensitivity;
		globalImagesAlpha = alpha;
		globalImages = images;
		return images;
	}

	/**
	 * Draws a small box with the hovered coordinates in the bottom-left corner of the map, moved up above the
	 * overview map if that's open and in the way.
	 */
	private void drawCoordinates(Graphics2D graphics, Rectangle bounds, String text)
	{
		final FontMetrics metrics = graphics.getFontMetrics();
		final int width = metrics.stringWidth(text) + 2 * COORDS_PADDING;
		final int height = metrics.getHeight() + 2 * COORDS_PADDING;
		final Rectangle box = new Rectangle(bounds.x + COORDS_MARGIN, bounds.y + bounds.height - height - COORDS_MARGIN, width, height);

		final Widget overview = client.getWidget(InterfaceID.Worldmap.OVERVIEW_CONTAINER);
		if (overview != null && !overview.isHidden() && overview.getBounds().intersects(box))
		{
			box.y = overview.getBounds().y - height - COORDS_MARGIN;
		}

		graphics.setColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
		graphics.fill(box);

		final int textX = box.x + COORDS_PADDING;
		final int textY = box.y + COORDS_PADDING + metrics.getAscent();
		graphics.setColor(Color.BLACK);
		graphics.drawString(text, textX + 1, textY + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, textX, textY);
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
