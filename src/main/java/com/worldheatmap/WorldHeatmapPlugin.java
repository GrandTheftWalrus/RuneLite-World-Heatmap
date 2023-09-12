package com.worldheatmap;

import com.google.inject.Provides;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.ClientToolbar;
import org.eclipse.collections.api.tuple.primitive.IntIntPair;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
@PluginDescriptor(
        name = "World Heatmap"
)
public class WorldHeatmapPlugin extends Plugin {
    private static final int
            OVERWORLD_MAX_X = 3904,
            OVERWORLD_MAX_Y = Constants.OVERWORLD_MAX_Y,
            TYPE_A_HEATMAP_AUTOSAVE_FREQUENCY = 100,
            TYPE_B_HEATMAP_AUTOSAVE_FREQUENCY = 500,
            HEATMAP_OFFSET_X = -1152, // never change these
            HEATMAP_OFFSET_Y = -2496, // never change these
            MIN_X = 1152,
            MAX_X = 3903,
            MIN_Y = 2496,
            MAX_Y = 4159;
    private int
            lastX = 0,
            lastY = 0,
            lastStepCountA = 0,
            lastStepCountB = 0;
    protected final File
            WORLDHEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap"),
            HEATMAP_FILES_DIR = Paths.get(WORLDHEATMAP_DIR.toString(), "Heatmap Files").toFile(),
            HEATMAP_IMAGE_DIR = Paths.get(WORLDHEATMAP_DIR.toString(), "Heatmap Images").toFile();
    private final int HEATMAP_WIDTH = 2752, HEATMAP_HEIGHT = 1664;
    protected HeatmapNew heatmapTypeA, heatmapTypeB;
    private NavigationButton toolbarButton;
    private WorldHeatmapPanel panel;
    private boolean shouldLoadHeatmaps;
    protected String mostRecentLocalUserName;
    protected long mostRecentLocalUserID;

    @Inject
    private Client client;

    private Future loadHeatmapsFuture;

    @Inject
    protected ScheduledExecutorService executor;

    @Inject
    private WorldHeatmapConfig config;

    @Inject
    private ClientToolbar clientToolbar;

    @Provides
    WorldHeatmapConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WorldHeatmapConfig.class);
    }

    protected void loadHeatmapFiles() {
        log.debug("Loading heatmaps under user ID " + mostRecentLocalUserID + "...");
        String filepathTypeAUsername = Paths.get(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName) + "_TypeA.heatmap";
        String filepathTypeAUserID = Paths.get(HEATMAP_FILES_DIR.toString(), "" + mostRecentLocalUserID) + "_TypeA.heatmap";
        String filepathTypeBUsername = Paths.get(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName) + "_TypeB.heatmap";
        String filepathTypeBUserID = Paths.get(HEATMAP_FILES_DIR.toString(), "" + mostRecentLocalUserID) + "_TypeB.heatmap";

        // Load heatmap Type A
        // To fix/deal with how previous versions of the plugin used player names
        // (which can change) instead of player IDs, we also do the following check
        if (new File(filepathTypeAUserID).exists()) {
            heatmapTypeA = readHeatmapFile(filepathTypeAUserID);
        } else if (new File(filepathTypeAUsername).exists()) {
            log.info("File '" + filepathTypeAUserID + "' did not exist. Checking for alternative file '" + filepathTypeAUsername + "'...");
            heatmapTypeA = readHeatmapFile(filepathTypeAUsername);
        } else {
            log.info("File '" + filepathTypeAUserID + "' did not exist. Creating a new heatmap...");
            heatmapTypeA = new HeatmapNew(mostRecentLocalUserID);
        }

        // Load heatmap type B
        if (new File(filepathTypeBUserID).exists()) {
            heatmapTypeB = readHeatmapFile(filepathTypeBUserID);
        } else if (new File(filepathTypeBUsername).exists()) {
            log.info("File '" + filepathTypeBUserID + "' did not exist. Checking for alternative file '" + filepathTypeBUsername + "'...");
            heatmapTypeB = readHeatmapFile(filepathTypeBUsername);
        } else {
            log.info("File '" + filepathTypeBUserID + "' did not exist. Creating a new heatmap...");
            heatmapTypeB = new HeatmapNew(mostRecentLocalUserID);
        }
        panel.writeTypeAHeatmapImageButton.setEnabled(true);
        panel.writeTypeBHeatmapImageButton.setEnabled(true);
        panel.clearTypeAHeatmapButton.setEnabled(true);
        panel.clearTypeBHeatmapButton.setEnabled(true);
        panel.writeTypeAcsvButton.setEnabled(true);
        panel.writeTypeBcsvButton.setEnabled(true);
    }

    @Override
    protected void startUp() {
        shouldLoadHeatmaps = true;
        loadHeatmapsFuture = null;
        panel = new WorldHeatmapPanel(this);
        panel.rebuild();
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/WorldHeatmap.png");
        toolbarButton = NavigationButton.builder()
                .tooltip("World Heatmap")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(toolbarButton);
        panel.writeTypeAHeatmapImageButton.setEnabled(false);
        panel.writeTypeBHeatmapImageButton.setEnabled(false);
        panel.clearTypeAHeatmapButton.setEnabled(false);
        panel.clearTypeBHeatmapButton.setEnabled(false);
        panel.writeTypeAcsvButton.setEnabled(false);
        panel.writeTypeBcsvButton.setEnabled(false);
    }

    @Override
    protected void shutDown() {
        if (loadHeatmapsFuture != null && loadHeatmapsFuture.isDone()) {
            String filepathA = Paths.get(mostRecentLocalUserID + "_TypeA.heatmap").toString();
            executor.execute(() -> writeHeatmapFile(heatmapTypeA, new File(filepathA)));
            String filepathB = Paths.get(mostRecentLocalUserID + "_TypeB.heatmap").toString();
            executor.execute(() -> writeHeatmapFile(heatmapTypeB, new File(filepathB)));
        }
        clientToolbar.removeNavigation(toolbarButton);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGGING_IN) {
            shouldLoadHeatmaps = true;
            loadHeatmapsFuture = null;
        }

        if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN && loadHeatmapsFuture != null && loadHeatmapsFuture.isDone()) {
            String filepathA = Paths.get(mostRecentLocalUserID + "_TypeA.heatmap").toString();
            executor.execute(() -> writeHeatmapFile(heatmapTypeA, new File(filepathA)));
            String filepathB = Paths.get(mostRecentLocalUserID + "_TypeB.heatmap").toString();
            executor.execute(() -> writeHeatmapFile(heatmapTypeB, new File(filepathB)));
            loadHeatmapsFuture = null;
        }
        if (gameStateChanged.getGameState() != GameState.LOGGED_IN) {
            panel.writeTypeAHeatmapImageButton.setEnabled(false);
            panel.writeTypeBHeatmapImageButton.setEnabled(false);
            panel.clearTypeAHeatmapButton.setEnabled(false);
            panel.clearTypeBHeatmapButton.setEnabled(false);
            panel.writeTypeAcsvButton.setEnabled(false);
            panel.writeTypeBcsvButton.setEnabled(false);
        }
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
            panel.writeTypeAHeatmapImageButton.setEnabled(true);
            panel.writeTypeBHeatmapImageButton.setEnabled(true);
            panel.clearTypeAHeatmapButton.setEnabled(true);
            panel.clearTypeBHeatmapButton.setEnabled(true);
            panel.writeTypeAcsvButton.setEnabled(true);
            panel.writeTypeBcsvButton.setEnabled(true);
        }
    }

    @Subscribe
    public void onGameTick(GameTick gameTick) {
        if (client.getAccountHash() == -1) {
            return;
        }
        if (mostRecentLocalUserID != client.getAccountHash()) {
            mostRecentLocalUserName = client.getLocalPlayer().getName();
            mostRecentLocalUserID = client.getAccountHash();
        }
        if (panel.mostRecentLocalUserID != mostRecentLocalUserID) {
            SwingUtilities.invokeLater(panel::updatePlayerID);
        }

        if (shouldLoadHeatmaps && client.getGameState().equals(GameState.LOGGED_IN)) {
            shouldLoadHeatmaps = false;
            loadHeatmapsFuture = executor.submit(this::loadHeatmapFiles);
        }
        // The following code requires the heatmap files to have been loaded
        if (loadHeatmapsFuture != null && !loadHeatmapsFuture.isDone()) {
            return;
        }

        WorldPoint currentCoords = client.getLocalPlayer().getWorldLocation();
        int currentX = currentCoords.getX();
        int currentY = currentCoords.getY();

        // The following code is for the 'Type A' heatmap
        if (currentX < OVERWORLD_MAX_X && currentY < OVERWORLD_MAX_Y && (currentX != lastX || currentY != lastY)) { // If the player is in the overworld and has moved since last game tick
            /* When running, players cover more than one tile per tick, which creates spotty paths.
             * We fix this by drawing a line between the current coordinates and the previous coordinates,
             * but we have to be sure that the player indeed ran from point A to point B, differentiating the movement from teleportation.
             * Since it's too hard to check if the player is actually running, we'll just check if the distance covered since last tick
             * was less than 5 tiles. Teleporting to the same place repeatedly will be considered local movement.
             */
            int diagDistance = diagonalDistance(new Point(lastX, lastY), new Point(currentX, currentY));
            boolean largeStep = diagDistance >= 5;
            if (!largeStep) {
                // Increments all the tiles between new position and last position
                for (Point tile : getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY))) {
                    if (isInBounds(tile.x, tile.y)) {
                        heatmapTypeA.increment((short) tile.x, (short) tile.y);

                        // If it's time to autosave the image, then save heatmap file and write image file
                        if (config.typeAImageAutosaveOnOff() && heatmapTypeA.getStepCount() % config.typeAImageAutosaveFrequency() == 0) {
                            String filepath = Paths.get(mostRecentLocalUserID + "_TypeA.heatmap").toString();
                            executor.execute(() -> writeHeatmapFile(heatmapTypeA, new File(filepath)));
                            executor.execute(() -> writeHeatmapImage(heatmapTypeA, new File(mostRecentLocalUserID + "_TypeA.png")));
                        }

                        // if it wasn't the time to autosave an image (and therefore save the .heatmap), then check if it's time to autosave just the .heatmap file
                        else if (heatmapTypeA.getStepCount() % TYPE_A_HEATMAP_AUTOSAVE_FREQUENCY == 0) {
                            String filepath = Paths.get(mostRecentLocalUserID + "_TypeA.heatmap").toString();
                            executor.execute(() -> writeHeatmapFile(heatmapTypeA, new File(filepath)));
                        }

                        if (heatmapTypeA.getStepCount() % config.typeAHeatmapBackupFrequency() == 0) {
                            String filepath = Paths.get("Backups", mostRecentLocalUserID + "-" + java.time.LocalDateTime.now() + "_TypeA.heatmap").toString();
                            executor.execute(() -> writeHeatmapFile(heatmapTypeA, new File(filepath)));
                        }
                    } else {
                        log.error("World Heatmap: Coordinates out of bounds for heatmap: (" + tile.x + ", " + tile.y + ")");
                    }
                }
            }
        }

        // The following code is for the 'Type B' heatmap
        if (currentX < OVERWORLD_MAX_X && currentY < OVERWORLD_MAX_Y) { // If the player is in the overworld
            /* When running, players cover more than one tile per tick, which creates spotty paths.
             * We fix this by drawing a line between the current coordinates and the previous coordinates,
             * but we have to be sure that the player indeed ran from point A to point B, differentiating the movement from teleportation.
             * Since it's too hard to check if the player is actually running, we'll just check if the distance covered since last tick
             * was less than 5 tiles, and the player hasn't cast a teleport in the last few ticks.
             */
            int diagDistance = diagonalDistance(new Point(lastX, lastY), new Point(currentX, currentY));
            boolean largeStep = diagDistance >= 5;
            if (!largeStep) {
                // Increments all the tiles between new position and last position
                for (Point tile : getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY))) {
                    if (isInBounds(tile.x, tile.y)) {
                        heatmapTypeB.increment((short) tile.x, (short) tile.y);

                        // If it's time to autosave the image, then save heatmap file and write image file
                        if (config.typeBImageAutosaveOnOff() && heatmapTypeB.getStepCount() % config.typeBImageAutosaveFrequency() == 0) {
                            String filepath = Paths.get(mostRecentLocalUserID + "_TypeB.heatmap").toString();
                            executor.execute(() -> writeHeatmapFile(heatmapTypeB, new File(filepath)));
                            executor.execute(() -> writeHeatmapImage(heatmapTypeB, new File(mostRecentLocalUserID + "_TypeB.png")));
                        }

                        // if it wasn't the time to autosave an image (and therefore save), then check if it's time to autosave just the file
                        else if (heatmapTypeB.getStepCount() % TYPE_B_HEATMAP_AUTOSAVE_FREQUENCY == 0) {
                            String filepath = Paths.get(mostRecentLocalUserID + "_TypeB.heatmap").toString();
                            executor.execute(() -> writeHeatmapFile(heatmapTypeB, new File(filepath)));
                        }

                        // Automatically make heatmap file backup with current date
                        if (heatmapTypeB.getStepCount() % config.typeBHeatmapBackupFrequency() == 0) {
                            String filepath = Paths.get("Backups", mostRecentLocalUserID + "-" + java.time.LocalDateTime.now() + "_TypeA.heatmap").toString();
                            executor.execute(() -> writeHeatmapFile(heatmapTypeB, new File(filepath)));
                        }
                    } else {
                        log.error("World Heatmap: Coordinates out of bounds for heatmap matrix: (" + tile.x + ", " + tile.y + ")");
                    }
                }
            }
        }

        // Update panel step counter
        if (heatmapTypeA.getStepCount() != lastStepCountA || heatmapTypeB.getStepCount() != lastStepCountB) {
            SwingUtilities.invokeLater(panel::updateCounts);
        }

        // Update last coords
        lastX = currentX;
        lastY = currentY;
        lastStepCountA = heatmapTypeA.getStepCount();
        lastStepCountB = heatmapTypeB.getStepCount();
    }

    /**
     * @param x X coordinate
     * @param y Y coordinate
     * @return True if (x, y) location falls within the region of the world map that will be written over the image osrs_world_map.png
     */
    private boolean isInBounds(int x, int y) {
        return (MIN_X <= x && x <= MAX_X && MIN_Y <= y && y <= MAX_Y);
    }

    // Credit to https:// www.redblobgames.com/grids/line-drawing.html for where I figured out how to make the following linear interpolation functions

    /**
     * Returns the list of discrete coordinates on the path between p0 and p1, as an array of Points
     *
     * @param p0 Point A
     * @param p1 Point B
     * @return Array of coordinates
     */
    private Point[] getPointsBetween(Point p0, Point p1) {
        if (p0.equals(p1)) {
            return new Point[]{p1};
        }
        int N = diagonalDistance(p0, p1);
        Point[] points = new Point[N];
        for (int step = 1; step <= N; step++) {
            float t = step / (float) N;
            points[step - 1] = roundPoint(lerp_point(p0, p1, t));
        }
        return points;
    }

    /**
     * Returns the diagonal distance (the maximum of the horizontal and vertical distance) between two points
     *
     * @param p0 Point A
     * @param p1 Point B
     * @return The diagonal distance
     */
    private int diagonalDistance(Point p0, Point p1) {
        int dx = Math.abs(p1.x - p0.x);
        int dy = Math.abs(p1.y - p0.y);
        return Math.max(dx, dy);
    }

    /**
     * Rounds a floating point coordinate to its nearest integer coordinate.
     *
     * @param point The point to round
     * @return Coordinates
     */
    private Point roundPoint(float[] point) {
        return new Point(Math.round(point[0]), Math.round(point[1]));
    }

    /**
     * Returns the floating point 2D coordinate that is t-percent of the way between p0 and p1
     *
     * @param p0 Point A
     * @param p1 Point B
     * @param t  Percent distance
     * @return Coordinate that is t% of the way from A to B
     */
    private float[] lerp_point(Point p0, Point p1, float t) {
        return new float[]{lerp(p0.x, p1.x, t), lerp(p0.y, p1.y, t)};
    }

    /**
     * Returns the floating point number that is t-percent of the way between p0 and p1.
     *
     * @param p0 Point A
     * @param p1 Point B
     * @param t  Percent distance
     * @return Point that is t-percent of the way from A to B
     */
    private float lerp(int p0, int p1, float t) {
        return p0 + (p1 - p0) * t;
    }

    // Loads heatmap from local storage. If file does not exist, or an error occurs, it will return null.
    private HeatmapNew readHeatmapFile(String filepath) {
        log.info("Loading heatmap file '" + filepath + "'");
        File heatmapFile = new File(filepath);
        if (!heatmapFile.exists()) {
            // Return new blank heatmap if specified file doesn't exist
            log.error("World Heatmap was not able to load Worldheatmap file " + filepath + " because it does not exist.");
            return null;
        }
        // Detect whether the .heatmap file is the old style (serialized Heatmap, rather than a zipped .CSV file)
        // And if it is, then convert it to the new style
        try (FileInputStream fis = new FileInputStream(filepath)) {
            InflaterInputStream iis = new InflaterInputStream(fis);
            ObjectInputStream ois = new ObjectInputStream(iis);
            Object heatmap = ois.readObject();
            if (heatmap instanceof Heatmap) {
                log.info("Attempting to convert old-style heatmap file to new style...");
                long startTime = System.nanoTime();
                HeatmapNew result = HeatmapNew.convertOldHeatmapToNew((Heatmap) heatmap, mostRecentLocalUserID);
                log.info("Finished converting old-style heatmap to new style in " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
                return result;
            }
        } catch (Exception e) {
            // If reached here, then the file was not of the older type.
        }
        // Test if it is of the newer type, or something else altogether.
        try (FileInputStream fis = new FileInputStream(filepath)) {
            ZipInputStream zis = new ZipInputStream(fis);
            InputStreamReader isr = new InputStreamReader(zis, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(isr);
            zis.getNextEntry();
            String[] fieldNames = reader.readLine().split(",");
            String[] fieldValues = reader.readLine().split(",");
            log.debug("Field Variables detectarino'd: " + Arrays.toString(fieldNames));
            long userID = (fieldValues[0].length() == 0 ? -1 : Long.parseLong(fieldValues[0]));
            int stepCount = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[2]));
            int maxVal = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[3]));
            int maxValX = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[4]));
            int maxValY = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[5]));
            int minVal = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[6]));
            int minValX = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[7]));
            int minValY = (fieldValues[0].length() == 0 ? -1 : Integer.parseInt(fieldValues[8]));

            HeatmapNew heatmapNew;
            if (userID != -1)
                heatmapNew = new HeatmapNew(userID);
            else
                heatmapNew = new HeatmapNew();
            heatmapNew.maxVal = new int[]{maxVal, maxValX, maxValY};
            heatmapNew.minVal = new int[]{minVal, minValX, minValY};
            heatmapNew.stepCount = stepCount;

            // Read the tile values
            final int[] errorCount = {0}; // Number of parsing errors occurred during read
            reader.lines().forEach(s -> {
                String[] tile = s.split(",");
                try {
                    heatmapNew.setFast(Short.parseShort(tile[0]), Short.parseShort(tile[1]), Integer.parseInt(tile[2]));
                } catch (NumberFormatException e) {
                    errorCount[0]++;
                }
            });
            log.error(errorCount[0] + " errors occurred during heatmap file read.");
            return heatmapNew;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("The file " + filepath + " is not a heatmap file or it is corrupt, or something.");
        }
        return null;
    }

    /**
     * Saves heatmap to 'Heatmap Files' folder.
     */
    protected void writeHeatmapFile(HeatmapNew heatmap, File fileOut) {
        if (!fileOut.isAbsolute()) {
            fileOut = new File(HEATMAP_FILES_DIR, fileOut.toString());
        }
        log.info("Saving " + fileOut + " heatmap file to disk...");
        long startTime = System.nanoTime();
        // Make the directory path if it doesn't exist
        File file = fileOut;
        if (!Files.exists(Paths.get(file.getParent()))) {
            new File(file.getParent()).mkdirs();
        }

        // Write the heatmap file
        try {
            FileOutputStream fos = new FileOutputStream(fileOut);
            ZipOutputStream zos = new ZipOutputStream(fos);
            BufferedOutputStream bos = new BufferedOutputStream(zos);
            OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8);
            ZipEntry ze = new ZipEntry("heatmap.csv");
            zos.putNextEntry(ze);
            // Write them field variables
            osw.write("userID,heatmapVersion,stepCount,maxVal,maxValX,maxValY,minVal,minValX,minValY\n");
            osw.write(mostRecentLocalUserID +
                    "," + heatmap.heatmapVersion +
                    "," + heatmap.stepCount +
                    "," + heatmap.maxVal[0] +
                    "," + heatmap.maxVal[1] +
                    "," + heatmap.maxVal[2] +
                    "," + heatmap.minVal[0] +
                    "," + heatmap.minVal[1] +
                    "," + heatmap.minVal[2] + "\n");
            // Write the tile values
            for (IntIntPair e : heatmap.getKeyValuesView()) {
                short[] coords = HeatmapNew.decodeIntCoordinate(e.getOne());
                short x = coords[0];
                short y = coords[1];
                int stepVal = e.getTwo();
                osw.write(x + "," + y + "," + stepVal + "\n");
            }
            osw.close();
        } catch (IOException e) {
            e.printStackTrace();
            log.error("World Heatmap was not able to save heatmap file");
        }
        log.info("Finished writing " + fileOut + " heatmap file to disk after " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
    }

    /**
     * Creates a CSV that just holds the tile coordinates and step values
     *
     * @param csvURI  The URI at which to save ze CSV
     * @param heatmap The HeatmapNew object
     */
    protected void writeCSVFile(File csvURI, HeatmapNew heatmap) {
        try (PrintWriter pw = new PrintWriter(csvURI)) {
            for (IntIntPair e : heatmap.getKeyValuesView()) {
                short[] coords = HeatmapNew.decodeIntCoordinate(e.getOne());
                short x = coords[0];
                short y = coords[1];
                int stepVal = e.getTwo();
                pw.write(x + "," + y + "," + stepVal + "\n");
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            log.error("World Heatmap was not able to save heatmap CSV file");
        }
    }

    /**
     * Writes a visualization of the heatmap over top of the OSRS world map as a PNG image to the "Heatmap Images" folder.
     */
    protected void writeHeatmapImage(HeatmapNew heatmap, File imageFileOut) {
        log.info("Saving " + imageFileOut + " image to disk...");
        long startTime = System.nanoTime();
        if (!imageFileOut.getName().endsWith(".png")) {
            imageFileOut = new File(imageFileOut.getName() + ".png");
        }

        if (!imageFileOut.isAbsolute()) {
            imageFileOut = new File(HEATMAP_IMAGE_DIR, imageFileOut.getName());
        }

        double heatmapTransparency = config.heatmapAlpha();
        if (heatmapTransparency < 0) {
            heatmapTransparency = 0;
        } else if (heatmapTransparency > 1) {
            heatmapTransparency = 1;
        }

        // First, to normalize the range of step values, we find the maximum and minimum values in the heatmap
        int[] maxValAndCoords = heatmap.getMaxVal();
        int maxVal = maxValAndCoords[0];
        int maxX = maxValAndCoords[1];
        int maxY = maxValAndCoords[2];
        log.debug("Maximum steps on a tile is: " + maxVal + " at (" + maxX + ", " + maxY + "), for " + imageFileOut + " of a total of " + heatmap.getStepCount() + " steps");
        int[] minValAndCoords = heatmap.getMinVal();
        int minVal = minValAndCoords[0];
        int minX = minValAndCoords[1];
        int minY = minValAndCoords[2];
        log.debug("Minimum steps on a tile is: " + minVal + " at (" + minX + ", " + minY + "), for " + imageFileOut + " of a total of " + heatmap.getStepCount() + " steps");
        maxVal = (maxVal == minVal ? maxVal + 1 : maxVal); // If minVal == maxVal, which is the case when a new heatmap is created, it might cause division by zero, in which case we add 1 to max val.
        log.debug("Number of tiles visited: " + heatmap.getNumTilesVisited());

        try {
            BufferedImage worldMapImage = ImageUtil.loadImageResource(getClass(), "/osrs_world_map.png");
            assert worldMapImage != null;
            if (worldMapImage.getWidth() != HEATMAP_WIDTH * 3 || worldMapImage.getHeight() != HEATMAP_HEIGHT * 3) {
                log.error("The file 'osrs_world_map.png' must have dimensions " + HEATMAP_WIDTH * 3 + " x " + HEATMAP_HEIGHT * 3);
                return;
            }
            int currRGB = 0;
            double currHue = 0; // a number on the interval [0, 1] that will represent the intensity of the current heatmap pixel
            double minHue = 1 / 3., maxHue = 0;
            double nthRoot = 1 + (config.heatmapSensitivity() - 1.0) / 2;
            int LOG_BASE = 4;

            for (IntIntPair e : heatmap.getKeyValuesView()) {
                short[] coords = HeatmapNew.decodeIntCoordinate(e.getOne());
                int x = coords[0] + HEATMAP_OFFSET_X;
                int y = HEATMAP_HEIGHT - (coords[1] + HEATMAP_OFFSET_Y) - 1;
                int value = e.getTwo();
                boolean isInBounds = (0 <= x && x < HEATMAP_WIDTH && 0 <= y && y < HEATMAP_HEIGHT);
                if (isInBounds && value != 0) {   // If the current tile HAS been stepped on (also we invert the y-coords here, because the game uses a different coordinate system than what is typical for images)
                    // Calculate normalized step value
                    currHue = (float) ((Math.log(value) / Math.log(LOG_BASE)) / (Math.log(maxVal + 1 - minVal) / Math.log(LOG_BASE)));
                    currHue = Math.pow(currHue, 1.0 / nthRoot);
                    currHue = (float) (minHue + (currHue * (maxHue - minHue)));                                                // Assign a hue based on normalized step value (values [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red)

                    // Reassign the new RGB values to the corresponding 9 pixels (we scale by a factor of 3)
                    for (int x_offset = 0; x_offset <= 2; x_offset++) {
                        for (int y_offset = 0; y_offset <= 2; y_offset++) {
                            int curX = x * 3 + x_offset;
                            int curY = y * 3 + y_offset;
                            int srcRGB = worldMapImage.getRGB(curX, curY);
                            int r = (srcRGB >> 16) & 0xFF;
                            int g = (srcRGB >> 8) & 0xFF;
                            int b = (srcRGB) & 0xFF;
                            double srcBrightness = Color.RGBtoHSB(r, g, b, null)[2] * (1 - heatmapTransparency) + heatmapTransparency;
                            // convert HSB to RGB with the calculated Hue, with Saturation=1 and Brightness according to original map pixel
                            currRGB = Color.HSBtoRGB((float) currHue, 1, (float) srcBrightness);
                            worldMapImage.setRGB(curX, curY, currRGB);
                        }
                    }
                } else if (!isInBounds) {
                    log.debug("Heatmap for some reason had out of bounds value at (" + x + ", " + y + ")");
                }
            }

            if (!Files.exists(Paths.get(imageFileOut.getParent()))) {
                new File(imageFileOut.getParent()).mkdirs();
            }
            ImageIO.write(worldMapImage, "png", imageFileOut);
            log.info("Finished writing " + imageFileOut + " image to disk after " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            log.error("OutOfMemoryError thrown whilst creating and/or writing image file." +
                    "Perhaps try making a Runelite plugin profile with no other plugins enabled" +
                    "besides World Heatmap. If it works then, then you might have too many plugins" +
                    "running. If not, then I unno chief, perhaps you should submit an issue on the" +
                    "GitHub.");
        } catch (IOException e) {
            e.printStackTrace();
            log.error("IOException thrown whilst creating and/or writing image file.");
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Exception thrown whilst creating and/or writing image file.");
        }
    }

    /**
     * Combines heatmaps, adding together each tile value
     *
     * @param outputFile      Output .heatmap file
     * @param outputImageFile Output .png file
     * @param heatmapFiles    Input .heatmap files
     * @return True if completed successfully, else false
     */
    public boolean combineHeatmaps(File outputFile, @Nullable File outputImageFile, File... heatmapFiles) {
        HeatmapNew outputHeatmap = new HeatmapNew(mostRecentLocalUserID);
        for (File hmapFile : heatmapFiles) {
            if (!hmapFile.exists()) {
                log.error("Input heatmap file " + hmapFile.getAbsolutePath() + " doesn't exist");
                return false;
            }
            HeatmapNew hmap = readHeatmapFile(hmapFile.getAbsolutePath());
            log.info("Adding heatmap file " + hmapFile + " to " + outputFile.getName() + "...");
            for (IntIntPair e : hmap.getKeyValuesView()) {
                short[] coords = HeatmapNew.decodeIntCoordinate(e.getOne());
                short x = coords[0];
                short y = coords[1];
                outputHeatmap.set(x, y, e.getTwo());
            }
        }
        writeHeatmapFile(outputHeatmap, outputFile);
        if (outputImageFile != null) {
            writeHeatmapImage(outputHeatmap, outputImageFile);
        }
        return true;
    }
}
