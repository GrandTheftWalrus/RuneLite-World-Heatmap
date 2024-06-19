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
    protected final static SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMdd-HHmm");
    
    /**
     * Make a File in the correct directory and filename according to userId and time
     * @return The new File
     */
    public static File getCurrentHeatmapFile(long userId) {
        String name = formatDate(new Date());
        File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId));

        return new File(userIdDir, name + HEATMAP_EXTENSION);
    }

    public static File getCurrentImageFile(long userId, HeatmapNew.HeatmapType type) {
        String name = formatDate(new Date());
        System.out.println("Date is " + name);
        File userIdDir = new File(HEATMAP_IMAGE_DIR, Long.toString(userId));

        return new File(userIdDir, name + type + ".tif");
    }

    /**
     * Get the File that contains the latest heatmaps based on the filename being a date.
     * @return the youngest heatmaps file
     */
    public static File getLastHeatmap(long userId) {
        File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId));
        File mostRecent = getMostRecentFile(userIdDir);

        // Legacy location
        if (mostRecent == null) {
            log.info("Moving old heatmap location");

            mostRecent = new File(HEATMAP_FILES_DIR, userId + HEATMAP_EXTENSION);
            File destination = getCurrentHeatmapFile(userId);
            if (!destination.mkdirs()) {
                log.info("Couldn't make dirs to move the file. Aborting");
                return mostRecent;
            }
            try {
                Files.move(mostRecent.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.info("Moving file failed:");
                log.info(e.toString());
                return mostRecent;
            }

            mostRecent = destination;
        }
        return mostRecent;
    }

    private static String formatDate(Date date) {
        return dateFormat.format(date);
    }

    private static File getMostRecentFile(File path) {

        File[] files = path.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return null;
        }

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
