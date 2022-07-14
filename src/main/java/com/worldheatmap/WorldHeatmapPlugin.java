package com.worldheatmap;

import com.google.inject.Provides;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;

import com.google.common.base.Strings;
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
import static net.runelite.client.RuneLite.RUNELITE_DIR;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

@Slf4j
@PluginDescriptor(
	name = "World Heatmap"
)
public class WorldHeatmapPlugin extends Plugin
{
	private static final int OVERWORLD_MAX_X = 3904,
			OVERWORLD_MAX_Y = Constants.OVERWORLD_MAX_Y,
			TYPE_A_HEATMAP_AUTOSAVE_FREQUENCY = 100,
			TYPE_B_HEATMAP_AUTOSAVE_FREQUENCY = 500,
			HEATMAP_OFFSET_X = -1152,
			HEATMAP_OFFSET_Y = -2496;
	private int lastX = 0,
			lastY = 0,
			lastStepCountA = 0,
			lastStepCountB = 0;
	protected final File WORLDHEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap");
	protected final String HEATMAP_FILES_DIR = Paths.get(WORLDHEATMAP_DIR.toString(),"Heatmap Files").toString(), HEATMAP_IMAGE_DIR = Paths.get(WORLDHEATMAP_DIR.toString(), "Heatmap Images").toString();
	private final int HEATMAP_WIDTH = 2752, HEATMAP_HEIGHT = 1664;
	protected Heatmap heatmapTypeA;
	protected Heatmap heatmapTypeB;
	private NavigationButton toolbarButton;
	private WorldHeatmapPanel panel;
	private boolean shouldLoadHeatmaps;
	private String mostRecentLocalUserName;
	private final float HEATMAP_TRANSPARENCY = 0.65f;

	@Inject
	private Client client;

	protected final Runnable LOAD_HEATMAP_FILES = () -> {
		String filepathTypeA = Paths.get(HEATMAP_FILES_DIR, client.getLocalPlayer().getName()) + "_TypeA.heatmap";
		String filepathTypeB = Paths.get(HEATMAP_FILES_DIR, client.getLocalPlayer().getName()) + "_TypeB.heatmap";
		heatmapTypeA = readHeatmapFile(filepathTypeA);
		heatmapTypeB = readHeatmapFile(filepathTypeB);
		panel.writeTypeAHeatmapImageButton.setEnabled(true);
		panel.writeTypeBHeatmapImageButton.setEnabled(true);
		panel.clearTypeAHeatmapButton.setEnabled(true);
		panel.clearTypeBHeatmapButton.setEnabled(true);
	};
	private Future loadHeatmapsFuture;

	private final Runnable SAVE_TYPE_A_HEATMAP = () -> {
		if (Strings.isNullOrEmpty(mostRecentLocalUserName))
			return;
		log.info("Saving 'Type A' .heatmap to disk...");
		long startTime = System.nanoTime();
		String filepathTypeA = Paths.get(HEATMAP_FILES_DIR, mostRecentLocalUserName) + "_TypeA.heatmap";
		writeHeatmapFile(heatmapTypeA, filepathTypeA);
		log.info("Finished writing 'Type A' .heatmap to disk after " + (System.nanoTime() - startTime)/1_000_000 + " ms");
	};

	private final Runnable SAVE_TYPE_B_HEATMAP = () -> {
		if (Strings.isNullOrEmpty(mostRecentLocalUserName))
			return;
		log.info("Saving 'Type B' .heatmap to disk...");
		long startTime = System.nanoTime();
		String filepathTypeB = Paths.get(HEATMAP_FILES_DIR, mostRecentLocalUserName) + "_TypeB.heatmap";
		writeHeatmapFile(heatmapTypeB, filepathTypeB);
		log.info("Finished writing 'Type B' .heatmap to disk after " + (System.nanoTime() - startTime)/1_000_000 + " ms");
	};

	protected final Runnable WRITE_TYPE_A_IMAGE = () -> {
		log.info("Saving 'Type A' heatmap image to disk...");
		long startTime = System.nanoTime();
		String filepathTypeA = Paths.get(HEATMAP_IMAGE_DIR, client.getLocalPlayer().getName() + "_TypeA").toString();
		writeHeatmapImage(heatmapTypeA, filepathTypeA, Heatmap.TYPE_A);
		log.info("Finished writing 'Type A' heatmap image to disk after " + (System.nanoTime() - startTime)/1_000_000 + " ms");
	};

	protected final Runnable WRITE_TYPE_B_IMAGE = () -> {
		log.info("Saving 'Type B' heatmap images to disk...");
		long startTime = System.nanoTime();
		String filepathTypeB = Paths.get(HEATMAP_IMAGE_DIR, client.getLocalPlayer().getName() + "_TypeB").toString();
		writeHeatmapImage(heatmapTypeB, filepathTypeB, Heatmap.TYPE_B);
		log.info("Finished writing 'Type B' heatmap image to disk after " + (System.nanoTime() - startTime)/1_000_000 + " ms");
	};

	protected final Runnable CLEAR_TYPE_A_HEATMAP = () -> {
		heatmapTypeA = new Heatmap(HEATMAP_WIDTH, HEATMAP_HEIGHT, HEATMAP_OFFSET_X, HEATMAP_OFFSET_Y);
		log.info("Writing blank 'Type A' heatmap images to disk...");
		long startTime = System.nanoTime();
		String imageFilepathTypeA = Paths.get(HEATMAP_IMAGE_DIR, client.getLocalPlayer().getName() + "_TypeA").toString();
		writeHeatmapImage(heatmapTypeA, imageFilepathTypeA, Heatmap.TYPE_A);
		log.info("Finished writing blank 'Type B' heatmap images to disk after " + (System.nanoTime() - startTime)/1_000_000 + " ms");
		String heatmapFilepathTypeA = Paths.get(HEATMAP_FILES_DIR, client.getLocalPlayer().getName()) + "_TypeA.heatmap";
		writeHeatmapFile(heatmapTypeA, heatmapFilepathTypeA);
	};

	protected final Runnable CLEAR_TYPE_B_HEATMAP = () -> {
		heatmapTypeB = new Heatmap(HEATMAP_WIDTH, HEATMAP_HEIGHT, HEATMAP_OFFSET_X, HEATMAP_OFFSET_Y);
		log.info("Writing blank 'Type B' heatmap image to disk...");
		long startTime = System.nanoTime();
		String imageFilepathTypeB = Paths.get(HEATMAP_IMAGE_DIR, client.getLocalPlayer().getName() + "_TypeB").toString();
		writeHeatmapImage(heatmapTypeB, imageFilepathTypeB, Heatmap.TYPE_B);
		log.info("Finished writing blank 'Type B' heatmap image to disk after " + (System.nanoTime() - startTime)/1_000_000 + " ms");
		String heatmapFilepathTypeB = Paths.get(HEATMAP_FILES_DIR, client.getLocalPlayer().getName()) + "_TypeB.heatmap";
		writeHeatmapFile(heatmapTypeB, heatmapFilepathTypeB);
	};

	private final Runnable WRITE_NEW_TYPE_A_BACKUP = () -> {
		String heatmapFilePathTypeA = Paths.get(HEATMAP_FILES_DIR, "Backups", client.getLocalPlayer().getName() + "-" + java.time.LocalDateTime.now() + "_TypeA.heatmap").toString();
		writeHeatmapFile(heatmapTypeA, heatmapFilePathTypeA);
		log.info("World Heatmap backup saved to " + heatmapFilePathTypeA);
	};

	private final Runnable WRITE_NEW_TYPE_B_BACKUP = () -> {
		String heatmapFilePathTypeB = Paths.get(HEATMAP_FILES_DIR, "Backups", client.getLocalPlayer().getName() + "-" + java.time.LocalDateTime.now() + "_TypeB.heatmap").toString();
		writeHeatmapFile(heatmapTypeB, heatmapFilePathTypeB);
		log.info("World Heatmap backup saved to " + heatmapFilePathTypeB);
	};

	@Inject
	protected ScheduledExecutorService executor;

	@Inject
	private WorldHeatmapConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Provides
	WorldHeatmapConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WorldHeatmapConfig.class);
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
	}

	@Override
	protected void shutDown() {
		if (loadHeatmapsFuture != null && loadHeatmapsFuture.isDone()){
			executor.execute(SAVE_TYPE_A_HEATMAP);
			executor.execute(SAVE_TYPE_B_HEATMAP);
		}
		clientToolbar.removeNavigation(toolbarButton);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged){
		if (gameStateChanged.getGameState() == GameState.LOGGING_IN){
			shouldLoadHeatmaps = true;
			loadHeatmapsFuture = null;
			//The panel buttons are enabled within the LOAD_HEATMAPS Runnable thingamajigger, so that they're not enabled until it's done
		}

		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN && loadHeatmapsFuture != null && loadHeatmapsFuture.isDone()){
			executor.execute(SAVE_TYPE_A_HEATMAP);
			executor.execute(SAVE_TYPE_B_HEATMAP);
			loadHeatmapsFuture = null;
		}
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN){
			panel.writeTypeAHeatmapImageButton.setEnabled(false);
			panel.writeTypeBHeatmapImageButton.setEnabled(false);
			panel.clearTypeAHeatmapButton.setEnabled(false);
			panel.clearTypeBHeatmapButton.setEnabled(false);
		}
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN){
			panel.writeTypeAHeatmapImageButton.setEnabled(true);
			panel.writeTypeBHeatmapImageButton.setEnabled(true);
			panel.clearTypeAHeatmapButton.setEnabled(true);
			panel.clearTypeBHeatmapButton.setEnabled(true);
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick){
		if (shouldLoadHeatmaps && client.getGameState().equals(GameState.LOGGED_IN) && !Strings.isNullOrEmpty(client.getLocalPlayer().getName())) {
			shouldLoadHeatmaps = false;
			loadHeatmapsFuture = executor.submit(LOAD_HEATMAP_FILES);
		}
		//The following code requires the heatmap files to have been loaded
		if (loadHeatmapsFuture != null && !loadHeatmapsFuture.isDone())
			return;

		WorldPoint currentCoords = client.getLocalPlayer().getWorldLocation();
		int currentX = currentCoords.getX();
		int currentY = currentCoords.getY();

		//The following code is for the 'Type A' heatmap
		if (currentX < OVERWORLD_MAX_X && currentY < OVERWORLD_MAX_Y && (currentX != lastX || currentY != lastY)) { //If the player is in the overworld and has moved since last game tick
			/* When running, players cover more than one tile per tick, which creates spotty paths.
			 * We fix this by drawing a line between the current coordinates and the previous coordinates,
			 * but we have to be sure that the player indeed ran from point A to point B, differentiating the movement from teleportation.
			 * Since it's too hard to check if the player is actually running, we'll just check if the distance covered since last tick
			 * was less than 5 tiles, and the player hasn't cast a teleport in the last few ticks.
			 */
			int diagDistance = diagonalDistance(new int[]{ lastX, lastY}, new int[]{ currentX, currentY});
			boolean largeStep = diagDistance >= 5;
			if (!largeStep) {
				//Increments all the tiles between new position and last position
				for (int[]  tile : getPointsBetween(new int[]{ lastX, lastY}, new int[]{ currentX, currentY})){
					if (heatmapTypeA.isInBounds(tile[0], tile[1])) {
						heatmapTypeA.increment(tile[0], tile[1]);
						if (config.typeAImageAutosaveOnOff() && heatmapTypeA.getStepCount() % config.typeAImageAutosaveFrequency() == 0) {	//If it's time to write the image, then save heatmap and write image
							executor.execute(SAVE_TYPE_A_HEATMAP);
							executor.execute(WRITE_TYPE_A_IMAGE);
						}
						else if (heatmapTypeA.getStepCount() % TYPE_A_HEATMAP_AUTOSAVE_FREQUENCY == 0){						//only if it wasn't the time to do the above, then check if it's time to autosave
							executor.execute(SAVE_TYPE_A_HEATMAP);
						}
						if (heatmapTypeA.getStepCount() % config.typeAHeatmapBackupFrequency() == 0)
							executor.execute(WRITE_NEW_TYPE_A_BACKUP);
					}
					else
						log.error("World Heatmap: Coordinates out of bounds for heatmap matrix: (" + tile[0] + ", " + tile[1] + ")");
				}
			}
		}

		//The following code is for the 'Type B' heatmap
		if (currentX < OVERWORLD_MAX_X && currentY < OVERWORLD_MAX_Y) { //If the player is in the overworld
			/* When running, players cover more than one tile per tick, which creates spotty paths.
			 * We fix this by drawing a line between the current coordinates and the previous coordinates,
			 * but we have to be sure that the player indeed ran from point A to point B, differentiating the movement from teleportation.
			 * Since it's too hard to check if the player is actually running, we'll just check if the distance covered since last tick
			 * was less than 5 tiles, and the player hasn't cast a teleport in the last few ticks.
			 */
			int diagDistance = diagonalDistance(new int[]{ lastX, lastY}, new int[]{ currentX, currentY});
			boolean largeStep = diagDistance >= 5;
			if (!largeStep) {
				//Increments all the tiles between new position and last position
				for (int[]  tile : getPointsBetween(new int[]{ lastX, lastY}, new int[]{ currentX, currentY})){
					if (heatmapTypeB.isInBounds(tile[0], tile[1])) {
						heatmapTypeB.increment(tile[0], tile[1]);
						if (config.typeBImageAutosaveOnOff() && heatmapTypeB.getStepCount() % config.typeBImageAutosaveFrequency() == 0) {	//If it's tiem to write the image, then save heatmap and write image
							executor.execute(SAVE_TYPE_B_HEATMAP);
							executor.execute(WRITE_TYPE_B_IMAGE);
						}
						else if (heatmapTypeB.getStepCount() % TYPE_B_HEATMAP_AUTOSAVE_FREQUENCY == 0){ 							//only if it wasn't the time to do the above, then check if it's time to autosave
							executor.execute(SAVE_TYPE_B_HEATMAP);
						}
						if (heatmapTypeB.getStepCount() % config.typeBHeatmapBackupFrequency() == 0)
							executor.execute(WRITE_NEW_TYPE_B_BACKUP);
					}
					else
						log.error("World Heatmap: Coordinates out of bounds for heatmap matrix: (" + tile[0] + ", " + tile[1] + ")");
				}
			}
		}
		if (heatmapTypeA.getStepCount() != lastStepCountA || heatmapTypeB.getStepCount() != lastStepCountB) {
			SwingUtilities.invokeLater(panel::updateCounts);
			//log.debug(String.format("Type A step count: %3d Type B step count: %3d", heatmapTypeA.getStepCount(), heatmapTypeB.getStepCount()));
		}
		//Update last coords
		lastX = currentX;
		lastY = currentY;
		lastStepCountA = heatmapTypeA.getStepCount();
		lastStepCountB = heatmapTypeB.getStepCount();
		mostRecentLocalUserName = client.getLocalPlayer().getName(); //This is for saving the heatmap files to the correct filename even after the user has just logged out
	}

	//Credit to https://www.redblobgames.com/grids/line-drawing.html for where I figured out how to make the following linear interpolation functions

	/**
	 * Returns the list of discrete coordinates on the path between p0 and p1, as an array of int[]s
	 * @param p0 Point A
	 * @param p1 Point B
	 * @return Array of coordinates
	 */
	private int[][] getPointsBetween(int[] p0, int[] p1){
		if (Arrays.equals(p0, p1))
			return new int[][]{ p1 };
		int N = diagonalDistance(p0, p1);
		int[][] points = new int[N][2];
		for (int step = 1; step <= N; step++) {
			float t = step / (float)N;
			points[step - 1] = roundPoint(lerp_point(p0, p1, t));
		}
		return points;
	}

	/**
	 * Returns the diagonal distance (the maximum of the horizontal and vertical distance) between two points
	 * @param p0 Point A
	 * @param p1 Point B
	 * @return The diagonal distance
	 */
	private int diagonalDistance(int[] p0, int[] p1){
		int dx = Math.abs(p1[0] - p0[0]);
		int dy = Math.abs(p1[1] - p0[1]);
		return Math.max(dx, dy);
	}

	/**
	 * Rounds a floating point coordinate to its nearest integer coordinate.
	 * @param point The point to round
	 * @return Coordinates
	 */
	private int[] roundPoint(float[] point){
		return new int[]{ Math.round(point[0]), Math.round(point[1])};
	}

	/**
	 * Returns the floating point 2D coordinate that is t-percent of the way between p0 and p1
	 * @param p0 Point A
	 * @param p1 Point B
	 * @param t Percent distance
	 * @return Coordinate that is t% of the way from A to B
	 */
	private float[] lerp_point(int[] p0, int[] p1, float t){
		return new float[] { lerp(p0[0], p1[0], t), lerp(p0[1], p1[1], t)};
	}

	/**
	 * Returns the floating point number that is t-percent of the way between p0 and p1.
	 * @param p0 Point A
	 * @param p1 Point B
	 * @param t Percent distance
	 * @return Point that is t-percent of the way from A to B
	 */
	private float lerp(int p0, int p1, float t) {
		return p0 + (p1-p0) * t;
	}

	//Loads heatmap from local storage. If file does not exist, it will create a new one.
	private Heatmap readHeatmapFile(String filepath) {
		log.info("Loading heatmap file '" + filepath + "'");
		File heatmapFile = new File(filepath);
		if (heatmapFile.exists()) {
			try (FileInputStream fis = new FileInputStream(filepath)) {
				InflaterInputStream inflaterIn = new InflaterInputStream(fis);
				ObjectInputStream ois = new ObjectInputStream(inflaterIn);
				return (Heatmap) ois.readObject();
			} catch (Exception e) {
				e.printStackTrace();
				log.info("World Heatmap was not able to load existing heatmap file, for some reason, so a new blank one was loaded");
				return new Heatmap(HEATMAP_WIDTH, HEATMAP_HEIGHT, HEATMAP_OFFSET_X, HEATMAP_OFFSET_Y);
			}
		}
		else{ //Return new blank heatmap if specified file doesn't exist
			log.info("Worldheatmap file " + filepath + " did not exist when read. A new heatmap will be made.");
			return new Heatmap(HEATMAP_WIDTH, HEATMAP_HEIGHT, HEATMAP_OFFSET_X, HEATMAP_OFFSET_Y);
		}
	}

	/**
	 * Saves heatmap matrix to 'Heatmap Results' folder.
	 */
	protected void writeHeatmapFile(Heatmap heatmap, String filename) {
		//Make the directory path if it doesn't exist
		File file = new File(filename);
		if (!Files.exists(Paths.get(file.getParent())))
			new File(file.getParent()).mkdirs();

		//Write the heatmap file
		try{
			FileOutputStream fos = new FileOutputStream(filename);
			DeflaterOutputStream dos = new DeflaterOutputStream(fos);
			ObjectOutputStream oos = new ObjectOutputStream(dos);
			oos.writeObject(heatmap);
			oos.close();
		}
		catch(IOException e){
			e.printStackTrace();
			log.error("World Heatmap was not able to save heatmap file");
		}
	}

	/**
	 * Writes a visualization of the heatmap matrix over top of the OSRS world map as a PNG image to the "Heatmap Results" folder.
	 */
	private void writeHeatmapImage(Heatmap heatmap, String fileName, int heatmapType){
		//First, to normalize the range of step values, we find the maximum and minimum values in the heatmap
		int[] maxValAndCoords = heatmap.getMaxVal();
		int maxVal = maxValAndCoords[0];
		int maxX = maxValAndCoords[1];
		int maxY = maxValAndCoords[2];
		log.debug("Maximum steps on a tile is: " + maxVal + " at (" + maxX + ", " + maxY + "), for " + fileName);
		int[] minValAndCoords = heatmap.getMinVal();
		int minVal = minValAndCoords[0];
		int minX = minValAndCoords[1];
		int minY = minValAndCoords[2];
		log.debug("Minimum steps on a tile is: " + minVal + " at (" + minX + ", " + minY + "), for " + fileName);
		maxVal = (maxVal == minVal ? maxVal + 1 : maxVal); //If maxVal == maxVal, which is the case when a new heatmap is created, it might cause division by zero, in which case we add 1 to max val.

		try{
			File worldMapImageFile = new File(WORLDHEATMAP_DIR, "osrs_world_map.png");
			BufferedImage worldMapImage = ImageIO.read(worldMapImageFile);
			BufferedImage heatmapOverlay = new BufferedImage(worldMapImage.getWidth(), worldMapImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
			if (worldMapImage.getWidth() != HEATMAP_WIDTH*3 || worldMapImage.getHeight() != HEATMAP_HEIGHT*3){
				log.error("The file 'osrs_world_map.png' must have dimensions " + HEATMAP_WIDTH*3 + " x " + HEATMAP_HEIGHT*3);
				return;
			}
			int currRGB = 0;
			float currStepValue = 0, currHue = 0; //a number on the interval [0, 1] that will represent the intensity of the current heatmap pixel
			for (int y = 0; y < HEATMAP_HEIGHT; y++) {
				for (int x = 0; x < HEATMAP_WIDTH; x++) {
					int invertedY = HEATMAP_HEIGHT - y - 1;
					if (heatmap.heatmapCoordsGet(x, invertedY) != 0) {														//If the current tile HAS been stepped on (also we invert the y-coords here, because the game uses a different coordinate system than what is typical for images)
						if (heatmapType == Heatmap.TYPE_A){
							//Calculate normalized step value
							currStepValue = ((float) heatmap.heatmapCoordsGet(x, invertedY) - minVal) / (maxVal - minVal);
							currHue = (float)(0.333 - (currStepValue * 0.333));												//Assign a hue based on normalized step value (values [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red)
						}
						if (heatmapType == Heatmap.TYPE_B){
							//Calculate normalized step value
							int normalizedMin = 0;
							int normalizedMax = 1;
							currStepValue = (float) (Math.log(heatmap.heatmapCoordsGet(x, invertedY) + 1 - minVal) / Math.log(maxVal + 1 - minVal));
							currStepValue = currStepValue > 1 ? 1 : currStepValue;
							float normalized = currStepValue*(normalizedMax-normalizedMin);
							currHue = (float)(0.333 - (normalized * 0.333));												//Assign a hue based on normalized step value (values [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red)
						}

						//convert HSB to RGB with the calculated Hue, with Saturation and Brightness always 1
						currRGB = Color.HSBtoRGB(currHue, 1, 1);

						//Now we reassign the new RGB values to the corresponding 9 pixels (we scale by a factor of 3)
						heatmapOverlay.setRGB(x*3 + 0, y*3 + 0, currRGB);
						heatmapOverlay.setRGB(x*3 + 0, y*3 + 1, currRGB);
						heatmapOverlay.setRGB(x*3 + 0, y*3 + 2, currRGB);
						heatmapOverlay.setRGB(x*3 + 1, y*3 + 0, currRGB);
						heatmapOverlay.setRGB(x*3 + 1, y*3 + 1, currRGB);
						heatmapOverlay.setRGB(x*3 + 1, y*3 + 2, currRGB);
						heatmapOverlay.setRGB(x*3 + 2, y*3 + 0, currRGB);
						heatmapOverlay.setRGB(x*3 + 2, y*3 + 1, currRGB);
						heatmapOverlay.setRGB(x*3 + 2, y*3 + 2, currRGB);
					}
				}
			}

			//Overlay the heatmap on top of the world map image, with semi-transparency, then write file
			File heatmapImageFile = new File(fileName);
			if (!Files.exists(Paths.get(heatmapImageFile.getParent())))
				new File(heatmapImageFile.getParent()).mkdirs();
			Graphics2D graphics = worldMapImage.createGraphics();
			AlphaComposite alphaChannel = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, HEATMAP_TRANSPARENCY);
			graphics.setComposite(alphaChannel);
			graphics.drawImage(heatmapOverlay, 0, 0, null);
			graphics.dispose();
			ImageIO.write(worldMapImage, "png", heatmapImageFile);
		}
		catch (IOException e){
			e.printStackTrace();
			log.error("Exception thrown whilst creating and/or writing image file.");
		}
	}
}
