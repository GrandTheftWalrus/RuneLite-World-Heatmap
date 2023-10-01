package com.worldheatmap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.w3c.dom.css.Rect;

public class HeatmapNew
{
	@Getter
	private final HashMap<Point, Integer> heatmapHashMap;
	@Getter
	private static final int heatmapVersion = 100;
	@Getter
	private long totalValue = 0;
	@Getter
	private int numTilesVisited = 0;
	@Getter @Setter
	private int gameTimeTicks = 0;
	private int[] maxVal = {1, 0, 0}, minVal = {1, 0, 0}; // {val, x, y}
	private static final int
		HEATMAP_WIDTH = 2752,       //never change these
		HEATMAP_HEIGHT = 1664,      //never change these
		HEATMAP_OFFSET_X = -1152,   //never change these
		HEATMAP_OFFSET_Y = -2496;   //never change these (for backwards compatibility)
	@Getter
	private long userID = -1;

	public enum HeatmapType
	{TYPE_A, TYPE_B, XP_GAINED, TELEPORT_PATHS, TELEPORTED_TO, TELEPORTED_FROM, LOOT_VALUE, PLACES_SPOKEN_AT, RANDOM_EVENT_SPAWNS, DEATHS, NPC_DEATHS, BOB_THE_CAT_SIGHTING, MELEE_FIGHTING, RANGED_FIGHTING, MAGE_FIGHTING, DRAGON_IMPLING_CATCHES, UNKNOWN}
	@Getter @Setter
	private HeatmapType heatmapType;

	public HeatmapNew(HeatmapType heatmapType)
	{
		this.heatmapType = heatmapType;
		this.heatmapHashMap = new HashMap<>();
	}

	public HeatmapNew(HeatmapType heatmapType, long userID)
	{
		this.heatmapHashMap = new HashMap<>();
		this.userID = userID;
		this.heatmapType = heatmapType;
	}

	// The following horse shit is for backwards compatibility with the old, retarded method of storing heatmap data
	public static HeatmapNew convertOldHeatmapToNew(Heatmap oldStyle, HeatmapType type, long userId)
	{
		HeatmapNew newStyle = new HeatmapNew(type, userId);
		for (int x = 0; x < HEATMAP_WIDTH; x++)
		{
			for (int y = 0; y < HEATMAP_HEIGHT; y++)
			{
				if (oldStyle.heatmapCoordsGet(x, y) != 0)
				{
					newStyle.set(x - HEATMAP_OFFSET_X, y - HEATMAP_OFFSET_Y, oldStyle.heatmapCoordsGet(x, y));
				}
			}
		}
		return newStyle;
	}

	public static HeatmapNew convertOldHeatmapToNew(Heatmap oldStyle, long userId)
	{
		return convertOldHeatmapToNew(oldStyle, HeatmapType.UNKNOWN, userId);
	}

	protected Set<Entry<Point, Integer>> getEntrySet()
	{
		return heatmapHashMap.entrySet();
	}

	public void incrementGameTimeTicks()
	{
		this.gameTimeTicks++;
	}

	/**
	 * Increments the heatmap's value at the given location by the amount specified
	 *
	 * @param x      Original RuneScape x-coord
	 * @param y      Original RuneScape y-coord
	 * @param amount Amount to increment the value by
	 */
	protected void increment(int x, int y, int amount)
	{
		set(x, y, get(x, y) + amount);
	}

	/**
	 * Increments the heatmap's value at the given location by 1
	 *
	 * @param x Original RuneScape x-coord
	 * @param y Original RuneScape y-coord
	 */
	protected void increment(int x, int y)
	{
		set(x, y, get(x, y) + 1);
	}

	/**
	 * Sets the heatmap's value at the given location to the given value. If the value is 0, the tile is removed from the heatmap. If the value is negative, nothing happens.
	 *
	 * @param newValue New value
	 * @param x        Original RuneScape x-coord
	 * @param y        Original RuneScape y-coord
	 */
	protected void set(int x, int y, int newValue)
	{
		try{

		}
		catch (OutOfMemoryError e){

		}
		//We don't keep track of unstepped-on tiles
		if (newValue < 0)
		{
			return;
		}

		//Set it & retrieve previous value
		Integer oldValue = heatmapHashMap.put(new Point(x, y), newValue);

		//Update numTilesVisited
		if (oldValue == null && newValue > 0)
		{
			numTilesVisited++;
		}
		else if (oldValue != null && newValue == 0)
		{
			numTilesVisited--;
		}

		//Update total value
		if (oldValue == null)
		{
			totalValue += newValue;
		}
		else
		{
			totalValue += (newValue - oldValue);
		}

		//Error checking for not keeping track of unstepped-on tiles
		if (newValue == 0)
		{
			heatmapHashMap.remove(new Point(x, y));
			// If the removed tile was the most stepped on, then we have
			// no choice but to recalculate the new most stepped on tile
			if (x == maxVal[1] && y == maxVal[2])
			{
				int[] newMax = {1, 0, 0};
				for (Entry<Point, Integer> e : heatmapHashMap.entrySet())
				{
					if (newMax[0] >= e.getValue())
					{
						newMax[0] = e.getValue();
						newMax[1] = e.getKey().x;
						newMax[2] = e.getKey().y;
					}
				}
				maxVal = newMax;
			}
			// It's super unlikely that a removed tile will have been the least
			// stepped on, so I'm just not even gon bother writing error checking for it lul
			return;
		}

		//Update min/max vals
		if (newValue > maxVal[0])
		{
			maxVal = new int[]{newValue, x, y};
		}
		if (newValue <= minVal[0])
		{
			minVal = new int[]{newValue, x, y};
		}
	}

	/**
	 * Returns the heatmap's value at the given game world location. If the tile has not been stepped on, returns 0.
	 *
	 * @param x Heatmap-style x-coord
	 * @param y Heatmap-style y-coord
	 */
	protected int get(int x, int y)
	{
		return heatmapHashMap.getOrDefault(new Point(x, y), 0);
	}

	/**
	 * @return int array holding {maxVal, maxX, maxY} where the latter two are the coordinate at which the max value exists
	 */
	protected int[] getMaxVal()
	{
		return maxVal;
	}

	/**
	 * @return int array holding {maxVal, maxX, maxY} where the latter two are the coordinate at which the max value exists.
	 */
	protected int[] getMaxValInRegion(Rectangle region){
		// Get highest value in region
		int[] maxValInRegion = {0, region.x, region.y};
		for (int x = region.x; x < region.x + region.width; x++)
		{
			for (int y = region.y; y < region.y + region.height; y++)
			{
				if (get(x, y) > maxValInRegion[0])
				{
					maxValInRegion[0] = get(x, y);
					maxValInRegion[1] = x;
					maxValInRegion[2] = y;
				}
			}
		}
		return maxValInRegion;
	}

	protected int[] getMinValInRegion(Rectangle region){
		// Get lowest value in region
		int[] minValInRegion = {Integer.MAX_VALUE, region.x, region.y};
		for (int x = region.x; x < region.x + region.width; x++)
		{
			for (int y = region.y; y < region.y + region.height; y++)
			{
				if (get(x, y) < minValInRegion[0])
				{
					minValInRegion[0] = get(x, y);
					minValInRegion[1] = x;
					minValInRegion[2] = y;
				}
			}
		}
		return minValInRegion;
	}

	/**
	 * @return int array holding {minVal, minX, minY} where the latter two are the coordinate at which the minimum NON-ZERO value exists
	 */
	protected int[] getMinVal()
	{
		return minVal;
	}
}