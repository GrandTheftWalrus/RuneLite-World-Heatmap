package com.worldheatmap;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.Map.Entry;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
		{TYPE_A, TYPE_B, XP_GAINED, TELEPORT_PATHS, TELEPORTED_TO, TELEPORTED_FROM, LOOT_VALUE, PLACES_SPOKEN_AT, RANDOM_EVENT_SPAWNS, DEATHS, NPC_DEATHS, BOB_THE_CAT_SIGHTING, DAMAGE_TAKEN, DAMAGE_GIVEN, UNKNOWN}
	@Getter @Setter
	private HeatmapType heatmapType;

	public HeatmapNew(HeatmapType heatmapType)
	{
		this.heatmapType = heatmapType;
		this.heatmapHashMap = new HashMap<>();
	}

	public HeatmapNew(HeatmapType heatmapType, long userID)
	{
		this.heatmapType = heatmapType;
		this.heatmapHashMap = new HashMap<>();
		this.userID = userID;
	}

	public static HeatmapNew convertOldHeatmapToNew(Heatmap oldStyle, long userId)
	{
		return convertOldHeatmapToNew(oldStyle, HeatmapType.UNKNOWN, userId);
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
	 * Returns the estimated total memory usage of the heatmap, in bytes, assuming 64 bit JVM and 8-byte alignment. Relatively expensive to run because it has to iterate through the entire hashmap.
	 * @return size in bytes
	 */
	public static int estimateSize(HeatmapNew heatmap){
		int estSize = 0;
		// Get count of values above and below 128
		for (Entry<Point, Integer> e : heatmap.getEntrySet())
		{
			int nodeSize = 16 + 8 + 8 + 4 + 4; //16 bytes for Node  object, 8 and 8 for the key and value references, 4 for the hash value, then extra 4 for 8-byte alignment
			int pointSize = 16 + 4 + 4; //16 bytes for Point object header, then 8 for each of the two int coords
			int integerSize = (e.getValue() < 128 ? 8 : 16 + 4 + 4); //8 bytes for just header of pooled Integer, or 12 bytes for non-pooled Integer header plus inner int plus 4 bytes for 8-byte alignment
			estSize += nodeSize + pointSize + integerSize;
		}
		return estSize;
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

	/**
	 * Saves a specified heatmap to the specified .heatmaps file. Forces overwrite of disabled heatmaps.
	 */
	protected static boolean writeHeatmapsToFile(HeatmapNew heatmap, File heatmapsFile) {
		return writeHeatmapsToFile(Collections.singletonList(heatmap), heatmapsFile);
	}

	/**
	 * Saves provided heatmaps to specified .heatmaps file. Disabled heatmaps are left unchanged in the file.
	 */
	protected static boolean writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile) {
		// Make the directory path if it doesn't exist
		if (!Files.exists(Paths.get(heatmapsFile.getParent()))) {
			if (!new File(heatmapsFile.getParent()).mkdirs()) {
				log.error("Could not create the directory for the heatmap file");
			}
		}

		// Creates the zip file if it doesn't exist
		Map<String, String> env = new HashMap<>();
		env.put("create", "true");
		URI uri = URI.create("jar:" + heatmapsFile.toURI());

		log.info("Saving heatmaps to file '" + heatmapsFile.getName() + "'...");
		long startTime = System.nanoTime();
		StringBuilder loggingOutput = new StringBuilder("Heatmap types saved: ");
		for (HeatmapNew heatmap : heatmapsToWrite) {
			// Write the heatmap file
			try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
				Path zipEntryFile = fs.getPath("/" + heatmap.getHeatmapType().toString() + "_HEATMAP.csv");
				try (OutputStreamWriter osw = new OutputStreamWriter(Files.newOutputStream(zipEntryFile), StandardCharsets.UTF_8)) {
					// Write them field variables
					osw.write("userID,heatmapVersion,heatmapType,totalValue,numTilesVisited,maxVal,maxValX,maxValY,minVal,minValX,minValY,gameTimeTicks\n");
					osw.write(heatmap.getUserID() +
							"," + getHeatmapVersion() +
							"," + heatmap.getHeatmapType() +
							"," + heatmap.getTotalValue() +
							"," + heatmap.getNumTilesVisited() +
							"," + heatmap.getMaxVal()[0] +
							"," + heatmap.getMaxVal()[1] +
							"," + heatmap.getMaxVal()[2] +
							"," + heatmap.getMinVal()[0] +
							"," + heatmap.getMinVal()[1] +
							"," + heatmap.getMinVal()[2] +
							"," + heatmap.getGameTimeTicks() + "\n");
					// Write the tile values
					for (Entry<Point, Integer> e : heatmap.getEntrySet()) {
						int x = e.getKey().x;
						int y = e.getKey().y;
						int stepVal = e.getValue();
						osw.write(x + "," + y + "," + stepVal + "\n");
					}
					osw.flush();
				}

			} catch (IOException e) {
				e.printStackTrace();
				log.error("World Heatmap was not able to save heatmap type '" + heatmap.getHeatmapType() + "' to file '" + heatmapsFile.getName() + "'");
				return false;
			}
			loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getNumTilesVisited() + " tiles), ");
		}
		log.info(loggingOutput.toString());
		log.info("Finished writing '" + heatmapsFile.getName() + "' heatmap file to disk after " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
		return true;
	}

	/**
	 * Loads the specified heatmap types from the given .heatmaps file.
	 *
	 * @param heatmapsFile The .heatmaps file
	 * @param types        The heatmap types to load
	 * @return HashMap of HeatmapNew objects
	 * @throws FileNotFoundException If the file does not exist
	 */
	static HashMap<HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapsFile, Collection<HeatmapType> types) throws FileNotFoundException {
		Map<String, String> env = new HashMap<>();
		env.put("create", "true");
		URI uri = URI.create("jar:" + heatmapsFile.toURI());
		try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
			HashMap<HeatmapType, HeatmapNew> heatmapsRead = new HashMap<>();
			StringBuilder loggingOutput = new StringBuilder();
			loggingOutput.append("Heatmap types loaded: ");

			for (HeatmapType curType : types) {
				Path curHeatmapPath = fs.getPath("/" + curType.toString() + "_HEATMAP.csv");
				if (!Files.exists(curHeatmapPath)) {
					continue;
				}
				try (InputStreamReader isr = new InputStreamReader(Files.newInputStream(curHeatmapPath), StandardCharsets.UTF_8);
					 BufferedReader reader = new BufferedReader(isr)) {
					// Read them field variables
					String[] fieldNames = reader.readLine().split(",");
					String[] fieldValues = reader.readLine().split(",");
					long userID = (fieldValues[0].isEmpty() ? -1 : Long.parseLong(fieldValues[0]));
					String heatmapTypeString = fieldValues[2];
					int totalValue = (fieldValues[3].isEmpty() ? -1 : Integer.parseInt(fieldValues[3]));
					int numTilesVisited = (fieldValues[4].isEmpty() ? -1 : Integer.parseInt(fieldValues[4]));
					int maxVal = (fieldValues[5].isEmpty() ? -1 : Integer.parseInt(fieldValues[5]));
					int maxValX = (fieldValues[6].isEmpty() ? -1 : Integer.parseInt(fieldValues[6]));
					int maxValY = (fieldValues[7].isEmpty() ? -1 : Integer.parseInt(fieldValues[7]));
					int minVal = (fieldValues[8].isEmpty() ? -1 : Integer.parseInt(fieldValues[8]));
					int minValX = (fieldValues[9].isEmpty() ? -1 : Integer.parseInt(fieldValues[9]));
					int minValY = (fieldValues[10].isEmpty() ? -1 : Integer.parseInt(fieldValues[10]));
					int gameTimeTicks = (fieldValues[11].isEmpty() ? -1 : Integer.parseInt(fieldValues[11]));

					// Get HeatmapType from field value if legit
					HeatmapType recognizedHeatmapType;
					if (Arrays.stream(HeatmapType.values()).noneMatch(type -> type.toString().equals(heatmapTypeString))) {
						log.warn("Heatmap type '" + heatmapTypeString + "' from ZipEntry '" + curHeatmapPath + "' is not a valid Heatmap type (at least in this program version). Ignoring...");
						// Stop reading and go to next Heatmap type
						continue;
					} else {
						recognizedHeatmapType = HeatmapType.valueOf(heatmapTypeString);
					}

					// Make ze Heatmap
					HeatmapNew heatmap = new HeatmapNew(recognizedHeatmapType, userID);

					// Read and load the tile values
					final int[] errorCount = {0}; // Number of parsing errors occurred during read
					reader.lines().forEach(s -> {
						String[] tile = s.split(",");
						try {
							heatmap.set(Integer.parseInt(tile[0]), Integer.parseInt(tile[1]), Integer.parseInt(tile[2]));
						} catch (NumberFormatException e) {
							errorCount[0]++;
						}
					});
					if (errorCount[0] != 0) {
						log.error(errorCount[0] + " errors occurred during " + recognizedHeatmapType + " heatmap file read.");
					}
					loggingOutput.append(recognizedHeatmapType + " (" + numTilesVisited + " tiles), ");
					heatmapsRead.put(recognizedHeatmapType, heatmap);
				} catch (IOException e) {
					log.error("Error reading " + curType + " heatmap from .heatmaps entry '" + curHeatmapPath + "'");
				}
			}
			log.info(loggingOutput.toString());
			return heatmapsRead;
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}