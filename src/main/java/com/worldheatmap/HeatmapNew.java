package com.worldheatmap;

import java.awt.Point;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.InflaterInputStream;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public class HeatmapNew
{
	@Getter
	private final HashMap<Point, Integer> heatmapHashMap;
	@Getter
	private final int heatmapVersion = 102;
	@Getter
	private long totalValue = 0;
	@Getter
	private int tileCount = 0;
	@Getter @Setter
	private int gameTimeTicks = 0;
	private int[] maxVal = {1, 0, 0}, minVal = {1, 0, 0}; // {val, x, y}
	private static final int
		HEATMAP_WIDTH = 2752,       //never change these
		HEATMAP_HEIGHT = 1664,      //never change these
		HEATMAP_OFFSET_X = -1152,   //never change these
		HEATMAP_OFFSET_Y = -2496;   //never change these (for backwards compatibility)
	public enum HeatmapType
	{TYPE_A, TYPE_B, XP_GAINED, TELEPORT_PATHS, TELEPORTED_TO, TELEPORTED_FROM, LOOT_VALUE, PLACES_SPOKEN_AT, RANDOM_EVENT_SPAWNS, DEATHS, NPC_DEATHS, BOB_THE_CAT_SIGHTING, DAMAGE_TAKEN, DAMAGE_GIVEN, UNKNOWN}
	@Getter @Setter
	private long userID = -1;
	@Getter @Setter
	private int accountType = -1;
	@Getter @Setter
	private HeatmapType heatmapType;
	@Getter @Setter
	private int currentCombatLevel = -1;
	@Getter @Setter
	private String seasonalType = null;

	/**
	 * Constructor for HeatmapNew object with no arguments.
	 */
	public HeatmapNew()
	{
		this.heatmapType = HeatmapType.UNKNOWN;
		this.heatmapHashMap = new HashMap<>();
	}

	/**
	 * Constructor for HeatmapNew object with userID and accountType.
	 * @param heatmapType
	 * @param userID
	 * @param accountType
	 */
	public HeatmapNew(HeatmapType heatmapType, long userID, int accountType, String seasonalType)
	{
		this.heatmapType = heatmapType;
		this.heatmapHashMap = new HashMap<>();
		this.userID = userID;
		this.accountType = accountType;
		this.seasonalType = seasonalType;
	}

	/**
	 * Converter for backwards compatibility with the old, retarded method of storing heatmap data
	 * @param oldStyle
	 * @return
	 */
	public static HeatmapNew convertOldHeatmapToNew(Heatmap oldStyle)
	{
		HeatmapNew newStyle = new HeatmapNew();
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

	/**
	 * Loads, converts, and returns .heatmap file of legacy style as a HeatmapNew.
	 * If the file isn't actually a legacy .heatmap file, throws an exception.
	 * @param heatmapFile The .heatmap file
	 * @return HeatmapNew object
	 */
	static HeatmapNew readLegacyV1HeatmapFile(File heatmapFile) {
		try (FileInputStream fis = new FileInputStream(heatmapFile);
			 InflaterInputStream iis = new InflaterInputStream(fis);
			 ObjectInputStream ois = new ObjectInputStream(iis)) {
			Heatmap heatmap = (Heatmap) ois.readObject();
			HeatmapNew result = convertOldHeatmapToNew(heatmap);
			return result;
		} catch (Exception e) {
			log.error("Exception occurred while reading legacy heatmap file '{}'", heatmapFile.getName());
			throw new RuntimeException(e);
		}
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
			tileCount++;
		}
		else if (oldValue != null && newValue == 0)
		{
			tileCount--;
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
	 * @return int array holding {minVal, minX, minY} where the latter two are the coordinate at which the minimum NON-ZERO value exists
	 */
	protected int[] getMinVal()
	{
		return minVal;
	}

	/**
	 * Writes the provided heatmap data to the specified .heatmaps file. If the file already exists, it will be updated,
	 * and unprovided heatmaps already in the file will remain.
	 * @param heatmapsToWrite The heatmaps to write
	 * @param heatmapsFile The .heatmaps file
	 */
	protected static void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, null);
	}

	/**
	 * Writes the provided heatmap data to the specified .heatmaps file. Unprovided heatmaps are carried over from the file previousHeatmapsFile, if it has them.
	 * If heatmapsFile already exists, existing unprovided heatmaps will be kept. Heatmaps in previousHeatmapsFile take precedence over heatmaps in heatmapsFile
	 * if heatmapsFile already exists.
	 * @param heatmapsToWrite The heatmaps to write
	 * @param heatmapsFile The .heatmaps file
	 * @param previousHeatmapsFile The previous .heatmaps file.
	 */
	protected static void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, @Nullable File previousHeatmapsFile) {
		// Preamble
		log.debug("Saving heatmaps to file '{}'...", heatmapsFile.getName());
		long startTime = System.nanoTime();
		StringBuilder loggingOutput = new StringBuilder("Heatmap types saved: ");

		// Make the directory path if it doesn't exist
		if (!Files.exists(Paths.get(heatmapsFile.getParent()))) {
			if (!new File(heatmapsFile.getParent()).mkdirs()) {
				log.error("Could not create the directory for the heatmap file");
			}
		}

		// Copy previousHeatmapsFile to heatmapsFile (if it exists)
		// in order to carry over unprovided heatmaps before updating
		if (previousHeatmapsFile != null && previousHeatmapsFile.exists()) {
			try {
				Files.copy(previousHeatmapsFile.toPath(), heatmapsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				log.error("Error copying latest heatmaps file to new location");
				return;
			}
		}

		// Zip reading params
		Map<String, String> env = new HashMap<>();
		env.put("create", "true");
		URI uri = URI.create("jar:" + heatmapsFile.toURI());

		for (HeatmapNew heatmap : heatmapsToWrite) {
			// Write the heatmap file, overwriting zip entries that already exist
			try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
				Path zipEntryFile = fs.getPath("/" + heatmap.getHeatmapType().toString() + "_HEATMAP.csv");
				try (OutputStreamWriter osw = new OutputStreamWriter(Files.newOutputStream(zipEntryFile), StandardCharsets.UTF_8)) {
					osw.write(heatmap.toCSV());
					osw.flush(); // Not sure if this is necessary
				}
			} catch (IOException e) {
                log.error("World Heatmap was not able to save heatmap type '{}' to file '{}'", heatmap.getHeatmapType(), heatmapsFile.getName());
				e.printStackTrace();
				return;
			}
			loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getTileCount() + " tiles), ");
		}

		log.debug(loggingOutput.toString());
        log.debug("Finished writing '{}' heatmap file to disk after {} ms", heatmapsFile.getName(), (System.nanoTime() - startTime) / 1_000_000);
	}

	/**
	 * Serializes the provided heatmap data to the specified OutputStream in CSV format.
	 */
	protected String toCSV() {
		StringBuilder output = new StringBuilder();
			// Write them field variables
			output.append("userID,heatmapVersion,heatmapType,gameTimeTicks,accountType,currentCombatLevel,seasonalType\n");
			output.append(this.getUserID() +
					"," + this.getHeatmapVersion() +
					"," + this.getHeatmapType() +
					"," + this.getGameTimeTicks() +
					"," + this.getAccountType() +
					"," + this.getCurrentCombatLevel() +
					"," + this.getSeasonalType() + "\n");
			// Write the tile values
			for (Entry<Point, Integer> e : this.getEntrySet()) {
				int x = e.getKey().x;
				int y = e.getKey().y;
				int stepVal = e.getValue();
				output.append(x + "," + y + "," + stepVal + "\n");
			}
		return output.toString();
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
					Map<String, String> fieldMap = new HashMap<>();
					for (int i = 0; i < fieldNames.length; i++) {
						fieldMap.put(fieldNames[i], fieldValues[i]);
					}
					long userID = Long.parseLong(fieldMap.getOrDefault("userID", "-1"));
					String heatmapTypeString = fieldMap.get("heatmapType");
					int gameTimeTicks = Integer.parseInt(fieldMap.getOrDefault("gameTimeTicks", "-1"));
					// The following fields exist only in version 101 and later
					int accountType = Integer.parseInt(fieldMap.getOrDefault("accountType", "-1"));;
					int currentCombatLevel = Integer.parseInt(fieldMap.getOrDefault("currentCombatLevel", "-1"));
					// The following fields exist only in version 102 and later
					String seasonalType = fieldMap.getOrDefault("seasonalType", "");

					// Get HeatmapType from field value if legit
					HeatmapType recognizedHeatmapType;
					if (Arrays.stream(HeatmapType.values()).noneMatch(type -> type.toString().equals(heatmapTypeString))) {
                        log.debug("Heatmap type '{}' from ZipEntry '{}' is not a valid Heatmap type (at least in this program version). Ignoring...", heatmapTypeString, curHeatmapPath);
						// Stop reading and go to next Heatmap type
						continue;
					} else {
						recognizedHeatmapType = HeatmapType.valueOf(heatmapTypeString);
					}

					// Make ze Heatmap and set metadata
					HeatmapNew heatmap = new HeatmapNew();
					heatmap.setHeatmapType(recognizedHeatmapType);
					heatmap.setUserID(userID);
					heatmap.setAccountType(accountType);
					heatmap.setGameTimeTicks(gameTimeTicks);
					heatmap.setCurrentCombatLevel(currentCombatLevel);
					heatmap.setSeasonalType(seasonalType);

					// Read and load the tile values
					final int[] errorCount = {0}; // Number of parsing errors occurred during read
					AtomicInteger numTiles = new AtomicInteger();
					reader.lines().forEach(s -> {
						String[] tile = s.split(",");
						try {
							heatmap.set(Integer.parseInt(tile[0]), Integer.parseInt(tile[1]), Integer.parseInt(tile[2]));
							numTiles.getAndIncrement();
						} catch (NumberFormatException e) {
							errorCount[0]++;
						}
					});
					if (errorCount[0] != 0) {
                        log.error("{} errors occurred during {} heatmap file read.", errorCount[0], recognizedHeatmapType);
					}
					loggingOutput.append(recognizedHeatmapType + " (" + numTiles + " tiles), ");
					heatmapsRead.put(recognizedHeatmapType, heatmap);
				} catch (IOException e) {
                    log.error("Error reading {} heatmap from .heatmaps entry '{}'", curType, curHeatmapPath);
				}
			}
			log.debug(loggingOutput.toString());
			return heatmapsRead;
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * V1.6.1 fix for file naming scheme. Checks the latest heatmap version in the directory, and if it's version 101 or
	 * earlier, renames the files after their dates modified.
	 * @param userId The user ID
	 * @param seasonalType The seasonal type
	 */
	protected static void fileNamingSchemeFix(long userId, @Nullable String seasonalType) {
		// Get latest .heatmaps file in the directory
		File latestHeatmap = HeatmapFile.getLatestHeatmapFile(userId, seasonalType);
		if (latestHeatmap == null) {
			return;
		}
		File directory = latestHeatmap.getParentFile();

		// Find the latest heatmap version
		long latestVersion = -1;
		Map<String, String> env = new HashMap<>();
		env.put("create", "true");
		HeatmapType[] types = HeatmapType.values();
		URI uri = URI.create("jar:" + latestHeatmap.toURI());
		try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
			for (HeatmapType curType : types) {
				Path curHeatmapPath = fs.getPath("/" + curType.toString() + "_HEATMAP.csv");
				if (!Files.exists(curHeatmapPath)) {
					continue;
				}

				// Read the heatmap
				try (InputStreamReader isr = new InputStreamReader(Files.newInputStream(curHeatmapPath), StandardCharsets.UTF_8);
					 BufferedReader reader = new BufferedReader(isr)) {
					// Read them field variables
					String[] fieldNames = reader.readLine().split(",");
					String[] fieldValues = reader.readLine().split(",");
					Map<String, String> fieldMap = new HashMap<>();
					for (int i = 0; i < fieldNames.length; i++) {
						fieldMap.put(fieldNames[i], fieldValues[i]);
					}
					long heatmapVersion = Long.parseLong(fieldMap.getOrDefault("heatmapVersion", "-1"));
					if (heatmapVersion > latestVersion) {
						latestVersion = heatmapVersion;
					}
				} catch (IOException e) {
					log.error("Error reading {} heatmap from .heatmaps entry '{}'", curType, curHeatmapPath);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		// Rename the files if necessary
		if (latestVersion <= 101) {
			log.info("Performing naming scheme fix on .heatmaps files in directory '{}'...", directory.getName());
			List<File> heatmapFiles = new ArrayList<>();
			for (File file : Objects.requireNonNull(directory.listFiles())) {
				if (file.getName().endsWith(".heatmaps")) {
					heatmapFiles.add(file);
				}
			}

			// Sort the files by parseable date
			heatmapFiles.sort((f1, f2) -> {
				String name1 = f1.getName().split("\\.")[0];
				String name2 = f2.getName().split("\\.")[0];
				LocalDateTime f1Date = LocalDateTime.parse(name1, HeatmapFile.dateFormat);
				LocalDateTime f2Date = LocalDateTime.parse(name2, HeatmapFile.dateFormat);
				return f1Date.compareTo(f2Date);
			});

			// Cycle through them, renaming them after their date modified
			for (File file : heatmapFiles) {
				long epochMillis = file.lastModified();

				// Convert to ZonedDateTime in the local time zone
				ZonedDateTime lastModified = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());

				// Format the new name
				String newName = HeatmapFile.dateFormat.format(lastModified);
				File newFile = new File(directory, newName + ".heatmaps");

				// Rename the file
				if (!file.renameTo(newFile)) {
					System.err.println("Could not rename file '" + file.getName() + "' to '" + newFile.getName() + "'");
				}
			}
		}
	}
}