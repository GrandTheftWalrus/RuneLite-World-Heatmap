package com.worldheatmap;

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
import java.time.LocalDate;
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

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
public class HeatmapFile {
    protected final static String HEATMAP_EXTENSION = ".heatmaps";
    protected final static File WORLD_HEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap");
    protected final static File HEATMAP_FILES_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Files").toFile();
    protected final static File HEATMAP_IMAGE_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Images").toFile();
	protected final static ZonedDateTime startOfLeaguesV = ZonedDateTime.of(LocalDateTime.of(2024, 11, 27, 12, 0), ZoneId.of("GMT"));
	protected final static LocalDate endOfLeaguesV = LocalDate.of(2025, 1, 23);
	protected final static DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");

	/**
	 * Return a File in the correct directory, either named after the current time or the latest file in the directory,
	 * whichever comes last. If they are equal, returns a new file named onConflictOffset minutes later than the
	 * latest file. If onConflictOffset is zero, then it returns the latest file.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	public static File getHeatmapFile(long userId, String seasonalType, String username, int onConflictOffset) {
		boolean isSeasonal = !seasonalType.isBlank();
		File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId) + (isSeasonal ? "_" + seasonalType : ""));
		// Find the next available filename
		File latestFile = getLatestHeatmapFile(userId, seasonalType, username);
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
			timeToUse = dateOfLatestFile.plus(Duration.ofMinutes(onConflictOffset));
		}
		String fileName = timeToUse.format(dateFormat) + HEATMAP_EXTENSION;

		return new File(userIdDir, fileName);
	}

	/**
	 * Returns a new heatmaps File named after the current time, or one minute past the latest file if it already exists.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
    public static File getNewHeatmapFile(long userId, String seasonalType, String username) {
		return getHeatmapFile(userId, seasonalType, username, 1);
    }

	/**
	 * Returns a new heatmaps File named after the current time, or the latest file if it already exists.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	public static File getCurrentHeatmapFile(long userId, String seasonalType, String username) {
		return getHeatmapFile(userId, seasonalType, username, 0);
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
     * Get the File that contains the latest heatmaps based on the filename being a date.
     * Returns null if no such file exists.
	 * @param accountHash the user ID/account hash
	 * @param seasonalType the seasonal type, or null if not seasonal
	 * @param username the player's username. Used for detecting legacy files.
     * @return the youngest heatmaps file.
     */
    public static File getLatestHeatmapFile(long accountHash, String seasonalType, String username) {
		boolean isSeasonal = !seasonalType.isBlank();
		// heatmapsDir may or may not be the seasonal dir
        File heatmapsDir = new File(HEATMAP_FILES_DIR, accountHash + (isSeasonal ? "_" + seasonalType : ""));
		File normalHeatmapsDir = new File(HEATMAP_FILES_DIR, Long.toString(accountHash));

		// Modernize any legacy heatmap files, stashing them in the regular directory (since they won't have seasonal type encoded)
		convertAndMoveV1_0HeatmapFiles(username, accountHash, normalHeatmapsDir);
		moveV1_2HeatmapFiles(accountHash, normalHeatmapsDir);

		// Carry out the leagues decontamination process if necessary
		if (!heatmapsDir.exists() && isSeasonal) {
			if (normalHeatmapsDir.exists() && LocalDate.now().isBefore(endOfLeaguesV)) {
				handleLeaguesDecontamination(normalHeatmapsDir, heatmapsDir);
			}
		}

		// If the latest file is found, return it
		return getLatestFile(heatmapsDir);
	}

	/**
	 * Detects V1.2 heatmaps files, moves them to the new location, and renames them after their date modified.
	 *
	 * @param accountHash The user ID/account hash
	 * @param newDir      The new directory to move the files to
	 */
	private static void moveV1_2HeatmapFiles(long accountHash, File newDir) {
		File v1_2File = new File(HEATMAP_FILES_DIR, accountHash + HEATMAP_EXTENSION);
		if (v1_2File.exists()) {
			// Move the old file to the new location, naming it after its date modified
			long epochMillis = v1_2File.lastModified();
			ZonedDateTime lastModified = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
			String newName = dateFormat.format(lastModified);
			File movedLegacyV1_2File = new File(newDir, newName + HEATMAP_EXTENSION);

			// Make the directory path if it doesn't exist
			if (!movedLegacyV1_2File.mkdirs()) {
				log.error("Couldn't make dirs to move heatmaps file from legacy V1.2 location. Aborting move operation, but returning the file.");
				return;
			}

			// Move the file
			try {
				log.info("Moving heatmaps file from legacy (V1.2) location {} to new location {}", v1_2File, movedLegacyV1_2File);
				Files.move(v1_2File.toPath(), movedLegacyV1_2File.toPath(), StandardCopyOption.REPLACE_EXISTING);

				// Set its date modified to what it was before
				movedLegacyV1_2File.setLastModified(epochMillis);

				// Make a second copy of the moved file, named one minute later,
				// so we can keep the other one as an updated backup
				File newFile2 = new File(newDir, dateFormat.format(lastModified.plusMinutes(1)) + HEATMAP_EXTENSION);
				Files.copy(movedLegacyV1_2File.toPath(), newFile2.toPath());
				newFile2.setLastModified(lastModified.plusMinutes(1).toInstant().toEpochMilli());
			} catch (IOException e) {
				log.error("Moving heatmaps file from legacy (V1.2) location failed");
			}

		}

		// If the file doesn't exist, return null
	}

	/**
	 * Returns V1.0 heatmap files if they exist, after having converted them to the new format and moving them to the new location.
	 * Also keeps an '.old' copy of the old files
	 * Also renames the old 'Backups' directory to 'Backups.old' if it exists
	 *
	 * @param currentPlayerName The player name
	 * @param currentPlayerId   The player ID
	 * @param newDir            The new directory to move the files to
	 */
	private static void convertAndMoveV1_0HeatmapFiles(String currentPlayerName, long currentPlayerId, File newDir) {
		// Check if TYPE_A V1.0 file exists, and if it does, convert it to the new format
		// rename the old file to .old, and write the new file
		File typeAFile = new File(HEATMAP_FILES_DIR.toString(), currentPlayerName + "_TypeA.heatmap");
		HeatmapNew typeAHeatmap = new HeatmapNew(HeatmapNew.HeatmapType.TYPE_A, -1, -1, null, -1);
		ZonedDateTime typeAModified = null;
		boolean typeAExisted = false;
		if (typeAFile.exists()) {
			typeAExisted = true;
			// Get date modified
			typeAModified = ZonedDateTime.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(typeAFile.lastModified()), ZoneId.systemDefault()), ZoneId.systemDefault());

			// Load and convert the legacy heatmap file
			typeAHeatmap = HeatmapNew.readLegacyV1HeatmapFile(typeAFile);
			typeAHeatmap.setHeatmapType(HeatmapNew.HeatmapType.TYPE_A);

			// Append '.old' to legacy file name
			File dotOldTypeA = new File(typeAFile + ".old");
			typeAFile.renameTo(dotOldTypeA);
		}

		// Repeat for Type B
		File typeBFile = new File(HEATMAP_FILES_DIR.toString(), currentPlayerName + "_TypeB.heatmap");
		HeatmapNew typeBHeatmap = new HeatmapNew(HeatmapNew.HeatmapType.TYPE_B, currentPlayerId, -1, null, -1);
		ZonedDateTime typeBModified = null;
		boolean typeBExisted = false;
		if (typeBFile.exists()) {
			typeBExisted = true;
			// Get date modified
			typeBModified = ZonedDateTime.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(typeBFile.lastModified()), ZoneId.systemDefault()), ZoneId.systemDefault());

			// Load, rename, and rewrite the legacy heatmap file to the proper location
			typeBHeatmap = HeatmapNew.readLegacyV1HeatmapFile(typeBFile);
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
			HeatmapFile.writeHeatmapsToFile(List.of(typeAHeatmap, typeBHeatmap), newFile);
			newFile.setLastModified(latestModified.toInstant().toEpochMilli());

			// Make a second copy of the moved file, named one minute later
			// so we can keep the other one as an updated backup
			File newFile2 = new File(newDir, dateFormat.format(latestModified.plusMinutes(1)) + HEATMAP_EXTENSION);
			try {
				Files.copy(newFile.toPath(), newFile2.toPath());
				newFile2.setLastModified(latestModified.plusMinutes(1).toInstant().toEpochMilli());
			} catch (IOException e) {
				log.error("Failed to make extra copy of converted legacy shmeatmap file: {}", e.toString());
			}

		}
		// Else, return null
		else {
		}
	}

	/**
	 * The following is a fix for the realization that leagues (and all other world-based game modes)
	 * were being permanently mixed with regular heatmaps. This fix moves all leagues-contaminated
	 * heatmap files into a "seasonal" directory, and rolls back normal heatmaps to a state before the
	 * leagues contamination. It also creates a text file explaining the situation.
	 * @param regularHeatmapsDir the directory containing the regular heatmaps
	 * @param seasonalHeatmapsDir the directory to move the contaminated heatmaps to
	 */
	private static void handleLeaguesDecontamination(File regularHeatmapsDir, File seasonalHeatmapsDir) {
		// Create seasonal directory
		if (!seasonalHeatmapsDir.mkdirs()) {
			log.error("Failed to create seasonal heatmaps directory");
			return;
		}

		// Move potentially contaminated files from the normal heatmap directory to the seasonal directory
		File[] files = getSortedFiles(regularHeatmapsDir);
		if (files != null) {
			for (File originFile : files) {
				String name = originFile.getName();
				int pos = name.lastIndexOf(".");
				name = name.substring(0, pos);
				ZonedDateTime fileDate = ZonedDateTime.of(LocalDateTime.parse(name, dateFormat), ZoneId.systemDefault());
				if (fileDate.isAfter(startOfLeaguesV)) {
					try {
						// Move it to the seasonal directory, updating its metadata
						log.info("File {} is contaminated by Leagues V, moving to Leagues V directory", originFile.getName());
						HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = readHeatmapsFromFile(originFile, List.of(HeatmapNew.HeatmapType.values()), false);
						heatmaps.values().stream().forEach(h -> {
							h.setSeasonalType("LEAGUES_V");
						});
						File destinationFile = new File(seasonalHeatmapsDir, originFile.getName());
						writeHeatmapsToFile(heatmaps.values(), destinationFile, false);

						// Delete the original file
						Files.delete(originFile.toPath());
					}
					catch (IOException e)
					{
						log.error("Failed to move file to seasonal directory and/or update its metadata: {}", e.toString());
					}
				}
			}
		}

		// Create a text file explaining the situation, for when the user eventually investigates
		String today = formatDate(LocalDateTime.now());
		File leaguesExplanationFile = new File(seasonalHeatmapsDir, today + "_leagues_incident.txt");
		try {
			Files.write(leaguesExplanationFile.toPath(), (
					"This directory contains regular account heatmaps that were contaminated by Leagues V data.\n" +
					"Version 1.5.1 of the plugin didn't keep separate heatmaps for Leagues/seasonal\n" +
					"accounts and regular accounts because I didn't realize that each player's\n" +
					"Leagues/seasonal account would have the same Account ID and Account Type code\n" +
					"(regular/ironman/group ironman etc.) as their regular account. So, data being\n" +
					"uploaded to the website https://osrsworldheatmap.com was looking kinda sus\n" +
					"such as how TELEPORTED_TO had a bunch of entries that would normally be impossibru\n" +
					"for regular accounts. The only way to semi-fix it, that I could think of, was to\n" +
					"designate the potentially contaminated .heatmaps files of anyone who logged into Leagues V\n" +
					"between the release of WorldHeatmap v1.6.0 and the end of Leagues V (Jan 22nd, 2025), as a\n" +
					"leagues heatmap (which is why you're seeing this), and roll-back any regular heatmaps\n" +
					"to the latest existing pre-leagues backup. That way, the personal data that players spent\n" +
					"a long time collecting wouldn't be lost, whilst somewhat unfugging the global heatmap.\n" +
					"In the future (or perhaps in the past?) I'm  thinking that I'll add a feature to the \n" +
					"website where you can open a local .heatmaps file for visualization, so you can more\n" +
					"easily take a gander at your old data in this folder. If you have any questions or \n" +
					"concerns, please contact me somehow or make an issue on the GitHub page for the plugin:\n" +
					"https://github.com/GrandTheftWalrus/RuneLite-World-Heatmap\n"+
					"\n" +
					"P.S. If you're absolutely certain that you didn't play Leagues V until a certain date\n" +
					"post-release, then I gueeeeesss you could move the allegedly unaffected .heatmap files from\n" +
					"this folder back to the regular folder if you see this message in time \uD83E\uDD28 but\n" +
					"you better be deadass or else you'll be messing up the global heatmap if you're opted-in\n" +
					"to contributing to it. If not, then I spose you can whatever you want with your own data.\n"
			).getBytes());
		} catch (IOException e) {
			log.error("Failed to write leagues explanation file: {}", e.toString());
		}
	}

	private static String formatDate(LocalDateTime dateTime) {
		return dateTime.format(dateFormat);
	}

	/**
	 * Returns the file in the given directory whose filename is the most recent parseable date string.
	 * @param path the directory to search
	 * @return the file with the most recent date in its filename, or null if no such file exists
	 */
	private static File getLatestFile(File path) {
		File[] files = getSortedFiles(path);
		if (files == null || files.length == 0) {
			return null;
		}
		return files[0];
	}

    /**
     * Returns the file in the given directory whose filename is the most recent parseable date string before the given date.
     * If no such file is found, returns null.
     * @param path the directory to search
     * @return the file with the most recent date in its filename, or null if no such file exists
     */
	private static File[] getSortedFiles(File path) {
		File[] files = path.listFiles(File::isFile);
		if (files == null || files.length == 0) {
			return null;
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

	protected static void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, @Nullable File previousHeatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, previousHeatmapsFile, true);
	}

	protected static void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, null, true);
	}

	/**
	 * Writes the provided heatmap data to the specified .heatmaps file. If the file already exists, it will be updated,
	 * and unprovided heatmaps already in the file will remain.
	 * @param heatmapsToWrite The heatmaps to write
	 * @param heatmapsFile The .heatmaps file
	 */
	protected static void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, boolean verbose) {
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
	protected static void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, @Nullable File previousHeatmapsFile, boolean verbose) {
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
			log.debug(loggingOutput.toString());
			log.debug("Finished writing '{}' heatmap file to disk after {} ms", heatmapsFile.getName(), (System.nanoTime() - startTime) / 1_000_000);
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
	static HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapsFile, Collection<HeatmapNew.HeatmapType> types) throws FileNotFoundException {
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
	static HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapsFile, Collection<HeatmapNew.HeatmapType> types, boolean verbose) throws FileNotFoundException {
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
				log.debug(loggingOutput.toString());
			}
			return heatmapsRead;
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * V1.6.0 fix for file naming scheme. Checks the latest heatmap version in the directory, and if it's version 101 or
	 * earlier, renames the files after their dates modified.
	 * @param userId The user ID
	 * @param seasonalType The seasonal type
	 */
	protected static void fileNamingSchemeFix(long userId, String seasonalType, String username) {
		// Get latest .heatmaps file in the directory
		File latestHeatmap = getLatestHeatmapFile(userId, seasonalType, username);
		if (latestHeatmap == null) {
			return;
		}
		File directory = latestHeatmap.getParentFile();

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

		// Rename the files if necessary
		if (latestVersion <= 101) {
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
				}
				else {
					log.error("Could not rename file '{}' to '{}'", file.getName(), newFile.getName());
				}
			}
		}
	}
}
