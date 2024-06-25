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
    protected final static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_hh-mm");
    
    /**
     * Return a new File in the correct directory and filename according to userId and time. Doesn't actually create the file, just a File object.
     * @return The new File
     */
    public static File getCurrentHeatmapFile(long userId) {
        String name = formatDate(new Date());
        File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId));

        return new File(userIdDir, name + HEATMAP_EXTENSION);
    }

    public static File getCurrentImageFile(long userId, HeatmapNew.HeatmapType type) {
        String name = formatDate(new Date());
        File userIdDir = new File(HEATMAP_IMAGE_DIR, Long.toString(userId));

        return new File(userIdDir, name + "_" + type + ".tif");
    }

    /**
     * Get the File that contains the latest heatmaps based on the filename being a date.
     * Returns null if no such file exists.
     * @return the youngest heatmaps file.
     */
    public static File getLatestHeatmapFile(long userId) {
        File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId));
        File mostRecent = getMostRecentFile(userIdDir);

        // Check legacy location if latest file not found
        if (mostRecent == null) {

            File legacyHeatmapsFile = new File(HEATMAP_FILES_DIR, userId + HEATMAP_EXTENSION);
            if (!legacyHeatmapsFile.exists()) {
                return null;
            }

            // Move the old file to the new location
            File destination = getCurrentHeatmapFile(userId);
            if (!destination.mkdirs()) {
                log.info("Couldn't make dirs to move heatmaps file from legacy (V2) location. Aborting move operation, but returning the file.");
                return legacyHeatmapsFile;
            }
            try {
                log.info("Moving heatmaps file from legacy (V2) location {} to new location {}", legacyHeatmapsFile, destination);
                Files.move(legacyHeatmapsFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.info("Moving heatmaps file from legacy (V2) location failed:");
                log.info(e.toString());
                return legacyHeatmapsFile;
            }

            mostRecent = destination;
        }
        return mostRecent;
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
