package com.worldheatmap;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;

/**
 * A global heatmap in the compact format made by convert_global_heatmaps.py: floor 0 only, one byte per tile per
 * 64x64 region, where 0 means no data and 1..255 is the tile value's log ratio (log(value) / log(max)), quantised.
 * That ratio is all the colour gradient needs, so the full values are never held in memory.
 * <p>
 * File format (gzipped, big-endian):
 * "WHMG", version byte (1), heatmap type (writeUTF), max value (long), region count (int), then per region:
 * region ID (unsigned short), 4096 levels at index (y &amp; 63) * 64 + (x &amp; 63), i.e. rows from the south edge.
 */
class GlobalHeatmap
{
	static final byte[] MAGIC = {'W', 'H', 'M', 'G'};
	static final int FORMAT_VERSION = 1;
	static final int REGION_SIZE = 64;
	static final int TILES_PER_REGION = REGION_SIZE * REGION_SIZE;
	// Instanced areas (POH, raids etc.) are at x >= 6400, which no world map surface shows, so they're not kept
	private static final int MAX_REGION_X = 6400 >> 6;

	@Getter
	private final Heatmap.HeatmapType heatmapType;
	@Getter
	private final long maxValue;
	// Region ID -> 4096 levels
	@Getter
	private final Map<Integer, byte[]> regions;

	private GlobalHeatmap(Heatmap.HeatmapType heatmapType, long maxValue, Map<Integer, byte[]> regions)
	{
		this.heatmapType = heatmapType;
		this.maxValue = maxValue;
		this.regions = Collections.unmodifiableMap(regions);
	}

	/**
	 * Reads a global heatmap from the (already un-gzipped) stream.
	 */
	static GlobalHeatmap read(DataInputStream in, Heatmap.HeatmapType expectedType) throws IOException
	{
		byte[] magic = new byte[MAGIC.length];
		in.readFully(magic);
		for (int i = 0; i < MAGIC.length; i++)
		{
			if (magic[i] != MAGIC[i])
			{
				throw new IOException("Not a global heatmap file");
			}
		}
		int version = in.readUnsignedByte();
		if (version != FORMAT_VERSION)
		{
			throw new IOException("Unsupported global heatmap format version " + version);
		}
		String type = in.readUTF();
		long maxValue = in.readLong();
		int regionCount = in.readInt();
		if (regionCount < 0 || regionCount > 0x10000 || maxValue < 0)
		{
			throw new IOException("Corrupt global heatmap header");
		}

		Map<Integer, byte[]> regions = new HashMap<>();
		byte[] skipped = null;
		for (int i = 0; i < regionCount; i++)
		{
			int regionId = in.readUnsignedShort();
			if ((regionId >> 8) >= MAX_REGION_X)
			{
				if (skipped == null)
				{
					skipped = new byte[TILES_PER_REGION];
				}
				in.readFully(skipped);
				continue;
			}
			byte[] levels = new byte[TILES_PER_REGION];
			in.readFully(levels);
			regions.put(regionId, levels);
		}
		return new GlobalHeatmap(expectedType != null ? expectedType : Heatmap.HeatmapType.valueOf(type), maxValue, regions);
	}

	/**
	 * @return the tile's level (0 = no data, 1..255)
	 */
	int getLevel(int x, int y)
	{
		byte[] levels = regions.get((x >> 6) << 8 | (y >> 6));
		return levels == null ? 0 : levels[(y & 63) * REGION_SIZE + (x & 63)] & 0xff;
	}

	/**
	 * @return the log ratio a level (1..255) stands for
	 */
	static double logRatio(int level)
	{
		return (level - 1) / 254.0;
	}

	/**
	 * @return the approximate tile value a level (1..255) stands for, to within about 3%
	 */
	long approximateValue(int level)
	{
		if (maxValue <= 1)
		{
			return maxValue;
		}
		return Math.round(Math.exp(logRatio(level) * Math.log(maxValue)));
	}
}
