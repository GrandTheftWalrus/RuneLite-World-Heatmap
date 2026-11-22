package com.worldheatmap;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Holds the heatmap currently shown by the overlays, its value range, and (if the world map overlay is enabled)
 * a 64x64 one-pixel-per-tile image of each region that has data. Refreshed on the client thread once per game tick,
 * which is the same thread that modifies the heatmaps and renders the overlays.
 */
@Slf4j
@Singleton
class HeatmapOverlayCache
{
	static final int REGION_SIZE = 64;
	// How often (in ticks) to recolour after the heatmap's data changes. Config/heatmap swaps apply immediately.
	private static final int REFRESH_TICKS = 5;

	private final WorldHeatmapPlugin plugin;
	private final WorldHeatmapConfig config;
	private final GlobalHeatmapLoader globalLoader;

	// Global heatmap tile colours by level, for the palette settings they were made with
	private Color[] globalPalette;
	private int globalPaletteSensitivity;
	private int globalPaletteAlpha;

	@Getter
	private Heatmap heatmap;
	@Getter
	private long minVal;
	@Getter
	private long maxVal;
	@Getter
	private Map<Integer, BufferedImage> regionImages = Collections.emptyMap();

	private boolean dirty = true;
	private boolean regionImagesBuilt;
	private int lastTileCount;
	private long lastTotalValue;
	private int ticksSinceRefresh;

	@Inject
	HeatmapOverlayCache(WorldHeatmapPlugin plugin, WorldHeatmapConfig config, GlobalHeatmapLoader globalLoader)
	{
		this.plugin = plugin;
		this.config = config;
		this.globalLoader = globalLoader;
	}

	/**
	 * Forces a rebuild on the next tick, e.g. after an overlay config change.
	 */
	/**
	 * Colours for the game scene and minimap overlays' tiles, for one frame.
	 */
	interface TileColours
	{
		/**
		 * @return the tile's colour, or null if it has no data
		 */
		Color get(int x, int y, int plane);
	}

	/**
	 * Gets the tile colours for this frame: from the global heatmap (floor 0 only) in global mode, otherwise from the
	 * player's own. Reads the config once here rather than per tile. Client thread only.
	 *
	 * @return the colours, or null if there's no data to show
	 */
	TileColours getTileColours()
	{
		final int sensitivity = config.heatmapSensitivity();
		final int alpha = config.overlayOpacity();

		if (config.overlayUseGlobalHeatmaps())
		{
			final GlobalHeatmap global = globalLoader.getHeatmap();
			if (global == null)
			{
				return null;
			}
			final Color[] palette = getGlobalPalette(sensitivity, alpha);
			return (x, y, plane) ->
			{
				final int level = plane == 0 ? global.getLevel(x, y) : 0;
				return level == 0 ? null : palette[level];
			};
		}

		final Heatmap local = heatmap;
		if (local == null)
		{
			return null;
		}
		final long min = minVal;
		final long max = maxVal;
		return (x, y, plane) ->
		{
			final long value = local.get(x, y, plane);
			return value <= 0 ? null : Utils.heatmapColor(value, sensitivity, min, max, alpha);
		};
	}

	private Color[] getGlobalPalette(int sensitivity, int alpha)
	{
		if (globalPalette == null || sensitivity != globalPaletteSensitivity || alpha != globalPaletteAlpha)
		{
			final Color[] palette = new Color[256];
			for (int level = 1; level < 256; level++)
			{
				palette[level] = Utils.heatmapColorFromLogRatio(GlobalHeatmap.logRatio(level), sensitivity, alpha);
			}
			globalPalette = palette;
			globalPaletteSensitivity = sensitivity;
			globalPaletteAlpha = alpha;
		}
		return globalPalette;
	}

	void invalidate()
	{
		dirty = true;
	}

	void clear()
	{
		heatmap = null;
		minVal = 0;
		maxVal = 0;
		regionImages = Collections.emptyMap();
		regionImagesBuilt = false;
		dirty = true;
	}

	void onGameTick()
	{
		if (!config.showSceneOverlay() && !config.showMinimapOverlay() && !config.showWorldMapOverlay())
		{
			if (heatmap != null)
			{
				clear();
			}
			return;
		}

		ticksSinceRefresh++;
		Heatmap current = plugin.getLoadedHeatmap(config.overlayHeatmapType().getHeatmapType());
		// In global mode the world map shows the global heatmap instead
		boolean needsRegionImages = config.showWorldMapOverlay() && !config.overlayUseGlobalHeatmaps();
		boolean structuralChange = dirty || current != heatmap || needsRegionImages != regionImagesBuilt;
		boolean dataChanged = current != null &&
			(current.getTileCount() != lastTileCount || current.getTotalValue() != lastTotalValue);

		if (structuralChange || (dataChanged && ticksSinceRefresh >= REFRESH_TICKS))
		{
			rebuild(current, needsRegionImages);
		}
	}

	private void rebuild(Heatmap current, boolean buildRegionImages)
	{
		if (current == null)
		{
			clear();
			dirty = false;
			return;
		}

		try
		{
			long min = Long.MAX_VALUE;
			long max = 0;
			// Values summed across planes per (x, y), since the world map is 2D
			Map<Integer, long[]> regionSums = buildRegionImages ? new HashMap<>() : null;

			for (Map.Entry<WorldPoint, Long> e : current.getEntrySet())
			{
				long val = e.getValue();
				min = Math.min(min, val);
				max = Math.max(max, val);

				if (regionSums != null)
				{
					WorldPoint wp = e.getKey();
					long[] sums = regionSums.computeIfAbsent(wp.getRegionID(), k -> new long[REGION_SIZE * REGION_SIZE]);
					sums[pixelIndex(wp.getX(), wp.getY())] += val;
				}
			}

			heatmap = current;
			minVal = min == Long.MAX_VALUE ? 0 : min;
			maxVal = max;
			regionImages = regionSums != null ? colourRegions(regionSums) : Collections.emptyMap();
			regionImagesBuilt = buildRegionImages;
			lastTileCount = current.getTileCount();
			lastTotalValue = current.getTotalValue();
			ticksSinceRefresh = 0;
			dirty = false;
		}
		catch (ConcurrentModificationException e)
		{
			// The heatmap was modified off the client thread (e.g. while loading); try again next tick
			log.debug("Heatmap changed while building overlay cache, retrying next tick");
		}
	}

	private Map<Integer, BufferedImage> colourRegions(Map<Integer, long[]> regionSums)
	{
		long min = Long.MAX_VALUE;
		long max = 0;
		for (long[] sums : regionSums.values())
		{
			for (long val : sums)
			{
				if (val > 0)
				{
					min = Math.min(min, val);
					max = Math.max(max, val);
				}
			}
		}

		int sensitivity = config.heatmapSensitivity();
		int alpha = config.overlayOpacity();
		Map<Integer, BufferedImage> images = new HashMap<>();
		for (Map.Entry<Integer, long[]> region : regionSums.entrySet())
		{
			long[] sums = region.getValue();
			int[] argb = new int[sums.length];
			for (int i = 0; i < sums.length; i++)
			{
				if (sums[i] > 0)
				{
					argb[i] = Utils.heatmapColor(sums[i], sensitivity, min, max, alpha).getRGB();
				}
			}
			BufferedImage image = new BufferedImage(REGION_SIZE, REGION_SIZE, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, REGION_SIZE, REGION_SIZE, argb, 0, REGION_SIZE);
			images.put(region.getKey(), image);
		}
		return images;
	}

	/**
	 * Index of a tile's pixel within its region image. Row 0 is the region's north edge.
	 */
	private static int pixelIndex(int x, int y)
	{
		int px = x & (REGION_SIZE - 1);
		int py = REGION_SIZE - 1 - (y & (REGION_SIZE - 1));
		return py * REGION_SIZE + px;
	}
}
