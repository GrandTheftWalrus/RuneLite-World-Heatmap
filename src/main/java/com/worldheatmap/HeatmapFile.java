package com.worldheatmap;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
public class HeatmapFile {
    protected final static String HEATMAP_EXTENSION = ".heatmaps";
    protected final static File WORLD_HEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap");
    protected final static File HEATMAP_FILES_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Files").toFile();
    protected final static File HEATMAP_IMAGE_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Images").toFile();
    protected final static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm");
    
    /**
     * Return a new File in the correct directory and filename according to userId and time. Doesn't actually create the file, just a File object.
     * @return The new File
     */
    public static File getNewHeatmapFile(long userId, boolean isSeasonal) {
        String name = formatDate(new Date());
        File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId) + (isSeasonal ? "_seasonal" : ""));

        return new File(userIdDir, name + HEATMAP_EXTENSION);
    }

    public static File getNewImageFile(long userId, HeatmapNew.HeatmapType type, boolean isSeasonal) {
        String dateString = formatDate(new Date());
        File userIdDir = new File(HEATMAP_IMAGE_DIR, Long.toString(userId) + (isSeasonal ? "_seasonal" : ""));

        return new File(userIdDir, type + "_" + dateString + ".tif");
    }

    /**
     * Get the File that contains the latest heatmaps based on the filename being a date.
     * Returns null if no such file exists.
     * @return the youngest heatmaps file.
     */
    public static File getLatestHeatmapFile(long userId, boolean isSeasonalGameMode) {
        File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId) + (isSeasonalGameMode ? "_seasonal" : ""));

		// The following chunk is a fix for the realization that leagues (and all other world-based game modes)
		// were being permanently mixed with regular game mode heatmaps. This fix will designate all
		// leagues-contaminated heatmaps as being leagues heatmaps.
		Date endOfLeaguesV = new Date(2025, 01, 23);
		if (!userIdDir.exists() && isSeasonalGameMode) {
			File userIdDirNormalGameMode = new File(HEATMAP_FILES_DIR, Long.toString(userId));
			if (userIdDirNormalGameMode.exists() && new Date().before(endOfLeaguesV)) {
				// Steal the file from the normal game mode directory
				assert userIdDirNormalGameMode.renameTo(userIdDir);
				// Create a text file explaining the situation, for when the user eventually investigates
				String today = formatDate(new Date());
				File leaguesExplanationFile = new File(userIdDir, today + "_leagues_incident.txt");
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
							"designate the mixed .heatmaps files of anyone who logged into Leagues V between\n" +
							"the release of WorldHeatmap v1.6.1 and the end of Leagues V (Jan 22nd, 2025), as a\n" +
							"leagues heatmap (which is why you're seeing this). That way, the personal data that\n" +
							"players spent a long time collecting wouldn't be lost (just archived in a\n" +
							"'{userID}_seasonal' folder), whilst somewhat unfugging the global heatmap.\n" +
							"In the future (or perhaps in the past?) I'm  thinking that I'll add a feature to the\n" +
							"website where you can open a local .heatmaps file for visualization, so you can\n" +
							"more easily take a gander at your old data in this folder. If you have any questions\n" +
							"or concerns, please make an issue on the GitHub page for the plugin:\n" +
							"https://github.com/GrandTheftWalrus/RuneLite-World-Heatmap\n\n"+
							"P.S. You'll want to rename the folder to something else if a new seasonal game mode\n" +
							"comes out and you don't want its new data mixed with your old seasonal data. The world-based\n" +
							"game modes 'TOURNAMENT', 'SEASONAL', and 'BETA_WORLD' are lumped together in the '{userID}_seasonal' folder.\n"
					).getBytes());
				} catch (IOException e) {
					log.error("Failed to write leagues explanation file: {}", e);
				}
			}
		}
        File mostRecentFile = getMostRecentFile(userIdDir);

        // Check legacy location if latest file not found
        if (mostRecentFile == null) {

            File legacyHeatmapsFile = new File(HEATMAP_FILES_DIR, userId + HEATMAP_EXTENSION);
            if (!legacyHeatmapsFile.exists()) {
                return null;
            }

            // Move the old file to the new location
            File destination = getNewHeatmapFile(userId, isSeasonalGameMode);
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

    private static String formatDate(Date date) {
        return dateFormat.format(date);
    }

    /**
     * Returns the file in the given directory whose filename is the most recent parseable date string.
     * If no such file is found, returns null.
     * @param path the directory to search
     * @return the file with the most recent date in its filename, or null if no such file exists
     */
    private static File getMostRecentFile(File path) {

        File[] files = path.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            // Return null if the heatmaps directory is empty, doesn't exist, or isn't a directory
            return null;
        }

        // Remove files from the list `files` if they're not parseable by dateFormat
        // so that unrelated files in the directory don't cause an exception
        files = Arrays.stream(files).filter(f -> {
            String n = f.getName();
            int pos = n.lastIndexOf(".");
            n = n.substring(0,pos);
            try {
                dateFormat.parse(n);
                return true;
            } catch (ParseException e) {
                return false;
            }
        }).toArray(File[]::new);

        Arrays.sort(files, (f1, f2) -> {
            try {
                String n1 = f1.getName();
                String n2 = f2.getName();
                int pos1 = n1.lastIndexOf(".");
                int pos2 = n2.lastIndexOf(".");
                n1 = n1.substring(0,pos1);
                n2 = n2.substring(0,pos2);

                Date d1 = dateFormat.parse(n1);
                Date d2 = dateFormat.parse(n2);
                return d2.compareTo(d1);

            } catch (ParseException e) {
                return 0;
            }
        });

        return files[0];
    }
}
