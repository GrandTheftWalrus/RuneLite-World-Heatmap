package com.worldheatmap;

import com.google.inject.Provides;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.PlayerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

@Slf4j
@PluginDescriptor(
	name = "World Heatmap"
)
public class WorldHeatmapPlugin extends Plugin
{
	private static final int OVERWORLD_MAX_X = 3904, OVERWORLD_MAX_Y = Constants.OVERWORLD_MAX_Y, DEFAULT_IMAGE_SAVE_FREQUENCY = 100, DEFAULT_HEATMAP_BACKUP_FREQUENCY = 1000, HEATMAP_OFFSET_X = -1152, HEATMAP_OFFSET_Y = -2496;
	private int lastX = 0, lastY = 0;
	private long stepCount = 0;
	private final String HEATMAP_FILENAME = "Heatmap Results/local.heatmap", HEATMAP_IMAGE_FILENAME = "Heatmap Results/Heatmap";
	private int[][] heatmap = new int[2752][1664];
	private final Runnable START = () -> {
		readHeatmap();
		log.debug("World Heatmap started!");
	};
	private final Runnable SHUTDOWN = () -> {
		writeHeatmap();
		log.debug("World Heatmap stopped!");
	};
	private final Runnable SAVE_HEATMAP = () -> {
		writeHeatmap();
	};
	private final Runnable MAKE_IMAGE = () -> {
		log.debug("Saving heatmap image to disk...");
		long startTime = System.nanoTime();
		writeImage();
		log.debug("Finished heatmap image to disk after " + (System.nanoTime() - startTime)/1_000_000 + " ms");
	};
	private final Runnable WRITE_NEW_BACKUP = () -> {
		writeBackupHeatmap();
		log.debug("World Heatmap backup saved!");
	};

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Client client;

	@Inject
	private WorldHeatmapConfig config;

	@Provides
	WorldHeatmapConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WorldHeatmapConfig.class);
	}

	@Override
	protected void startUp()
	{
		executor.execute(START);
	}

	@Override
	protected void shutDown()
	{
		executor.execute(MAKE_IMAGE);
		executor.execute(SHUTDOWN);
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged playerChanged){
		//This occurs when the player teleports, but also when they change their armour and probably a lot of other stuff.
	}

	@Subscribe
	public void onClientTick(ClientTick  clientTick){
		WorldPoint currentCoords = client.getLocalPlayer().getWorldLocation();
		int currentX = currentCoords.getX();
		int currentY = currentCoords.getY();
		int imageSaveFrequency = config.imageAutosaveFrequency();
		int heatmapBackupFrequency = config.heatmapBackupFrequency();
		if (imageSaveFrequency < 100)		//If the user specified the save frequency to be less than 10, we set it to default because the user is retarded, and that's too often.
			imageSaveFrequency = DEFAULT_IMAGE_SAVE_FREQUENCY;
		if (heatmapBackupFrequency < 1000)
			heatmapBackupFrequency = DEFAULT_HEATMAP_BACKUP_FREQUENCY;

		if (currentX < OVERWORLD_MAX_X && currentY < OVERWORLD_MAX_Y && (currentX != lastX || currentY != lastY)) { //If the player is in the overworld and has moved since last game tick
			/* When running, players cover more than one tile per tick, which creates spotty paths.
			 * We fix this by drawing a line between the current coordinates and the previous coordinates,
			 * but we have to be sure that the player indeed ran from point A to point B, differentiating the movement from teleportation.
			 * Since it's too hard to check if the player is actually running, we'll just check if the distance covered since last tick
			 * was less than 5 tiles, and the player hasn't cast a teleport in the last few ticks.
			 */
			boolean largeStep;
			int diagDistance = diagonalDistance(new int[]{ lastX, lastY}, new int[]{ currentX, currentY});
			if (diagDistance < 5) {
				//Increments all the tiles between new position and last position
				for (int[]  tile : getPointsBetween(new int[]{ lastX, lastY}, new int[]{ currentX, currentY})){
					if (tile[0] + HEATMAP_OFFSET_X < heatmap.length && tile[1] + HEATMAP_OFFSET_Y < heatmap[0].length) {
						stepCount++;	//only increments when coordinates are valid
						log.debug("Step count: " + stepCount);
						incrementHeatmap(tile[0], tile[1]);
						if (stepCount % 100 == 0 && imageSaveFrequency != 100)					//Always saves the heatmap matrix to disk every 100 steps
							executor.execute(SAVE_HEATMAP);
						if (stepCount % imageSaveFrequency == 0 && config.autosaveOnOff()) {
							executor.execute(SAVE_HEATMAP);
							executor.execute(MAKE_IMAGE);
						}
						if (stepCount % heatmapBackupFrequency == 0)
							executor.execute(WRITE_NEW_BACKUP);
					}
					else
						log.error("World Heatmap: Coordinates out of bounds for heatmap matrix: (" + tile[0] + ", " + tile[1] + ")");
				}
			}
		}
		//Update last coords
		lastX = currentX;
		lastY = currentY;
	}

	/** Updates the heatmap matrix in memory.
	 * @param x X coordinate of point to increment
	 * @param y Y coordinate of point to increment
	 */
	private void incrementHeatmap(int x, int y) {
		int convertedX = x +  HEATMAP_OFFSET_X;	//These offsets are to reconcile the matrix 'heatmap' being much smaller than the game coordinate domain (which is much larger than the explorable area)
		int convertedY = y + HEATMAP_OFFSET_Y;
		heatmap[convertedX][convertedY]++;
	}

	//Credit to https://www.redblobgames.com/grids/line-drawing.html for where I figured out how to make the following linear interpolation functions

	/**
	 * Returns the list of discrete coordinates on the path between p0 and p1, as an array of int[]s
	 * @param p0
	 * @param p1
	 * @return
	 */
	private int[][] getPointsBetween(int[] p0, int[] p1){
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
	 * Rounds a coordinate to its nearest integers.
	 * @param point The point to round
	 * @return Coordinates
	 */
	private int[] roundPoint(float[] point){
		return new int[]{ Math.round(point[0]), Math.round(point[1])};
	}

	/**
	 * Returns the floating point coordinate that is t-percent of the way between p0 and p1
	 * @param p0
	 * @param p1
	 * @param t
	 * @return
	 */
	private float[] lerp_point(int[] p0, int[] p1, float t){
		return new float[] { lerp(p0[0], p1[0], t), lerp(p0[1], p1[1], t)};
	}

	/**
	 * Returns the number t-percent of the way between start and end.
	 * @param start
	 * @param end
	 * @param t
	 * @return
	 */
	private float lerp(int start, int end, float t) {
		return start + (end-start) * t;
	}

	//Loads heatmap from local storage. If file does not exist, it will create a new one.
	private void readHeatmap() {
		File heatmapFile = new File(HEATMAP_FILENAME);
		if (heatmapFile.exists()){
			try{
				FileInputStream fis = new FileInputStream(HEATMAP_FILENAME);
				InflaterInputStream inflaterIn = new InflaterInputStream(fis);
				DataInputStream dis = new DataInputStream(inflaterIn);
				//Load heatmap into memory
				for (int y = 0; y < heatmap[0].length; y++)
					for (int x = 0; x < heatmap.length; x++)
						if (dis.available() > 0)
							heatmap[x][y] = dis.readInt();
				dis.close();
			}
			catch(IOException e){
				e.printStackTrace();
				log.debug("World Heatmap was not able to load heatmap file");
			}
		}
		else{ //If heatmap file does not exist, sanity-reinitialize heatmap matrix to zeros, then save file
			heatmap = new int[2752][1664];
			writeHeatmap();
		}
	}

	/**
	 * Saves heatmap matrix to 'Heatmap Results' folder.
	 */
	private void writeHeatmap() {
		try{
			FileOutputStream fos = new FileOutputStream(HEATMAP_FILENAME);
			DeflaterOutputStream deflaterOut = new DeflaterOutputStream(fos);
			DataOutputStream dos = new DataOutputStream(deflaterOut);
			for (int y = 0; y < heatmap[0].length; y++)
				for (int x = 0; x < heatmap.length; x++)
					dos.writeInt(heatmap[x][y]);
			dos.close();
		}
		catch(IOException e){
			e.printStackTrace();
			log.error("World Heatmap was not able to save heatmap file");
		}
	}

	/**
	 * Writes a new backup copy of the heatmap matrix to 'Heatmap Results/Backups/' with the current time and date appended to the filename.
	 */
	private void writeBackupHeatmap() {
		try{
			LocalDateTime currentTimeAndDate = java.time.LocalDateTime.now();
			String suffix = currentTimeAndDate.toString();
			FileOutputStream newBackup = new FileOutputStream("Heatmap Results/Backups/local-" + suffix + ".heatmap");
			DeflaterOutputStream deflaterOut = new DeflaterOutputStream(newBackup);
			DataOutputStream dos = new DataOutputStream(deflaterOut);
			for (int y = 0; y < heatmap[0].length; y++)
				for (int x = 0; x < heatmap.length; x++)
					dos.writeInt(heatmap[x][y]);
			dos.close();
		}
		catch(IOException e){
			e.printStackTrace();
			log.error("World Heatmap was not able to save backup heatmap file ");
		}
	}

	/**
	 * Writes a visualization of the heatmap matrix over top of the OSRS world map as a PNG image to the "Heatmap Results" folder.
	 */
	private void writeImage() {
		//First, to normalize the image, we find the maximum value in the heatmap
		int max = 0, maxX = 0, maxY = 0;
		for (int y = 0; y < heatmap[0].length; y++)
			for (int x = 0; x < heatmap.length; x++)
				if (heatmap[x][y] > max) {
					max = heatmap[x][y];
					maxX = x;
					maxY = y;
				}
		log.debug("Maximum steps on a tile is: " + max + " at (" + maxX + ", " + maxY + ")");

		try{
			File worldMapImageFile = new File("osrs_world_map.png");
			BufferedImage worldMapImage = ImageIO.read(worldMapImageFile);
			if (worldMapImage.getWidth() != heatmap.length*3 || worldMapImage.getHeight() != heatmap[0].length*3 ){
				log.error("The file 'osrs_world_map.png' must have dimensions " + heatmap.length*3 + " x " + heatmap[0].length*3);
				return;
			}
			int currRGB = 0;
			float currStepValue = 0, currHue = 0; //a number on the interval [0, 1] that will represent the intensity of the current heatmap pixel
			for (int y = 0; y < heatmap[0].length; y++) {
				for (int x = 0; x < heatmap.length; x++) {
					if (heatmap[x][heatmap[0].length - y - 1] != 0) {								//If the current tile HAS been stepped on (also we invert the y-coords here)
						currStepValue = ((float) heatmap[x][heatmap[0].length - y - 1]) / max;		//Calculate normalized step value
						currHue = (float)(0.333 - (currStepValue * 0.333));							//Assign a hue based on normalized step value (values [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red)
						currRGB = Color.HSBtoRGB(currHue, 1, 1);					//convert HSB to RGB with the calculated Hue, with Saturation and Brightness always 1
						//Now we reassign the new RGB values to the corresponding 9 pixels (we scale by a factor of 3)
						worldMapImage.setRGB(x*3 + 0, y*3 + 0, currRGB);
						worldMapImage.setRGB(x*3 + 0, y*3 + 1, currRGB);
						worldMapImage.setRGB(x*3 + 0, y*3 + 2, currRGB);
						worldMapImage.setRGB(x*3 + 1, y*3 + 0, currRGB);
						worldMapImage.setRGB(x*3 + 1, y*3 + 1, currRGB);
						worldMapImage.setRGB(x*3 + 1, y*3 + 2, currRGB);
						worldMapImage.setRGB(x*3 + 2, y*3 + 0, currRGB);
						worldMapImage.setRGB(x*3 + 2, y*3 + 1, currRGB);
						worldMapImage.setRGB(x*3 + 2, y*3 + 2, currRGB);
					}
				}
			}
			File heatmapImageFile = new File(HEATMAP_IMAGE_FILENAME);
			ImageIO.write(worldMapImage, "png", heatmapImageFile);
		}
		catch (IOException e){
			e.printStackTrace();
			log.error("Exception thrown whilst creating and/or writing image file.");
		}
	}
}
