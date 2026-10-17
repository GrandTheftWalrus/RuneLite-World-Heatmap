package com.worldheatmap;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.Filepath;

@Slf4j
public class HeatmapFileManager
{
    protected final static String HEATMAP_EXTENSION = ".heatmaps";
    protected final Filepath HEATMAP_FILES_DIR;
    protected final Filepath HEATMAP_IMAGE_DIR;
	protected final static DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
	private final WorldHeatmapPlugin plugin;
	private final WorldHeatmapConfig config;

	public HeatmapFileManager(WorldHeatmapPlugin plugin, Filepath pluginDirectory){
		this.plugin = plugin;
		this.config = plugin.config;
		this.HEATMAP_FILES_DIR = pluginDirectory.join("Heatmap Files");
		this.HEATMAP_IMAGE_DIR = pluginDirectory.join("Heatmap Images");
	}

	/**
	 * Return a Filepath in the correct directory, either named after the current time or the latest file in the directory,
	 * whichever comes last. If they are equal, returns a new file named mustReturnNewFile minutes later than the
	 * latest file. If mustReturnNewFile is zero, then it returns the latest file.
	 * Doesn't actually create the file, just a Filepath object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	private Filepath getFile(long userId, String seasonalType, boolean mustReturnNewFile) {
		boolean isSeasonal = !seasonalType.isBlank();
		Filepath userIdDir = HEATMAP_FILES_DIR.join(userId + (isSeasonal ? "_" + seasonalType : ""));
		// Find the most recent file
		Filepath latestFile = getLatestFile(userId, seasonalType);

		// Parse its date
		LocalDateTime dateOfLatestFile = null;
		if (latestFile != null && latestFile.exists()) {
			String name = latestFile.getFileName();
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

		return userIdDir.join(fileName);
	}

	/**
	 * Returns a new heatmaps Filepath named after the current time, or one minute past the latest file, whichever comes last.
	 * Doesn't actually create the file, just a Filepath object.
	 * @param userId The user ID
	 * @param seasonalType The seasonal type, or empty string if not seasonal
	 * @return The new heatmaps Filepath
	 */
    public Filepath getNewFile(long userId, String seasonalType) {
		return getFile(userId, seasonalType, true);
    }

	/**
	 * Returns a new heatmaps Filepath named after the current time, or the latest heatmaps Filepath if it's dated into the future
	 * This is generally for updating the name of modified files.
	 * Doesn't actually create the file, just a Filepath object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	public Filepath getCurrentFile(long userId, String seasonalType) {
		return getFile(userId, seasonalType, false);
	}

	/**
	 * Returns a Filepath named after the current date and time, even if it already exists.
	 * @param userId The user ID
	 * @param type The heatmap type
	 * @param seasonalType The seasonal type, or null if not seasonal
	 * @return
	 */
    public Filepath getNewImageFile(long userId, Heatmap.HeatmapType type, String seasonalType) {
		boolean isSeasonal = !seasonalType.isBlank();
        String dateString = formatDate(LocalDateTime.now());
        Filepath userIdDir = HEATMAP_IMAGE_DIR.join(Long.toString(userId) + (isSeasonal ? "_" + seasonalType : ""));

        return userIdDir.join(type + "_" + dateString + ".tif");
    }

    /**
     * Returns the .heatmaps file in the given directory whose filename is the most recent parseable date string.
     * Returns null if no such file exists.
	 * @param accountHash the user ID/account hash
	 * @param seasonalType the seasonal type, or null if not seasonal
     * @return the file with the most recent date in its filename, or null if no such file exists
     */
    public Filepath getLatestFile(long accountHash, String seasonalType) {
		boolean isSeasonal = !seasonalType.isBlank();
		// currentDir may be normal or seasonal
        Filepath currentDir = HEATMAP_FILES_DIR.join(accountHash + (isSeasonal ? "_" + seasonalType : ""));
		return getLatestFile(currentDir);
	}

	/**
	 * Returns the .heatmaps file in the given directory whose filename is the most recent parseable date string.
	 * @param path the directory to search
	 * @return the file with the most recent date in its filename, or null if no such file exists
	 */
	private Filepath getLatestFile(Filepath path) {
		// If the latest file is found, return it
		List<Filepath> files = getSortedFiles(path);
		if (files.isEmpty()) {
			return null;
		}
		return files.get(0);
	}

	/**
	 * Runs fixes on the user's heatmaps files, if necessary, for backwards compatability.
	 * @param accountHash the user ID/account hash
	 * @param username the player's username
	 */
	protected void fixHeatmapsFiles(long accountHash, String username) {
		Filepath normalDir = HEATMAP_FILES_DIR.join(Long.toString(accountHash));

		// Modernize any legacy heatmap files, stashing them in the regular directory
		// (since they won't have seasonal type encoded)
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
		Filepath latestFile = getLatestFile(accountHash, seasonalType);
		if (latestFile == null) {
			return;
		}
		Filepath newFile = getCurrentFile(accountHash, seasonalType);

		try {
			log.info("Performing death-teleport fix for account {} (seasonal type: \"{}\") on file {}", accountHash, seasonalType, latestFile.getFileName());
			// Read all heatmap types (because we want to rewrite even the ones not needing to be modified, to update their version)
			HashMap<Heatmap.HeatmapType, Heatmap> heatmaps = readHeatmapsFromFile(latestFile, List.of(Heatmap.HeatmapType.values()), false);
			Heatmap deaths = heatmaps.get(Heatmap.HeatmapType.DEATHS);

			Heatmap teleportedFrom = heatmaps.get(Heatmap.HeatmapType.TELEPORTED_FROM);

			if (teleportedFrom != null && deaths != null && deaths.getTileCount() > 0) {
				teleportedFrom.subtract(deaths, 0);
			}

			writeHeatmapsToFile(heatmaps.values(), newFile, false);

			// Write new, fixed TELEPORTED_FROM image
			Filepath teleportedFromImageFile = getNewImageFile(accountHash, Heatmap.HeatmapType.TELEPORTED_FROM, seasonalType);
			plugin.executor.execute(() -> HeatmapImage.writeHeatmapImage(heatmaps.get(Heatmap.HeatmapType.TELEPORTED_FROM), teleportedFromImageFile, config.isWriteFullImageEnabled(), config.isBlueMapEnabled(), config.heatmapAlpha(), config.heatmapSensitivity(), config.speedMemoryTradeoff(), new WorldHeatmapPlugin.HeatmapProgressListener(plugin, Heatmap.HeatmapType.TELEPORTED_FROM)));

			// If enabled, trigger heatmap data upload to server.
			// Theoretically this could fix most of the public data.
			if (config.isUploadEnabled()) {
				heatmaps.remove(Heatmap.HeatmapType.UNKNOWN); // I forget where this even comes from

				plugin.executor.execute(() -> {
					boolean uploadSuccessful = Utils.uploadHeatmaps(heatmaps, plugin.okHttpClient);
					if (uploadSuccessful) {
						log.info("Successfully uploaded heatmaps after applying deaths as teleports fix for account {} (seasonal type: \"{}\")", accountHash, seasonalType);
					}
					else {
						log.error("Failed to upload heatmaps after applying deaths as teleports fix for account {} (seasonal type: \"{}\")", accountHash, seasonalType);
					}
				});
			}

		} catch (Exception e) {
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
	private void carryOverV1_2Files(long accountHash, Filepath newDir) {
		Filepath oldFile = HEATMAP_FILES_DIR.join(accountHash + HEATMAP_EXTENSION);
		if (oldFile.exists()) {
			// Move the old file to the new location, naming it after its date modified
			try {
				long epochMillis = oldFile.getLastModifiedTime().toMillis();
				ZonedDateTime lastModified = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
				String newName = dateFormat.format(lastModified);
				Filepath newFile = newDir.join(newName + HEATMAP_EXTENSION);

				// Move the file by rewriting it, to update its version
				log.info("Moving heatmaps file from legacy (V1.2) location {} to new location {}", oldFile, newFile);
				HashMap<Heatmap.HeatmapType, Heatmap> heatmaps = readHeatmapsFromFile(oldFile, List.of(Heatmap.HeatmapType.values()), false);
				// Update the account hash cuz we have it available
				heatmaps.values().forEach(h -> {
					h.setUserID(accountHash);
					h.setSeasonalType("");
				});
				writeHeatmapsToFile(heatmaps.values(), newFile, false);

				// Delete the old file
				oldFile.delete();

				// If the moved file is the latest one in the folder,
				// make a second copy of the moved file, named one minute later,
				// so we can keep the other one as an updated backup
				if (newFile.equals(getLatestFile(newDir))) {
					Filepath newFile2 = newDir.join(dateFormat.format(lastModified.plusMinutes(1)) + HEATMAP_EXTENSION);
					newFile.copyTo(newFile2);
				}
			} catch (IOException e) {
				log.error("Moving heatmaps file from legacy (V1.2) location failed");
			}

		}
	}

	private static String formatDate(LocalDateTime dateTime) {
		return dateTime.format(dateFormat);
	}

    /**
	 * Returns a list of files in the given directory whose filenames are parseable date strings, sorted by date.
	 * @param path the directory to search
	 * @return a list of files with parseable dates in their filenames, sorted by date (most recent first)
     */
	private List<Filepath> getSortedFiles(Filepath path) {
		if (!path.exists() || !path.isDirectory()) {
			return new ArrayList<>();
		}

		List<Filepath> files;
		try (Stream<Filepath> stream = path.walk(1)) {
			files = stream
				.filter(Filepath::isFile)
				.filter(file -> {
					String name = file.getFileName();
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
				.collect(Collectors.toList());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		files.sort((f1, f2) -> {
			try {
				String n1 = f1.getFileName();
				String n2 = f2.getFileName();
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

	protected void writeHeatmapsToFile(Collection<Heatmap> heatmapsToWrite, Filepath heatmapsFile, @Nullable Filepath previousHeatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, previousHeatmapsFile, true);
	}

	protected void writeHeatmapsToFile(Collection<Heatmap> heatmapsToWrite, Filepath heatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, null, true);
	}

	/**
	 * Writes the provided heatmap data to the specified .heatmaps file. If the file already exists, it will be updated,
	 * and unprovided heatmaps already in the file will remain.
	 * @param heatmapsToWrite The heatmaps to write
	 * @param heatmapsFile The .heatmaps file
	 */
	protected void writeHeatmapsToFile(Collection<Heatmap> heatmapsToWrite, Filepath heatmapsFile, boolean verbose) {
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
	protected void writeHeatmapsToFile(Collection<Heatmap> heatmapsToWrite, Filepath heatmapsFile, @Nullable Filepath previousHeatmapsFile, boolean verbose) {
		// Preamble
		if (verbose) {
			log.info("Saving heatmaps to file '{}'...", heatmapsFile.getFileName());
		}

		long startTime = System.nanoTime();
		StringBuilder loggingOutput = new StringBuilder("Heatmap types saved: ");

		try {
			// Make the directory path if it doesn't exist
			heatmapsFile.getParent().createDirectories();

			// The file to carry over unprovided heatmap entries from: previousHeatmapsFile takes
			// precedence (carrying over its entries into heatmapsFile) if given and it exists,
			// otherwise fall back to heatmapsFile's own existing entries (if any)
			Filepath sourceFile = null;
			if (previousHeatmapsFile != null && previousHeatmapsFile.exists()) {
				sourceFile = previousHeatmapsFile;
			}
			else if (heatmapsFile.exists()) {
				sourceFile = heatmapsFile;
			}

			Set<String> writtenEntryNames = new HashSet<>();
			Filepath tempFile = heatmapsFile.getParent().createTempFile("heatmaps", ".tmp");
			try (OutputStream os = tempFile.openOutputStream();
				 ZipOutputStream zos = new ZipOutputStream(os)) {
				for (Heatmap heatmap : heatmapsToWrite) {
					String entryName = heatmap.getHeatmapType().toString() + "_HEATMAP.csv";
					zos.putNextEntry(new ZipEntry(entryName));
					OutputStreamWriter osw = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
					heatmap.toCSV(osw);
					osw.flush();
					zos.closeEntry();
					writtenEntryNames.add(entryName);
					loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getTileCount() + " tiles), ");
				}

				// Carry over any entries from the source file that weren't just (re)written
				if (sourceFile != null) {
					try (InputStream is = sourceFile.openInputStream();
						 ZipInputStream zis = new ZipInputStream(is)) {
						ZipEntry entry;
						while ((entry = zis.getNextEntry()) != null) {
							if (!writtenEntryNames.contains(entry.getName())) {
								zos.putNextEntry(new ZipEntry(entry.getName()));
								zis.transferTo(zos);
								zos.closeEntry();
							}
							zis.closeEntry();
						}
					}
				}
			}

			tempFile.moveTo(heatmapsFile, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			log.error("World Heatmap was not able to save heatmaps to file '{}': {}", heatmapsFile.getFileName(), e.toString());
			return;
		}

		if (verbose) {
			log.info(loggingOutput.toString());
			log.info("Finished writing '{}' heatmap file to disk after {} ms", heatmapsFile.getFileName(), (System.nanoTime() - startTime) / 1_000_000);
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
	HashMap<Heatmap.HeatmapType, Heatmap> readHeatmapsFromFile(Filepath heatmapsFile, Collection<Heatmap.HeatmapType> types) throws FileNotFoundException {
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
	HashMap<Heatmap.HeatmapType, Heatmap> readHeatmapsFromFile(Filepath heatmapsFile, Collection<Heatmap.HeatmapType> types, boolean verbose) throws FileNotFoundException {
		if (!heatmapsFile.exists()) {
			throw new FileNotFoundException(heatmapsFile.toString());
		}

		Set<String> wantedEntryNames = new HashSet<>();
		for (Heatmap.HeatmapType type : types) {
			wantedEntryNames.add(type.toString() + "_HEATMAP.csv");
		}

		HashMap<Heatmap.HeatmapType, Heatmap> heatmapsRead = new HashMap<>();
		StringBuilder loggingOutput = new StringBuilder();
		loggingOutput.append("Heatmap types loaded: ");

		try (InputStream is = heatmapsFile.openInputStream();
			 ZipInputStream zis = new ZipInputStream(is)) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (!wantedEntryNames.contains(entry.getName())) {
					zis.closeEntry();
					continue;
				}
				try {
					BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
					Heatmap heatmap = Heatmap.fromCSV(reader);
					heatmapsRead.put(heatmap.getHeatmapType(), heatmap);
					loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getTileCount() + " tiles), ");
				} catch (IOException e) {
					log.error("Error reading heatmap from .heatmaps entry '{}'", entry.getName());
				}
				zis.closeEntry();
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		if (verbose) {
			log.info(loggingOutput.toString());
		}
		return heatmapsRead;
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
	protected void fileNamingSchemeFix(Filepath directory) {
		// Rename the files
		log.info("Performing naming scheme fix on .heatmaps files in directory '{}'...", directory.getFileName());
		List<Filepath> heatmapFiles = new ArrayList<>();
		if (directory.exists() && directory.isDirectory()) {
			try (Stream<Filepath> stream = directory.walk(1)) {
				stream.filter(Filepath::isFile)
					.filter(file -> file.getFileName().endsWith(".heatmaps"))
					.forEach(heatmapFiles::add);
			} catch (IOException e) {
				log.error("Could not list .heatmaps files in directory '{}': {}", directory.getFileName(), e.toString());
				return;
			}
		}

		// Sort the files by parseable date descending
		heatmapFiles.sort((f1, f2) -> {
			String name1 = f1.getFileName().split("\\.")[0];
			String name2 = f2.getFileName().split("\\.")[0];
			LocalDateTime f1Date = LocalDateTime.parse(name1, dateFormat);
			LocalDateTime f2Date = LocalDateTime.parse(name2, dateFormat);
			return f2Date.compareTo(f1Date);
		});

		// Cycle through them, renaming them after their date modified
		for (Filepath file : heatmapFiles) {
			try {
				long epochMillis = file.getLastModifiedTime().toMillis();

				// Convert to ZonedDateTime in the local time zone
				ZonedDateTime lastModified = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());

				// Format the new name
				String newName = dateFormat.format(lastModified);
				Filepath newFile = directory.join(newName + ".heatmaps");

				// Rename the file
				file.moveTo(newFile);
				log.info("Renamed {} to {}", file.getFileName(), newFile.getFileName());
			}
			catch (IOException e) {
				log.error("Could not rename file '{}'", file.getFileName());
			}
		}
	}

	private Long getLatestHeatmapVersion(Filepath directory)
	{
		// Get latest .heatmaps file in the directory
		Filepath latestHeatmap = getLatestFile(directory);
		if (latestHeatmap == null) {
			return null;
		}

		// Find the latest heatmap version of the latest .heatmaps file
		long latestVersion = -1;
		try {
			Map<Heatmap.HeatmapType, Heatmap> heatmaps = readHeatmapsFromFile(latestHeatmap, List.of(Heatmap.HeatmapType.values()), false);
			for (Heatmap heatmap : heatmaps.values()) {
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
