package com.worldheatmap;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
	protected final static LocalDate startOfLeaguesV = LocalDate.of(2024, 11, 26);
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
	public static File getHeatmapFile(long userId, @Nullable String seasonalType, int onConflictOffset) {
		boolean isSeasonal = seasonalType != null;
		File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId) + (isSeasonal ? "_" + seasonalType : ""));
		// Find the next available filename
		File latestFile = getLatestHeatmapFile(userId, seasonalType);
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
    public static File getNewHeatmapFile(long userId, @Nullable String seasonalType) {
		return getHeatmapFile(userId, seasonalType, 1);
    }

	/**
	 * Returns a new heatmaps File named after the current time, or the latest file if it already exists.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	public static File getCurrentHeatmapFile(long userId, @Nullable String seasonalType) {
		return getHeatmapFile(userId, seasonalType, 0);
	}

	/**
	 * Returns a File named after the current date and time, even if it already exists.
	 * @param userId The user ID
	 * @param type The heatmap type
	 * @param seasonalType The seasonal type, or null if not seasonal
	 * @return
	 */
    public static File getNewImageFile(long userId, HeatmapNew.HeatmapType type, @Nullable String seasonalType) {
		boolean isSeasonal = seasonalType != null;
        String dateString = formatDate(LocalDateTime.now());
        File userIdDir = new File(HEATMAP_IMAGE_DIR, Long.toString(userId) + (isSeasonal ? "_" + seasonalType : ""));

        return new File(userIdDir, type + "_" + dateString + ".tif");
    }

    /**
     * Get the File that contains the latest heatmaps based on the filename being a date.
     * Returns null if no such file exists.
	 * @param accountHash the user ID/account hash
	 * @param seasonalType the seasonal type, or null if not seasonal
     * @return the youngest heatmaps file.
     */
    public static File getLatestHeatmapFile(long accountHash, @Nullable String seasonalType) {
		boolean isSeasonal = seasonalType != null;
        File heatmapsDir = new File(HEATMAP_FILES_DIR, Long.toString(accountHash) + (isSeasonal ? "_" + seasonalType : ""));

		// Carry out the leagues decontamination process if necessary
		if (!heatmapsDir.exists() && isSeasonal) {
			File normalHeatmapsDir = new File(HEATMAP_FILES_DIR, Long.toString(accountHash));
			if (normalHeatmapsDir.exists() && LocalDate.now().isBefore(endOfLeaguesV)) {
				handleLeaguesDecontamination(normalHeatmapsDir, heatmapsDir);
			}
		}
        File mostRecentFile = getMostRecentFile(heatmapsDir);

        // Check legacy location if latest file not found
        if (mostRecentFile == null) {

            File legacyHeatmapsFile = new File(HEATMAP_FILES_DIR, accountHash + HEATMAP_EXTENSION);
            if (!legacyHeatmapsFile.exists()) {
                return null;
            }

            // Move the old file to the new location
            File destination = getNewHeatmapFile(accountHash, seasonalType);
            if (!destination.mkdirs()) {
                log.error("Couldn't make dirs to move heatmaps file from legacy (V2) location. Aborting move operation, but returning the file.");
                return legacyHeatmapsFile;
            }
            try {
                log.info("Moving heatmaps file from legacy (V2) location {} to new location {}", legacyHeatmapsFile, destination);
                Files.move(legacyHeatmapsFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("Moving heatmaps file from legacy (V2) location failed:");
                log.debug(e.toString());
                return legacyHeatmapsFile;
            }

            mostRecentFile = destination;
        }
        return mostRecentFile;
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
		log.debug("Handling leagues decontamination");
		// Create seasonal directory
		if (!seasonalHeatmapsDir.mkdirs()) {
			log.error("Failed to create seasonal heatmaps directory");
			return;
		}

		// Move potentially contaminated files from the normal heatmap directory to the seasonal directory
		File[] files = getSortedFiles(regularHeatmapsDir);
		if (files != null) {
			for (File file : files) {
				String name = file.getName();
				int pos = name.lastIndexOf(".");
				name = name.substring(0, pos);
				LocalDate fileDate = LocalDate.parse(name, dateFormat);
				if (fileDate.isAfter(startOfLeaguesV)) {
					log.debug("File {} is contaminated by Leagues V, moving to seasonal directory", file.getName());
					// Move it to the seasonal directory
					File destination = new File(seasonalHeatmapsDir, file.getName());
					try {
						Files.move(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException e)
					{
						log.error("Failed to move file to seasonal directory: {}", e.toString());
					}
				}
				else{
					log.debug("File {} is before {}, leaving in normal directory", file.getName(), startOfLeaguesV);
				}
			}
		}

		// Create a text file explaining the situation, for when the user eventually investigates
		String today = formatDate(LocalDateTime.now());
		File leaguesExplanationFile = new File(seasonalHeatmapsDir, today + "_leagues_incident.txt");
		try {
			Files.write(leaguesExplanationFile.toPath(), (
					"This directory contains regular account heatmaps that were contaminated by Leagues V data.\n" +
					"Version 1.6.0 of the plugin didn't keep separate heatmaps for Leagues/seasonal\n" +
					"accounts and regular accounts because I didn't realize that each player's\n" +
					"Leagues/seasonal account would have the same Account ID and Account Type code\n" +
					"(regular/ironman/group ironman etc.) as their regular account. So, data being\n" +
					"uploaded to the website https://osrsworldheatmap.com was looking kinda sus\n" +
					"such as how TELEPORTED_TO had a bunch of entries that would normally be impossibru\n" +
					"for regular accounts. The only way to semi-fix it, that I could think of, was to\n" +
					"designate the potentially contaminated .heatmaps files of anyone who logged into Leagues V\n" +
					"between the release of WorldHeatmap v1.6.1 and the end of Leagues V (Jan 22nd, 2025), as a\n" +
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
	private static File getMostRecentFile(File path) {
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
}
