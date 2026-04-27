package com.worldheatmap;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import net.runelite.api.ChatMessageType;
import static net.runelite.client.RuneLite.RUNELITE_DIR;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.WorldService;
import net.runelite.client.hiscore.HiscoreManager;
import net.runelite.client.hiscore.HiscoreResult;
import okhttp3.OkHttpClient;

@Slf4j
public class HeatmapFileManager
{
    protected final static String HEATMAP_EXTENSION = ".heatmaps";
    protected final static File WORLD_HEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap");
    protected final static File HEATMAP_FILES_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Files").toFile();
    protected final static File HEATMAP_IMAGE_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Images").toFile();
	protected final static DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
	private final WorldHeatmapPlugin plugin;

	public HeatmapFileManager(WorldHeatmapPlugin plugin){
		this.plugin = plugin;
	}

	/**
	 * Return a File in the correct directory, either named after the current time or the latest file in the directory,
	 * whichever comes last. If they are equal, returns a new file named mustReturnNewFile minutes later than the
	 * latest file. If mustReturnNewFile is zero, then it returns the latest file.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	private File getFile(long userId, String seasonalType, boolean mustReturnNewFile) {
		boolean isSeasonal = !seasonalType.isBlank();
		File userIdDir = new File(HEATMAP_FILES_DIR, userId + (isSeasonal ? "_" + seasonalType : ""));
		// Find the most recent file
		File latestFile = getLatestFile(userId, seasonalType);

		// Parse its date
		LocalDateTime dateOfLatestFile = null;
		if (latestFile != null && latestFile.exists()) {
			String name = latestFile.getName();
			int pos = name.lastIndexOf(HEATMAP_EXTENSION);
			name = name.substring(0, pos);
			dateOfLatestFile = LocalDateTime.parse(name, dateFormat);
		}

		LocalDateTime timeToUse;
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
		if (dateOfLatestFile == null || now.isAfter(dateOfLatestFile)) {
			timeToUse = now;
		}
		else {
			timeToUse = dateOfLatestFile.plus(Duration.ofMinutes(mustReturnNewFile ? 1 : 0));
		}
		String fileName = timeToUse.format(dateFormat) + HEATMAP_EXTENSION;

		return new File(userIdDir, fileName);
	}

	/**
	 * Returns a new heatmaps File named after the current time, or one minute past the latest file, whichever comes last.
	 * Doesn't actually create the file, just a File object.
	 * @param userId The user ID
	 * @param seasonalType The seasonal type, or empty string if not seasonal
	 * @return The new heatmaps File
	 */
    public File getNewFile(long userId, String seasonalType) {
		return getFile(userId, seasonalType, true);
    }

	/**
	 * Returns a new heatmaps File named after the current time, or the latest heatmaps File if it's dated into the future
	 * This is generally for updating the name of modified files.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	public File getCurrentFile(long userId, String seasonalType) {
		return getFile(userId, seasonalType, false);
	}

	/**
	 * Returns a File named after the current date and time, even if it already exists.
	 * @param userId The user ID
	 * @param type The heatmap type
	 * @param seasonalType The seasonal type, or null if not seasonal
	 * @return
	 */
    public static File getNewImageFile(long userId, HeatmapNew.HeatmapType type, String seasonalType) {
		boolean isSeasonal = !seasonalType.isBlank();
        String dateString = formatDate(LocalDateTime.now());
        File userIdDir = new File(HEATMAP_IMAGE_DIR, Long.toString(userId) + (isSeasonal ? "_" + seasonalType : ""));

        return new File(userIdDir, type + "_" + dateString + ".tif");
    }

    /**
     * Returns the .heatmaps file in the given directory whose filename is the most recent parseable date string.
     * Returns null if no such file exists.
	 * @param accountHash the user ID/account hash
	 * @param seasonalType the seasonal type, or null if not seasonal
     * @return the file with the most recent date in its filename, or null if no such file exists
     */
    public File getLatestFile(long accountHash, String seasonalType) {
		boolean isSeasonal = !seasonalType.isBlank();
		// currentDir may be normal or seasonal
        File currentDir = new File(HEATMAP_FILES_DIR, accountHash + (isSeasonal ? "_" + seasonalType : ""));
		return getLatestFile(currentDir);
	}

	/**
	 * Returns the .heatmaps file in the given directory whose filename is the most recent parseable date string.
	 * @param path the directory to search
	 * @return the file with the most recent date in its filename, or null if no such file exists
	 */
	private File getLatestFile(File path) {
		// If the latest file is found, return it
		File[] files = getSortedFiles(path);
		if (files.length == 0) {
			return null;
		}
		return files[0];
	}

	/**
	 * Runs fixes on the user's heatmaps files, if necessary, for backwards compatability.
	 * @param accountHash the user ID/account hash
	 * @param username the player's username
	 */
	protected void fixHeatmapsFiles(long accountHash, String username) {
		File normalDir = new File(HEATMAP_FILES_DIR, Long.toString(accountHash));

		// Modernize any legacy heatmap files, stashing them in the regular directory
		// (since they won't have seasonal type encoded)
		carryOverV1_0Files(username, accountHash, normalDir);
		carryOverV1_2Files(accountHash, normalDir);

		// Perform V1.6.0 file naming scheme fix on normal directory if necessary
		Long latestHeatmapVersion = getLatestHeatmapVersion(normalDir);
		if (latestHeatmapVersion != null && latestHeatmapVersion <= 101) {
			fileNamingSchemeFix(normalDir);
		}

		// Perform V.1.6.1 fix for deaths being counted as teleports
		if (latestHeatmapVersion != null && latestHeatmapVersion <= 102) {
			deathsAsTeleportsFix(accountHash, "");
			deathsAsTeleportsFix(accountHash, "LEAGUES_V");
			deathsAsTeleportsFix(accountHash, "LEAGUES_VI");
		}
	}

	/*
	 * Subtracts DEATHS from TELEPORTED_FROM heatmap in latest file,
	 */
	private void deathsAsTeleportsFix(long accountHash, String seasonalType)
	{
		File latestFile = getLatestFile(accountHash, seasonalType);
		if (latestFile == null) {
			return;
		}
		File newFile = getCurrentFile(accountHash, seasonalType);

		try {
			// Read all heatmap types (because we want to rewrite even the ones not needing to be modified, to update their version)
			HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = readHeatmapsFromFile(latestFile, List.of(HeatmapNew.HeatmapType.values()), false);
			HeatmapNew deaths = heatmaps.get(HeatmapNew.HeatmapType.DEATHS);

			HeatmapNew teleportedFrom = heatmaps.get(HeatmapNew.HeatmapType.TELEPORTED_FROM);

			if (teleportedFrom != null && deaths != null && deaths.getTileCount() > 0) {
				teleportedFrom.subtract(deaths, 0);
			}

			writeHeatmapsToFile(heatmaps.values(), newFile, false);
		} catch (IOException e) {
			log.error("Error applying deaths as teleports fix for account {} (seasonal type: \"{}\": {}", accountHash, seasonalType, e.toString());
		}
	}

	/**
	 * Detects V1.2 heatmap files, moves them to the new location, and renames them after their date modified.
	 * Also deletes the legacy files, since the new format is compatible with the old data and there's no risk
	 * of data loss.
	 *
	 * The V1.2 format is the single-heatmap format where all heatmap types were stored in one file, but the
	 * file was still named after the account hash and stored in the legacy location, instead of being named
	 * after the date and stored in a user ID folder like in the new format.
	 *
	 * @param accountHash The user ID/account hash
	 * @param newDir      The new directory to move the files to
	 */
	private void carryOverV1_2Files(long accountHash, File newDir) {
		File oldFile = new File(HEATMAP_FILES_DIR, accountHash + HEATMAP_EXTENSION);
		if (oldFile.exists()) {
			// Move the old file to the new location, naming it after its date modified
			long epochMillis = oldFile.lastModified();
			ZonedDateTime lastModified = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
			String newName = dateFormat.format(lastModified);
			File newFile = new File(newDir, newName + HEATMAP_EXTENSION);

			// Move the file by rewriting it, to update its version
			try {
				log.info("Moving heatmaps file from legacy (V1.2) location {} to new location {}", oldFile, newFile);
				HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = readHeatmapsFromFile(oldFile, List.of(HeatmapNew.HeatmapType.values()), false);
				// Update the account hash cuz we have it available
				heatmaps.values().forEach(h -> {
					h.setUserID(accountHash);
					h.setSeasonalType("");
				});
				writeHeatmapsToFile(heatmaps.values(), newFile, false);

				// Delete the old file
				Files.delete(oldFile.toPath());

				// Set its date modified to what it was before
				newFile.setLastModified(epochMillis);

				// If the moved file is the latest one in the folder,
				// make a second copy of the moved file, named one minute later,
				// so we can keep the other one as an updated backup
				if (getLatestFile(newDir) == newFile) {
					File newFile2 = new File(newDir, dateFormat.format(lastModified.plusMinutes(1)) + HEATMAP_EXTENSION);
					Files.copy(newFile.toPath(), newFile2.toPath());
					newFile2.setLastModified(lastModified.plusMinutes(1).toInstant().toEpochMilli());
				}
			} catch (IOException e) {
				log.error("Moving heatmaps file from legacy (V1.2) location failed");
			}

		}
	}

	/**
	 * Detects V1.0 heatmap files, converts them to the new format, moves them to the new location,
	 * and renames them after their date modified. Also appends .old to the legacy files and backup
	 * folder, in case anything goes wrong and the user needs to manually recover their data.
	 *
	 * The V1.0 format is the old format where there were separate files for each heatmap
	 * type, named after the account hash and heatmap type, and stored in the legacy location
	 * instead of being named after the date and stored in a user ID folder like in the new format.
	 *
	 * @param currentPlayerName The player name
	 * @param newDir            The new directory to move the files to
	 */
	private void carryOverV1_0Files(String currentPlayerName, long accountHash, File newDir) {
		// Check if TYPE_A V1.0 file exists, and if it does, convert it to the new format
		// rename the old file to .old, and write the new file
		File typeAFile = new File(HEATMAP_FILES_DIR.toString(), currentPlayerName + "_TypeA.heatmap");
		HeatmapNew typeAHeatmap = new HeatmapNew(HeatmapNew.HeatmapType.TYPE_A, -1, -1, null, -1);
		ZonedDateTime typeAModified = null;
		boolean typeAExisted = false;
		if (typeAFile.exists()) {
			log.info("Found legacy Type A heatmap file for user {}. Converting to new format and moving to proper directory...", currentPlayerName);
			typeAExisted = true;
			// Get date modified
			typeAModified = ZonedDateTime.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(typeAFile.lastModified()), ZoneId.systemDefault()), ZoneId.systemDefault());

			// Load and convert the legacy heatmap file
			typeAHeatmap = HeatmapNew.readLegacyV1HeatmapFile(typeAFile);
			typeAHeatmap.setUserID(accountHash);
			typeAHeatmap.setSeasonalType("");
			typeAHeatmap.setHeatmapType(HeatmapNew.HeatmapType.TYPE_A);

			// Append '.old' to legacy file name
			File dotOldTypeA = new File(typeAFile + ".old");
			typeAFile.renameTo(dotOldTypeA);
		}

		// Repeat for Type B
		File typeBFile = new File(HEATMAP_FILES_DIR.toString(), currentPlayerName + "_TypeB.heatmap");
		HeatmapNew typeBHeatmap = new HeatmapNew(HeatmapNew.HeatmapType.TYPE_B, -1, -1, null, -1);
		ZonedDateTime typeBModified = null;
		boolean typeBExisted = false;
		if (typeBFile.exists()) {
			log.info("Found legacy Type B heatmap file for user {}. Converting to new format and moving to proper directory...", currentPlayerName);
			typeBExisted = true;
			// Get date modified
			typeBModified = ZonedDateTime.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(typeBFile.lastModified()), ZoneId.systemDefault()), ZoneId.systemDefault());

			// Load, rename, and rewrite the legacy heatmap file to the proper location
			typeBHeatmap = HeatmapNew.readLegacyV1HeatmapFile(typeBFile);
			typeBHeatmap.setUserID(accountHash);
			typeBHeatmap.setSeasonalType("");
			typeBHeatmap.setHeatmapType(HeatmapNew.HeatmapType.TYPE_B);

			// Make '.old' copy
			File dotOldTypeB = new File(typeBFile + ".old");
			typeBFile.renameTo(dotOldTypeB);
		}

		// Append .old to Paths.get(HEATMAP_FILES_DIR, "Backups") folder if it exists
		File backupDir = Paths.get(HEATMAP_FILES_DIR.toString(), "Backups").toFile();
		if (backupDir.exists()) {
			File oldBackupDir = new File(backupDir.toString() + ".old");
			backupDir.renameTo(oldBackupDir);
		}

		// If either one existed, write them to a new file
		if (typeAExisted || typeBExisted) {
			// Set the new file's date modified to the latest of the two legacy files
			ZonedDateTime latestModified;
			if (typeAModified == null) {
				latestModified = typeBModified;
			}
			else if (typeBModified == null) {
				latestModified = typeAModified;
			}
			else {
				latestModified = typeAModified.isAfter(typeBModified) ? typeAModified : typeBModified;
			}

			File newFile = new File(newDir, dateFormat.format(latestModified) + HEATMAP_EXTENSION);
			this.writeHeatmapsToFile(List.of(typeAHeatmap, typeBHeatmap), newFile);
			newFile.setLastModified(latestModified.toInstant().toEpochMilli());

			// If the new file is the latest one in the folder,
			// Make a second copy of the moved file, named one minute later
			// so we can keep the other one as an updated backup
			if (getLatestFile(newDir) == newFile) {
				File newFile2 = new File(newDir, dateFormat.format(latestModified.plusMinutes(1)) + HEATMAP_EXTENSION);
				try {
					Files.copy(newFile.toPath(), newFile2.toPath());
					newFile2.setLastModified(latestModified.plusMinutes(1).toInstant().toEpochMilli());
				} catch (IOException e) {
					log.error("Failed to make extra copy of converted legacy shmeatmap file: {}", e.toString());
				}
			}
		}
	}

	private static String formatDate(LocalDateTime dateTime) {
		return dateTime.format(dateFormat);
	}

    /**
	 * Returns an array of files in the given directory whose filenames are parseable date strings, sorted by date.
	 * @param path the directory to search
	 * @return an array of files with parseable dates in their filenames, sorted by date (most recent first)
     */
	private File[] getSortedFiles(File path) {
		File[] files = path.listFiles(File::isFile);
		if (files == null || files.length == 0) {
			return new File[0];
		}

		files = Arrays.stream(files)
			.filter(file -> {
				String name = file.getName();
				int pos = name.lastIndexOf(".heatmaps");
				if (pos == -1) {
					return false;
				}
				name = name.substring(0, pos);
				try {
					LocalDateTime.parse(name, dateFormat);
					return true;
				} catch (Exception e) {
					return false;
				}
			})
			.toArray(File[]::new);

		Arrays.sort(files, (f1, f2) -> {
			try {
				String n1 = f1.getName();
				String n2 = f2.getName();
				int pos1 = n1.lastIndexOf(".heatmaps");
				int pos2 = n2.lastIndexOf(".heatmaps");
				n1 = n1.substring(0, pos1);
				n2 = n2.substring(0, pos2);

				LocalDateTime d1 = LocalDateTime.parse(n1, dateFormat);
				LocalDateTime d2 = LocalDateTime.parse(n2, dateFormat);
				return d2.compareTo(d1);
			} catch (Exception e) {
				return 0;
			}
		});

		return files;
	}

	protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, @Nullable File previousHeatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, previousHeatmapsFile, true);
	}

	protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, null, true);
	}

	/**
	 * Writes the provided heatmap data to the specified .heatmaps file. If the file already exists, it will be updated,
	 * and unprovided heatmaps already in the file will remain.
	 * @param heatmapsToWrite The heatmaps to write
	 * @param heatmapsFile The .heatmaps file
	 */
	protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, boolean verbose) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, null, verbose);
	}

	/**
	 * Writes the provided heatmap data to the specified .heatmaps file. Unprovided heatmaps are carried over from the file previousHeatmapsFile, if it has them.
	 * If heatmapsFile already exists, existing unprovided heatmaps will be kept. Heatmaps in previousHeatmapsFile take precedence over heatmaps in heatmapsFile
	 * if heatmapsFile already exists.
	 * @param heatmapsToWrite The heatmaps to write
	 * @param heatmapsFile The .heatmaps file
	 * @param previousHeatmapsFile The previous .heatmaps file.
	 */
	protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, @Nullable File previousHeatmapsFile, boolean verbose) {
		// Preamble
		if (verbose) {
			log.info("Saving heatmaps to file '{}'...", heatmapsFile.getName());
		}

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
					heatmap.toCSV(osw);
					osw.flush(); // Not sure if this is necessary
				}
			} catch (IOException e) {
                log.error("World Heatmap was not able to save heatmap type '{}' to file '{}'", heatmap.getHeatmapType(), heatmapsFile.getName());
				e.printStackTrace();
				return;
			}
			loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getTileCount() + " tiles), ");
		}

		if (verbose) {
			log.info(loggingOutput.toString());
			log.info("Finished writing '{}' heatmap file to disk after {} ms", heatmapsFile.getName(), (System.nanoTime() - startTime) / 1_000_000);
		}
	}

	/**
	 * Loads the specified heatmap types from the given .heatmaps file.
	 *
	 * @param heatmapsFile The .heatmaps file
	 * @param types        The heatmap types to load
	 * @return HashMap of HeatmapNew objects
	 * @throws FileNotFoundException If the file does not exist
	 */
	HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapsFile, Collection<HeatmapNew.HeatmapType> types) throws FileNotFoundException {
		return readHeatmapsFromFile(heatmapsFile, types, true);
	}

	/**
	 * Loads the specified heatmap types from the given .heatmaps file.
	 *
	 * @param heatmapsFile The .heatmaps file
	 * @param types        The heatmap types to load
	 * @param verbose      Whether to log the heatmap types loaded
	 * @return HashMap of HeatmapNew objects
	 * @throws FileNotFoundException If the file does not exist
	 */
	HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapsFile, Collection<HeatmapNew.HeatmapType> types, boolean verbose) throws FileNotFoundException {
		Map<String, String> env = new HashMap<>();
		env.put("create", "true");
		URI uri = URI.create("jar:" + heatmapsFile.toURI());
		try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
			HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmapsRead = new HashMap<>();
			StringBuilder loggingOutput = new StringBuilder();
			loggingOutput.append("Heatmap types loaded: ");

			for (HeatmapNew.HeatmapType curType : types) {
				Path curHeatmapPath = fs.getPath("/" + curType.toString() + "_HEATMAP.csv");
				if (!Files.exists(curHeatmapPath)) {
					continue;
				}
				try (InputStreamReader isr = new InputStreamReader(Files.newInputStream(curHeatmapPath), StandardCharsets.UTF_8);
					 BufferedReader reader = new BufferedReader(isr)) {
					HeatmapNew heatmap = HeatmapNew.fromCSV(curType, reader);
					heatmapsRead.put(heatmap.getHeatmapType(), heatmap);
					loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getTileCount() + " tiles), ");
				} catch (IOException e) {
                    log.error("Error reading {} heatmap from .heatmaps entry '{}'", curType, curHeatmapPath);
				}
			}
			if (verbose) {
				log.info(loggingOutput.toString());
			}
			return heatmapsRead;
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * V1.6.0 fix for file naming scheme.
	 * I think this fix is to address how previously, due to a syntax typo, the date format was using
	 * 12-hour time for the hours instead of 24-hour time, which caused issues with the ordering of
	 * files and determining which file is the latest, resulting in some random time jumps and overwriting.
	 * The fix is to rename all .heatmaps files to be named after their date modified, using the correct date format with 24-hour time.
	 *
	 * At least I think that's what this does. I forgets, and I ain't bothered to check.
	 * @param directory The directory to fix
	 */
	protected void fileNamingSchemeFix(File directory) {
		// Rename the files
		log.info("Performing naming scheme fix on .heatmaps files in directory '{}'...", directory.getName());
		List<File> heatmapFiles = new ArrayList<>();
		for (File file : Objects.requireNonNull(directory.listFiles())) {
			if (file.getName().endsWith(".heatmaps")) {
				heatmapFiles.add(file);
			}
		}

		// Sort the files by parseable date descending
		heatmapFiles.sort((f1, f2) -> {
			String name1 = f1.getName().split("\\.")[0];
			String name2 = f2.getName().split("\\.")[0];
			LocalDateTime f1Date = LocalDateTime.parse(name1, dateFormat);
			LocalDateTime f2Date = LocalDateTime.parse(name2, dateFormat);
			return f2Date.compareTo(f1Date);
		});

		// Cycle through them, renaming them after their date modified
		for (File file : heatmapFiles) {
			long epochMillis = file.lastModified();

			// Convert to ZonedDateTime in the local time zone
			ZonedDateTime lastModified = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());

			// Format the new name
			String newName = dateFormat.format(lastModified);
			File newFile = new File(directory, newName + ".heatmaps");

			// Rename the file
			if (file.renameTo(newFile)) {
				log.info("Renamed {} to {}", file.getName(), newFile.getName());

				// Set its date modified to what it was before
				if (!newFile.setLastModified(epochMillis)){
					log.error("Could not set last modified time of file '{}' back to what it was", newFile.getName());
				}
			}
			else {
				log.error("Could not rename file '{}' to '{}'", file.getName(), newFile.getName());
			}
		}
	}

	private Long getLatestHeatmapVersion(File directory)
	{
		// Get latest .heatmaps file in the directory
		File latestHeatmap = getLatestFile(directory);
		if (latestHeatmap == null) {
			return null;
		}

		// Find the latest heatmap version of the latest .heatmaps file
		long latestVersion = -1;
		try {
			Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = readHeatmapsFromFile(latestHeatmap, List.of(HeatmapNew.HeatmapType.values()), false);
			for (HeatmapNew heatmap : heatmaps.values()) {
				if (heatmap.getVersionReadFrom() > latestVersion) {
					latestVersion = heatmap.getVersionReadFrom();
				}
			}
		}
		catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
		return latestVersion;
	}

	/**
	 * Displays a message to the user about the latest update.
	 * Only displays the message once per update.
	 */
	private void displayLeaguesVNotice() {
		String noticeKey = "leaguesVDecontaminationNotice";
		if (plugin.configManager.getConfiguration("worldheatmap", noticeKey) == null) {
			// Send a message in game chat
			final String message = new ChatMessageBuilder()
				.append(Color.decode("#a100a1"), "An automatic fix has been applied to your World Heatmap data in " +
					"order to separate Leagues V data from regular account data. Open your heatmaps folder via " +
					"the side panel and check out the Leagues V folder for more information.")
				.build();
			plugin.chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(message)
				.build());
			plugin.configManager.setConfiguration("worldheatmap", noticeKey, "shown");
		}
	}
}
