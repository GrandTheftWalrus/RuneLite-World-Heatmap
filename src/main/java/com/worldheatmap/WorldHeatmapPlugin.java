package com.worldheatmap;

import com.google.inject.Provides;
import java.awt.Point;
import java.awt.image.RenderedImage;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nullable;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.event.IIOWriteWarningListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.*;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
@PluginDescriptor(
	name = "World Heatmap"
)
public class WorldHeatmapPlugin extends Plugin
{
	private static final int TYPE_A_HEATMAP_AUTOSAVE_FREQUENCY = 100;
	private static final int TYPE_B_HEATMAP_AUTOSAVE_FREQUENCY = 500;
	private int lastX = 0;
	private int lastY = 0;
	private int lastStepCountA = 0;
	private int lastStepCountB = 0;
	protected long mostRecentLocalUserID;
	private boolean shouldLoadHeatmaps;
	protected final File WORLDHEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap");
	protected final File HEATMAP_FILES_DIR = Paths.get(WORLDHEATMAP_DIR.toString(), "Heatmap Files").toFile();
	protected final File HEATMAP_IMAGE_DIR = Paths.get(WORLDHEATMAP_DIR.toString(), "Heatmap Images").toFile();
	protected HeatmapNew heatmapTypeA, heatmapTypeB;
	private NavigationButton toolbarButton;
	private WorldHeatmapPanel panel;
	protected String mostRecentLocalUserName;

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
	WorldHeatmapConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WorldHeatmapConfig.class);
	}

	protected void loadHeatmapFiles()
	{
		log.debug("Loading heatmaps under user ID " + mostRecentLocalUserID + "...");
		File filepathTypeAUsername = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName + "_TypeA.heatmap");
		File filepathTypeAUserID = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserID + "_TypeA.heatmap");
		File heatmapTypeBUsernameFile = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName + "_TypeB.heatmap");
		File filepathTypeBUserID = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserID + "_TypeB.heatmap");

		// Load heatmap Type A
		// To fix/deal with how previous versions of the plugin used player names
		// (which can change) instead of player IDs, we also do the following check
		if (filepathTypeAUserID.exists())
		{
			heatmapTypeA = readHeatmapFile(filepathTypeAUserID);
		}
		else if (filepathTypeAUsername.exists())
		{
			log.info("File '" + filepathTypeAUserID + "' did not exist. Checking for alternative file '" + filepathTypeAUsername + "'...");
			heatmapTypeA = readHeatmapFile(filepathTypeAUsername);
		}
		else
		{
			log.info("File '" + filepathTypeAUserID + "' did not exist. Creating a new heatmap...");
			heatmapTypeA = new HeatmapNew(mostRecentLocalUserID);
			heatmapTypeA.setHeatmapType(HeatmapNew.HeatmapType.TYPE_A);
		}

		// Load heatmap type B
		if (filepathTypeBUserID.exists())
		{
			heatmapTypeB = readHeatmapFile(filepathTypeBUserID);
		}
		else if (heatmapTypeBUsernameFile.exists())
		{
			log.info("File '" + filepathTypeBUserID + "' did not exist. Checking for alternative file '" + heatmapTypeBUsernameFile + "'...");
			heatmapTypeB = readHeatmapFile(heatmapTypeBUsernameFile);
		}
		else
		{
			log.info("File '" + filepathTypeBUserID + "' did not exist. Creating a new heatmap...");
			heatmapTypeB = new HeatmapNew(mostRecentLocalUserID);
			heatmapTypeB.setHeatmapType(HeatmapNew.HeatmapType.TYPE_B);
		}
		panel.writeTypeAHeatmapImageButton.setEnabled(true);
		panel.writeTypeBHeatmapImageButton.setEnabled(true);
		panel.clearTypeAHeatmapButton.setEnabled(true);
		panel.clearTypeBHeatmapButton.setEnabled(true);
		panel.writeTypeAcsvButton.setEnabled(true);
		panel.writeTypeBcsvButton.setEnabled(true);
	}

	@Override
	protected void startUp()
	{
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
	protected void shutDown()
	{
		if (loadHeatmapsFuture != null && loadHeatmapsFuture.isDone())
		{
			String filepathA = Paths.get(mostRecentLocalUserID + "_TypeA.heatmap").toString();
			executor.execute(() -> writeHeatmapFile(heatmapTypeA, new File(filepathA)));
			String filepathB = Paths.get(mostRecentLocalUserID + "_TypeB.heatmap").toString();
			executor.execute(() -> writeHeatmapFile(heatmapTypeB, new File(filepathB)));
		}
		clientToolbar.removeNavigation(toolbarButton);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGING_IN)
		{
			shouldLoadHeatmaps = true;
			loadHeatmapsFuture = null;
		}

		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN && loadHeatmapsFuture != null && loadHeatmapsFuture.isDone())
		{
			String filepathA = Paths.get(mostRecentLocalUserID + "_TypeA.heatmap").toString();
			executor.execute(() -> writeHeatmapFile(heatmapTypeA, new File(filepathA)));
			String filepathB = Paths.get(mostRecentLocalUserID + "_TypeB.heatmap").toString();
			executor.execute(() -> writeHeatmapFile(heatmapTypeB, new File(filepathB)));
			loadHeatmapsFuture = null;
		}
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			panel.writeTypeAHeatmapImageButton.setEnabled(false);
			panel.writeTypeBHeatmapImageButton.setEnabled(false);
			panel.clearTypeAHeatmapButton.setEnabled(false);
			panel.clearTypeBHeatmapButton.setEnabled(false);
			panel.writeTypeAcsvButton.setEnabled(false);
			panel.writeTypeBcsvButton.setEnabled(false);
		}
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			panel.writeTypeAHeatmapImageButton.setEnabled(true);
			panel.writeTypeBHeatmapImageButton.setEnabled(true);
			panel.clearTypeAHeatmapButton.setEnabled(true);
			panel.clearTypeBHeatmapButton.setEnabled(true);
			panel.writeTypeAcsvButton.setEnabled(true);
			panel.writeTypeBcsvButton.setEnabled(true);
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (client.getAccountHash() == -1)
		{
			return;
		}
		if (mostRecentLocalUserID != client.getAccountHash())
		{
			mostRecentLocalUserName = client.getLocalPlayer().getName();
			mostRecentLocalUserID = client.getAccountHash();
		}
		if (panel.mostRecentLocalUserID != mostRecentLocalUserID)
		{
			SwingUtilities.invokeLater(panel::updatePlayerID);
		}

		if (shouldLoadHeatmaps && client.getGameState().equals(GameState.LOGGED_IN))
		{
			shouldLoadHeatmaps = false;
			loadHeatmapsFuture = executor.submit(this::loadHeatmapFiles);
		}
		// The following code requires the heatmap files to have been loaded
		if (loadHeatmapsFuture != null && !loadHeatmapsFuture.isDone())
		{
			return;
		}

		WorldPoint currentCoords = client.getLocalPlayer().getWorldLocation();
		int currentX = currentCoords.getX();
		int currentY = currentCoords.getY();
		boolean playerMovedSinceLastTick = (currentX != lastX || currentY != lastY);

		/* When running, players cover more than one tile per tick, which creates spotty paths.
		 * We fix this by drawing a line between the current coordinates and the previous coordinates,
		 * but we have to be sure that the player indeed ran from point A to point B, differentiating the movement from teleportation.
		 * Since it's too hard to check if the player is actually running, we'll just check if the distance covered since last tick
		 * was less than 5 tiles
		 */
		int diagDistance = diagonalDistance(new Point(lastX, lastY), new Point(currentX, currentY));
		boolean largeStep = diagDistance >= 5;
		if (!largeStep)
		{
			// Gets all the tiles between last position and new position
			for (Point tile : getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY)))
			{
				//If the player has moved since last game tick, increment Heatmap Type A
				if (playerMovedSinceLastTick)
				{
					heatmapTypeA.increment(tile.x, tile.y);
					File heatmapFile = Paths.get(mostRecentLocalUserID + "_TypeA.heatmap").toFile();
					File heatmapImageFileName = new File(mostRecentLocalUserID + "_TypeA.tif");
					File heatmapBackupFile = Paths.get("Backups", mostRecentLocalUserID + "-" + java.time.LocalDateTime.now() + "_TypeA.heatmap").toFile();
					autosaveRoutine(heatmapTypeA, heatmapFile, heatmapImageFileName, config.typeAImageAutosaveFrequency(), TYPE_A_HEATMAP_AUTOSAVE_FREQUENCY, config.typeAImageAutosaveOnOff());
					backupRoutine(heatmapTypeA, heatmapBackupFile, config.typeAHeatmapBackupFrequency());
				}

				// Increment Type B even if player hasn't moved
				heatmapTypeB.increment(tile.x, tile.y);
				File heatmapFile = Paths.get(mostRecentLocalUserID + "_TypeB.heatmap").toFile();
				File heatmapImageFileName = new File(mostRecentLocalUserID + "_TypeB.tif");
				File heatmapBackupFile = Paths.get("Backups", mostRecentLocalUserID + "-" + java.time.LocalDateTime.now() + "_TypeB.heatmap").toFile();
				autosaveRoutine(heatmapTypeB, heatmapFile, heatmapImageFileName, config.typeBImageAutosaveFrequency(), TYPE_B_HEATMAP_AUTOSAVE_FREQUENCY, config.typeBImageAutosaveOnOff());
				backupRoutine(heatmapTypeB, heatmapBackupFile, config.typeBHeatmapBackupFrequency());
			}
		}

		// Update panel step counter
		if (heatmapTypeA.getStepCount() != lastStepCountA || heatmapTypeB.getStepCount() != lastStepCountB)
		{
			SwingUtilities.invokeLater(panel::updateCounts);
		}

		// Update last coords
		lastX = currentX;
		lastY = currentY;
		lastStepCountA = heatmapTypeA.getStepCount();
		lastStepCountB = heatmapTypeB.getStepCount();
	}

	/**
	 * Autosave the heatmap file/write the heatmap image if it is the correct time to do each respective thing according to their frequencies
	 *
	 * @param heatmap                  The HeatmapNew object
	 * @param heatmapFile              .heatmap file to save
	 * @param heatmapImageFile         Heatmap image file
	 * @param imageAutosaveFrequency   Image autosave frequency (in steps)
	 * @param heatmapAutosaveFrequency Heatmap autosave frequency (in steps)
	 * @param isImageAutosaveOn        Is autosaving for the image turned on
	 */
	private void autosaveRoutine(HeatmapNew heatmap, File heatmapFile, File heatmapImageFile, int imageAutosaveFrequency, int heatmapAutosaveFrequency, boolean isImageAutosaveOn)
	{
		// If it's time to autosave the image, then save heatmap file (so file is in sync with image) and write image file
		if (isImageAutosaveOn && heatmap.getStepCount() % imageAutosaveFrequency == 0)
		{
			executor.execute(() -> writeHeatmapFile(heatmap, heatmapFile));
			executor.execute(() -> writeHeatmapImage(heatmap, heatmapImageFile));
		}

		// if it wasn't the time to autosave an image (and therefore save the .heatmap), then check if it's time to autosave just the .heatmap file
		else if (heatmap.getStepCount() % heatmapAutosaveFrequency == 0)
		{
			executor.execute(() -> writeHeatmapFile(heatmap, heatmapFile));
		}
	}

	private void backupRoutine(HeatmapNew heatmap, File heatmapBackupFile, int backupFrequency)
	{
		if (heatmap.getStepCount() % backupFrequency == 0)
		{
			executor.execute(() -> writeHeatmapFile(heatmap, heatmapBackupFile));
		}
	}

	// Credit to https:// www.redblobgames.com/grids/line-drawing.html for where I figured out how to make the following linear interpolation functions

	/**
	 * Returns the list of discrete coordinates on the path between p0 and p1, as an array of Points
	 *
	 * @param p0 Point A
	 * @param p1 Point B
	 * @return Array of coordinates
	 */
	private Point[] getPointsBetween(Point p0, Point p1)
	{
		if (p0.equals(p1))
		{
			return new Point[]{p1};
		}
		int N = diagonalDistance(p0, p1);
		Point[] points = new Point[N];
		for (int step = 1; step <= N; step++)
		{
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
	private int diagonalDistance(Point p0, Point p1)
	{
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
	private Point roundPoint(float[] point)
	{
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
	private float[] lerp_point(Point p0, Point p1, float t)
	{
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
	private float lerp(int p0, int p1, float t)
	{
		return p0 + (p1 - p0) * t;
	}

	// Loads heatmap from local storage. If file does not exist, or an error occurs, it will return null.
	private HeatmapNew readHeatmapFile(File heatmapFile)
	{
		log.info("Loading heatmap file '" + heatmapFile.getName() + "'");
		if (!heatmapFile.exists())
		{
			// Return new blank heatmap if specified file doesn't exist
			log.error("World Heatmap was not able to load Worldheatmap file " + heatmapFile.getName() + " because it does not exist.");
			return null;
		}
		// Detect whether the .heatmap file is the old style (serialized Heatmap, rather than a zipped .CSV file)
		// And if it is, then convert it to the new style
		try (FileInputStream fis = new FileInputStream(heatmapFile);
			 InflaterInputStream iis = new InflaterInputStream(fis);
			 ObjectInputStream ois = new ObjectInputStream(iis))
		{
			Object heatmap = ois.readObject();
			if (heatmap instanceof Heatmap)
			{
				log.info("Attempting to convert old-style heatmap file to new style...");
				long startTime = System.nanoTime();
				HeatmapNew result = HeatmapNew.convertOldHeatmapToNew((Heatmap) heatmap, mostRecentLocalUserID);
				log.info("Finished converting old-style heatmap to new style in " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
				return result;
			}
		}
		catch (Exception e)
		{
			// If reached here, then the file was not of the older type.
		}
		// Test if it is of the newer type, or something else altogether.
		try (FileInputStream fis = new FileInputStream(heatmapFile))
		{
			ZipInputStream zis = new ZipInputStream(fis);
			InputStreamReader isr = new InputStreamReader(zis, StandardCharsets.UTF_8);
			BufferedReader reader = new BufferedReader(isr);
			zis.getNextEntry();
			String[] fieldNames = reader.readLine().split(",");
			String[] fieldValues = reader.readLine().split(",");
			log.debug("Field Variables detectarino'd: " + Arrays.toString(fieldNames));
			long userID = (fieldValues[0].isEmpty() ? -1 : Long.parseLong(fieldValues[0]));
			HeatmapNew.HeatmapType heatmapType = HeatmapNew.HeatmapType.valueOf(fieldValues[2]);
			int stepCount = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[3]));
			int maxVal = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[4]));
			int maxValX = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[5]));
			int maxValY = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[6]));
			int minVal = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[7]));
			int minValX = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[8]));
			int minValY = (fieldValues[0].isEmpty() ? -1 : Integer.parseInt(fieldValues[9]));

			HeatmapNew heatmapNew;
			if (userID != -1)
			{
				heatmapNew = new HeatmapNew(userID);
			}
			else
			{
				heatmapNew = new HeatmapNew();
			}
			log.debug("HEATMAP TYPE: " + heatmapType);
			heatmapNew.maxVal = new int[]{maxVal, maxValX, maxValY};
			heatmapNew.minVal = new int[]{minVal, minValX, minValY};
			heatmapNew.stepCount = stepCount;
			heatmapNew.setHeatmapType(heatmapType);

			// Read the tile values
			final int[] errorCount = {0}; // Number of parsing errors occurred during read
			reader.lines().forEach(s -> {
				String[] tile = s.split(",");
				try
				{
					heatmapNew.set(Integer.parseInt(tile[0]), Integer.parseInt(tile[1]), Integer.parseInt(tile[2]));
				}
				catch (NumberFormatException e)
				{
					errorCount[0]++;
				}
			});
			if (errorCount[0] != 0)
			{
				log.error(errorCount[0] + " errors occurred during heatmap file read.");
			}
			return heatmapNew;
		}
		catch (FileNotFoundException e)
		{
			log.error("Was not able to read heatmap file " + heatmapFile.getName() + " because it doesn't exist");
		}
		catch (IOException e)
		{
			log.error("Was not able to read heatmap file " + heatmapFile.getName() + " for some reason (IOException)");
		}
		return null;
	}

	/**
	 * Saves heatmap to 'Heatmap Files' folder.
	 */
	protected void writeHeatmapFile(HeatmapNew heatmap, File fileOut)
	{
		if (!fileOut.isAbsolute())
		{
			fileOut = new File(HEATMAP_FILES_DIR, fileOut.toString());
		}
		log.info("Saving " + fileOut + " heatmap file to disk...");
		long startTime = System.nanoTime();
		// Make the directory path if it doesn't exist
		File file = fileOut;
		if (!Files.exists(Paths.get(file.getParent())))
		{
			new File(file.getParent()).mkdirs();
		}

		// Write the heatmap file
		try (FileOutputStream fos = new FileOutputStream(fileOut);
			 ZipOutputStream zos = new ZipOutputStream(fos);
			 BufferedOutputStream bos = new BufferedOutputStream(zos);
			 OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8))
		{
			ZipEntry ze = new ZipEntry("heatmap.csv");
			zos.putNextEntry(ze);
			// Write them field variables
			osw.write("userID,heatmapVersion,heatmapType,stepCount,maxVal,maxValX,maxValY,minVal,minValX,minValY\n");
			osw.write(mostRecentLocalUserID +
				"," + HeatmapNew.heatmapVersion +
				"," + heatmap.getHeatmapType() +
				"," + heatmap.stepCount +
				"," + heatmap.maxVal[0] +
				"," + heatmap.maxVal[1] +
				"," + heatmap.maxVal[2] +
				"," + heatmap.minVal[0] +
				"," + heatmap.minVal[1] +
				"," + heatmap.minVal[2] + "\n");
			// Write the tile values
			for (Map.Entry<Point, Integer> e : heatmap.getEntrySet())
			{
				int x = e.getKey().x;
				int y = e.getKey().y;
				int stepVal = e.getValue();
				osw.write(x + "," + y + "," + stepVal + "\n");
			}
		}
		catch (IOException e)
		{
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
	protected void writeCSVFile(File csvURI, HeatmapNew heatmap)
	{
		try (PrintWriter pw = new PrintWriter(csvURI))
		{
			for (Map.Entry<Point, Integer> e : heatmap.getEntrySet())
			{
				int x = e.getKey().x;
				int y = e.getKey().y;
				int stepVal = e.getValue();
				pw.write(x + "," + y + "," + stepVal + "\n");
			}
		}
		catch (FileNotFoundException e)
		{
			log.error("World Heatmap was not able to save heatmap CSV file");
		}
	}

	protected void writeHeatmapImage(HeatmapNew heatmap, File imageFileOut)
	{
		log.info("Saving " + imageFileOut + " image to disk...");
		long startTime = System.nanoTime();
		if (!imageFileOut.getName().endsWith(".tif"))
		{
			imageFileOut = new File(imageFileOut.getName() + ".tif");
		}

		if (!imageFileOut.isAbsolute())
		{
			imageFileOut = new File(HEATMAP_IMAGE_DIR, imageFileOut.getName());
		}

		float heatmapTransparency = (float) config.heatmapAlpha();
		if (heatmapTransparency < 0)
		{
			heatmapTransparency = 0;
		}
		else if (heatmapTransparency > 1)
		{
			heatmapTransparency = 1;
		}

		// Prepare the image reader
		try (InputStream inputStream = new URL("https://raw.githubusercontent.com/GrandTheftWalrus/gtw-runelite-stuff/main/osrs_world_map.png").openStream();
			 ImageInputStream worldMapImageInputStream = ImageIO.createImageInputStream(Objects.requireNonNull(inputStream, "Resource osrs_world_map.png didn't exist")))
		{
			ImageReader reader = ImageIO.getImageReadersByFormatName("PNG").next();
			reader.setInput(worldMapImageInputStream, true);

			// Prepare the image writer
			try (FileOutputStream fos = new FileOutputStream(imageFileOut);
				 BufferedOutputStream bos = new BufferedOutputStream(fos);
				 ImageOutputStream ios = ImageIO.createImageOutputStream(bos))
			{
				ImageWriter writer = ImageIO.getImageWritersByFormatName("tif").next();
				writer.setOutput(ios);
				final int tileWidth = 8800;
				final int tileHeight = 496;
				final int N = reader.getHeight(0) / tileHeight;

				// Make progress listener majigger
				HeatmapProgressListener progressListener = new HeatmapProgressListener();
				progressListener.setHeatmapType(heatmap.getHeatmapType());
				writer.addIIOWriteProgressListener(progressListener);

				// Prepare writing parameters
				ImageWriteParam writeParam = writer.getDefaultWriteParam();
				writeParam.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
				writeParam.setTiling(tileWidth, tileHeight, 0, 0);
				writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				writeParam.setCompressionType("Deflate");
				writeParam.setCompressionQuality(0);

				// Write heatmap image
				RenderedImage heatmapImage = new HeatmapImage(heatmap, reader, N, heatmapTransparency);
				writer.write(null, new IIOImage(heatmapImage, null, null), writeParam);
				reader.dispose();
				writer.dispose();
			}
			log.info("Finished writing " + imageFileOut + " image to disk after " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
		}
		catch (OutOfMemoryError e)
		{
			log.error("OutOfMemoryError thrown whilst creating and/or writing image file. " +
				"If you're not able to fix the issue by lowering the memory usage settings " +
				"(if they exist in this version of the plugin) then perhaps consider submitting" +
				"an Issue on the GitHub");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			log.error("Exception thrown whilst creating and/or writing image file: " + e.getMessage());
		}
	}

	/**
	 * Combines heatmaps, adding together each tile value
	 *
	 * @param outputFile      Output .heatmap file
	 * @param outputImageFile Output .tif file
	 * @param heatmapFiles    Input .heatmap files
	 * @return True if completed successfully, else false
	 */
	public boolean combineHeatmaps(File outputFile, @Nullable File outputImageFile, File... heatmapFiles)
	{
		HeatmapNew outputHeatmap = new HeatmapNew(mostRecentLocalUserID);
		for (File inputHeatmapFile : heatmapFiles)
		{
			if (!inputHeatmapFile.exists())
			{
				log.error("Input heatmap file " + inputHeatmapFile.getAbsolutePath() + " doesn't exist");
				return false;
			}
			HeatmapNew inputHeatmap = readHeatmapFile(new File(inputHeatmapFile.getAbsolutePath()));
			if (inputHeatmap == null)
			{
				log.error("Unable to add heatmap file " + inputHeatmapFile.getPath() + " to " + outputFile.getName() + " because it was unable to be read");
				return false;
			}
			log.info("Adding heatmap file " + inputHeatmapFile + " to " + outputFile.getName() + "...");
			for (Map.Entry<Point, Integer> e : inputHeatmap.getEntrySet())
			{
				outputHeatmap.set(e.getKey().x, e.getKey().y, e.getValue());
			}
		}
		writeHeatmapFile(outputHeatmap, outputFile);
		if (outputImageFile != null)
		{
			writeHeatmapImage(outputHeatmap, outputImageFile);
		}
		return true;
	}

	private class HeatmapProgressListener implements IIOWriteProgressListener
	{
		HeatmapNew.HeatmapType heatmapType = HeatmapNew.HeatmapType.UNKNOWN;

		public void setHeatmapType(HeatmapNew.HeatmapType type)
		{
			this.heatmapType = type;
		}

		@Override
		public void imageStarted(ImageWriter source, int imageIndex)
		{
			if (heatmapType == HeatmapNew.HeatmapType.TYPE_A)
			{
				panel.writeTypeAHeatmapImageButton.setEnabled(false);
				panel.clearTypeAHeatmapButton.setEnabled(false);
				panel.writeTypeAcsvButton.setEnabled(false);
				panel.writeTypeAHeatmapImageButton.setText("Writing... 0%");
			}
			else if (heatmapType == HeatmapNew.HeatmapType.TYPE_B)
			{
				panel.writeTypeBHeatmapImageButton.setEnabled(false);
				panel.clearTypeBHeatmapButton.setEnabled(false);
				panel.writeTypeBcsvButton.setEnabled(false);
				panel.writeTypeBHeatmapImageButton.setText("Writing... 0%");
			}

		}

		@Override
		public void imageProgress(ImageWriter source, float percentageDone)
		{
			if (heatmapType == HeatmapNew.HeatmapType.TYPE_A)
			{
				panel.writeTypeAHeatmapImageButton.setText("Writing... " + percentageDone + "%");
			}
			else if (heatmapType == HeatmapNew.HeatmapType.TYPE_B)
			{
				panel.writeTypeBHeatmapImageButton.setText("Writing... " + percentageDone + "%");
			}
		}

		@Override
		public void imageComplete(ImageWriter source)
		{
			if (heatmapType == HeatmapNew.HeatmapType.TYPE_A)
			{
				panel.writeTypeAHeatmapImageButton.setEnabled(true);
				panel.clearTypeAHeatmapButton.setEnabled(true);
				panel.writeTypeAcsvButton.setEnabled(true);
				panel.writeTypeAHeatmapImageButton.setText("Write Heatmap Image");
			}
			else if (heatmapType == HeatmapNew.HeatmapType.TYPE_B)
			{
				panel.writeTypeBHeatmapImageButton.setEnabled(true);
				panel.clearTypeBHeatmapButton.setEnabled(true);
				panel.writeTypeBcsvButton.setEnabled(true);
				panel.writeTypeBHeatmapImageButton.setText("Write Heatmap Image");
			}
		}

		@Override
		public void thumbnailStarted(ImageWriter source, int imageIndex, int thumbnailIndex)
		{
		}

		@Override
		public void thumbnailProgress(ImageWriter source, float percentageDone)
		{
		}

		@Override
		public void thumbnailComplete(ImageWriter source)
		{
		}

		@Override
		public void writeAborted(ImageWriter source)
		{
		}
	}
}
