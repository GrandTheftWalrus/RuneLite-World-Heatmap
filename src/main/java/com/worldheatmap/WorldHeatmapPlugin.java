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
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import joptsimple.internal.Strings;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NpcID;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
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
import net.runelite.api.Constants;
import net.runelite.client.game.ItemManager;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
@PluginDescriptor(
	name = "World Heatmap"
)
public class WorldHeatmapPlugin extends Plugin
{
	private static final int HEATMAP_AUTOSAVE_FREQUENCY = 500; // How often to autosave the .heatmap file (in ticks)
	private static final int IMAGE_AUTOSAVE_FREQUENCY = 500; // How often to autosave the heatmap image (in ticks)
	private int lastX = 0;
	private int lastY = 0;
	protected long mostRecentLocalUserID;
	private boolean shouldLoadHeatmaps;
	protected final File WORLDHEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap");
	protected final File HEATMAP_FILES_DIR = Paths.get(WORLDHEATMAP_DIR.toString(), "Heatmap Files").toFile();
	protected final File HEATMAP_IMAGE_DIR = Paths.get(WORLDHEATMAP_DIR.toString(), "Heatmap Images").toFile();
	protected Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = new HashMap<>();
	private NavigationButton toolbarButton;
	private WorldHeatmapPanel panel;
	private ArrayList<Integer> randomEventNPCIDs = new ArrayList<>(Arrays.asList(NpcID.BEE_KEEPER_6747,
		NpcID.CAPT_ARNAV,
		NpcID.DRUNKEN_DWARF,
		NpcID.FLIPPA_6744,
		NpcID.GILES,
		NpcID.GILES_5441,
		NpcID.MILES,
		NpcID.MILES_5440,
		NpcID.NILES,
		NpcID.NILES_5439,
		NpcID.PILLORY_GUARD,
		NpcID.POSTIE_PETE_6738,
		NpcID.RICK_TURPENTINE,
		NpcID.RICK_TURPENTINE_376,
		NpcID.SERGEANT_DAMIEN_6743,
		NpcID.FREAKY_FORESTER_6748,
		NpcID.FROG_5429,
		NpcID.GENIE,
		NpcID.GENIE_327,
		NpcID.DR_JEKYLL,
		NpcID.DR_JEKYLL_314,
		NpcID.EVIL_BOB,
		NpcID.EVIL_BOB_6754,
		NpcID.LEO_6746,
		NpcID.MYSTERIOUS_OLD_MAN_6751,
		NpcID.MYSTERIOUS_OLD_MAN_6750,
		NpcID.MYSTERIOUS_OLD_MAN_6752,
		NpcID.MYSTERIOUS_OLD_MAN_6753,
		NpcID.QUIZ_MASTER_6755,
		NpcID.DUNCE_6749,
		NpcID.SANDWICH_LADY,
		NpcID.STRANGE_PLANT));
	Map<Integer, Instant> timeLastSeenBobTheCatPerWorld = new HashMap<>();
	@Inject
	ItemManager itemManager;
	private final int[] previousXP = new int[Skill.values().length];
	protected String mostRecentLocalUserName;
	private Future loadHeatmapsFuture;

	@Inject
	private Client client;

	@Inject
	protected ScheduledExecutorService worldHeatmapPluginExecutor;

	@Inject
	private WorldHeatmapConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Provides
	WorldHeatmapConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WorldHeatmapConfig.class);
	}

	@SneakyThrows
	protected void loadHeatmapFiles()
	{
		log.debug("Loading heatmaps under user ID " + mostRecentLocalUserID + "...");

		// Load heatmap Type A
		// To fix/deal with how previous versions of the plugin used player names
		// (which can change) instead of player IDs, we also do the following check
		File filepathUserID = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserID + ".heatmaps"); //NOTE: changed from .heatmap to .heatmaps
		if (filepathUserID.exists())
		{
			// Load all heatmaps from the file
			heatmaps = readHeatmapsFile(filepathUserID);
		}
		else // If the file doesn't exist, then check for the old username files
		{
			File filepathTypeAUsername = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName + "_TypeA.heatmap");
			File filepathTypeBUsername = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName + "_TypeB.heatmap");
			log.info("File '" + filepathUserID + "' did not exist. Checking for alternative files '" + filepathTypeAUsername + "' and '" + filepathTypeBUsername + "'...");
			if (filepathTypeAUsername.exists())
			{
				HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmapHashMapTemp = readHeatmapsFile(filepathTypeAUsername);
				// If it was a username file then there should only be one heatmap, and it would be of type UNKNOWN
				System.out.println("Type A as unknown:" + heatmapHashMapTemp.get(HeatmapNew.HeatmapType.UNKNOWN).getTotalValue());
				heatmapHashMapTemp.put(HeatmapNew.HeatmapType.TYPE_A, heatmapHashMapTemp.get(HeatmapNew.HeatmapType.UNKNOWN));
				heatmapHashMapTemp.get(HeatmapNew.HeatmapType.TYPE_A).setHeatmapType(HeatmapNew.HeatmapType.TYPE_A);
				// Put it in the heatmaps hashmap
				heatmaps.put(HeatmapNew.HeatmapType.TYPE_A, heatmapHashMapTemp.get(HeatmapNew.HeatmapType.TYPE_A));
			}
			if (filepathTypeBUsername.exists())
			{
				HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmapHashMapTemp = readHeatmapsFile(filepathTypeBUsername);
				// If it was a username file then there should only be one heatmap, and it would be of type UNKNOWN
				System.out.println("Type B as unknown:" + heatmapHashMapTemp.get(HeatmapNew.HeatmapType.UNKNOWN).getTotalValue());
				heatmapHashMapTemp.put(HeatmapNew.HeatmapType.TYPE_B, heatmapHashMapTemp.get(HeatmapNew.HeatmapType.UNKNOWN));
				heatmapHashMapTemp.get(HeatmapNew.HeatmapType.TYPE_B).setHeatmapType(HeatmapNew.HeatmapType.TYPE_B);
				// Put it in the heatmaps hashmap
				heatmaps.put(HeatmapNew.HeatmapType.TYPE_B, heatmapHashMapTemp.get(HeatmapNew.HeatmapType.TYPE_B));
			}
		}

		initializeMissingHeatmaps(heatmaps, mostRecentLocalUserID);
		panel.setEnabledHeatmapButtons(true);
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
		panel.setEnabledHeatmapButtons(false);
	}

	@Override
	protected void shutDown()
	{
		if (loadHeatmapsFuture != null && loadHeatmapsFuture.isDone())
		{
			String filepath = Paths.get(mostRecentLocalUserID + ".heatmaps").toString();
			worldHeatmapPluginExecutor.execute(() -> writeHeatmapsFile(heatmaps, new File(filepath)));
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
			//TODO: Why not schedule the heatmaps for loading here instead of waiting for the first game tick?
			//client id is known at this point.
		}

		// If you're at the login screen and heatmaps have been loaded (implying that you've been logged in, but now you're logged out)
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN && loadHeatmapsFuture != null && loadHeatmapsFuture.isDone())
		{
			String filepath = Paths.get(mostRecentLocalUserID + ".heatmaps").toString();
			worldHeatmapPluginExecutor.execute(() -> writeHeatmapsFile(heatmaps, new File(filepath)));
			loadHeatmapsFuture = null;
		}
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			// Disable write heatmap image buttons
			panel.setEnabledHeatmapButtons(false);
		}
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			// Enable write heatmap image buttons
			panel.setEnabledHeatmapButtons(true);
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		if (client.getAccountHash() == -1)
		{
			return;
		}
		// If the player has changed, update the player ID
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
			// Populated previousXP array with current XP values
			for (Skill skill : Skill.values())
			{
				previousXP[skill.ordinal()] = client.getSkillExperience(skill);
			}

			// Schedule the loading of the heatmap files
			shouldLoadHeatmaps = false;
			loadHeatmapsFuture = worldHeatmapPluginExecutor.submit(this::loadHeatmapFiles);
		}
		// The following code requires the heatmap files to have been loaded
		if (loadHeatmapsFuture != null && !loadHeatmapsFuture.isDone())
		{
			return;
		}

		// Increment game time ticks of each heatmap
		for (HeatmapNew.HeatmapType type : heatmaps.keySet())
		{
			heatmaps.get(type).incrementGameTimeTicks();
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
		if (diagDistance <= 3)
		{
			// Gets all the tiles between last position and new position
			for (Point tile : getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY)))
			{
				// TYPE_A
				if (playerMovedSinceLastTick)
				{
					heatmaps.get(HeatmapNew.HeatmapType.TYPE_A).increment(tile.x, tile.y);
				}

				// TYPE_B
				heatmaps.get(HeatmapNew.HeatmapType.TYPE_B).increment(tile.x, tile.y);
			}
		}

		// TELEPORT_PATHS
		if (diagDistance > 15 && lastY < Constants.OVERWORLD_MAX_Y && currentY < Constants.OVERWORLD_MAX_Y) //we don't draw lines between the overworld and caves etc.
		{
			for (Point tile : getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY)))
			{
				if (heatmaps.get(HeatmapNew.HeatmapType.TELEPORT_PATHS) != null){
					heatmaps.get(HeatmapNew.HeatmapType.TELEPORT_PATHS).increment(tile.x, tile.y);
				}
				else {
					log.error("TELEPORT_PATHS heatmap is null");
				}
			}
		}

		// TELEPORTED_TO and TELEPORTED_FROM
		if (diagDistance > 15)
		{
			heatmaps.get(HeatmapNew.HeatmapType.TELEPORTED_TO).increment(currentX, currentY);
			heatmaps.get(HeatmapNew.HeatmapType.TELEPORTED_FROM).increment(lastX, lastY);
		}

		// Backup/autosave routines
		File heatmapsFile = Paths.get(mostRecentLocalUserID + ".heatmaps").toFile();
		File[] heatmapImageFileNames = new File[]{new File(mostRecentLocalUserID + "_" + HeatmapNew.HeatmapType.TYPE_A + ".tif"), new File(mostRecentLocalUserID + "_" + HeatmapNew.HeatmapType.TYPE_B + ".tif")};
		File heatmapsBackupFile = Paths.get("Backups", mostRecentLocalUserID + "-" + java.time.LocalDateTime.now() + ".heatmaps").toFile();
		autosaveRoutine(heatmaps, heatmapsFile, heatmapImageFileNames);
		backupRoutine(heatmaps, heatmapsBackupFile, config.HeatmapBackupFrequency());

		// Update panel step counter
		SwingUtilities.invokeLater(panel::updateCounts);

		// Update last coords
		lastX = currentX;
		lastY = currentY;
	}

	@Subscribe
	public void onActorDeath(ActorDeath actorDeath){
		if (actorDeath.getActor() instanceof Player){
			Player actor = (Player) actorDeath.getActor();
			if (actor.getId() == client.getLocalPlayer().getId()){
				// DEATHS
				heatmaps.get(HeatmapNew.HeatmapType.DEATHS).increment(actor.getWorldLocation().getX(), actor.getWorldLocation().getY());
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage){
		if (chatMessage.getType() == ChatMessageType.PUBLICCHAT && chatMessage.getName().equals(client.getLocalPlayer().getName())){
			// PLACES_SPOKEN_AT
			heatmaps.get(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT).increment(client.getLocalPlayer().getWorldLocation().getX(), client.getLocalPlayer().getWorldLocation().getY());
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged){
		// NOTE: this happens 23 times when you log in, and at such time, the heatmaps haven't been loaded in, so you can't call .get() on them
		// Get difference between previous and current XP
		int skillIndex = statChanged.getSkill().ordinal();
		int xpDifference = client.getSkillExperience(statChanged.getSkill()) - previousXP[skillIndex];

		// Update previous XP
		previousXP[skillIndex] = client.getSkillExperience(statChanged.getSkill());

		// XP_GAINED
		if (heatmaps.get(HeatmapNew.HeatmapType.XP_GAINED) != null){
			heatmaps.get(HeatmapNew.HeatmapType.XP_GAINED).increment(client.getLocalPlayer().getWorldLocation().getX(), client.getLocalPlayer().getWorldLocation().getY(), xpDifference);
		}
	}

	@Subscribe
	public void onNpcSpawned(final NpcSpawned npcSpawned){
		if (loadHeatmapsFuture != null && !loadHeatmapsFuture.isDone())
		{
			log.debug("NPC " + npcSpawned.getNpc().getName() + " spawned whilst heatmaps were still loading. Scheduling NpcId checks with the executor (so that they happen after heatmaps are loaded)...");
			worldHeatmapPluginExecutor.execute(() -> handleNpcSpawned(npcSpawned));
		}
		else if (loadHeatmapsFuture != null && loadHeatmapsFuture.isDone()){
			handleNpcSpawned(npcSpawned);
		}
		else if (loadHeatmapsFuture == null)
		{
			log.debug("NPC " + npcSpawned.getNpc().getName() + " spawned whilst heatmap loading wasn't even scheduled yet. Scheduling NpcId checks with the executor 10 seconds from now (so that they happen after heatmaps are loaded)...");
			worldHeatmapPluginExecutor.schedule(() -> handleNpcSpawned(npcSpawned), 10, TimeUnit.SECONDS);
			//TODO: it would be best if the plugin could detect when the heatmaps are done loading, and then run the scheduled tasks
			//	or if the heatmap loading could be done sooner
		}
	}

	public void handleNpcSpawned(final NpcSpawned npcSpawned){
		// Currently it counts all random event spawns, not just random events meant for the local player
		if (randomEventNPCIDs.contains(npcSpawned.getNpc().getId())){
			// RANDOM_EVENT_SPAWNS
			heatmaps.get(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS).increment(npcSpawned.getNpc().getWorldLocation().getX(), npcSpawned.getNpc().getWorldLocation().getY());
		}

		// BOB_THE_CAT_SIGHTING
		if (npcSpawned.getNpc().getId() == NpcID.BOB_8034)
		{
			log.debug("Bob spotted.");
			log.debug("Info: loadHeamtaps future is " + (loadHeatmapsFuture == null ? "null" : "not null") + ", and is done: " + (loadHeatmapsFuture == null ? "null" : loadHeatmapsFuture.isDone()));
			// Only count Bob the Cat sightings once per hour at most
			if (timeLastSeenBobTheCatPerWorld.get(client.getWorld()) == null || Instant.now().isAfter(timeLastSeenBobTheCatPerWorld.get(client.getWorld()).plusSeconds(3600)))
			{
				log.debug("Bob's timer on world " + client.getWorld() + " has expired. Logging sighting.");
				heatmaps.get(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING).increment(npcSpawned.getNpc().getWorldLocation().getX(), npcSpawned.getNpc().getWorldLocation().getY());
				timeLastSeenBobTheCatPerWorld.put(client.getWorld(), Instant.now());
			}
		}
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned itemSpawned){
		// NOTE: this happens many times when you log in (once for each item on the ground), and at such time, the heatmaps haven't been loaded in, so you can't call .get() on them
		Tile tile = itemSpawned.getTile();
		TileItem tileItem = itemSpawned.getItem();
		// NOTE: it doesn't keep track of where items came from. You could drop the same item repeatedly, and it would count as a new item each time
		// LOOT_VALUE
		if (heatmaps.get(HeatmapNew.HeatmapType.LOOT_VALUE) != null){
			heatmaps.get(HeatmapNew.HeatmapType.LOOT_VALUE).increment(tile.getWorldLocation().getX(), tile.getWorldLocation().getY(), tileItem.getQuantity() * itemManager.getItemPrice(tileItem.getId()));
		}
		else {
			log.error("LOOT_VALUE heatmap is null");
		}
	}

	/**
	 * Autosave the heatmap file/write the 'TYPE_A' and 'TYPE_B' heatmap images if it is the correct time to do each respective thing according to their frequencies
	 *
	 * @param heatmaps          The HeatmapNew object
	 * @param heatmapFile       .heatmap file to save
	 * @param heatmapImageFiles Heatmap image files to write (usually Type A and Type B)
	 */
	private void autosaveRoutine(Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps, File heatmapFile, File[] heatmapImageFiles)
	{
		// If it's time to autosave the image, then save heatmap file (so file is in sync with image) and write image file
		int highestGameTimeTicks = 0;
		// Find the largest game time ticks of all the heatmaps
		for (HeatmapNew.HeatmapType type : heatmaps.keySet())
		{
			if (heatmaps.get(type).getGameTimeTicks() > highestGameTimeTicks)
			{
				highestGameTimeTicks = heatmaps.get(type).getGameTimeTicks();
			}
		}
		if (highestGameTimeTicks % WorldHeatmapPlugin.IMAGE_AUTOSAVE_FREQUENCY == 0)
		{
			worldHeatmapPluginExecutor.execute(() -> writeHeatmapsFile(heatmaps, heatmapFile));
			worldHeatmapPluginExecutor.execute(() -> writeHeatmapImage(heatmaps.get(HeatmapNew.HeatmapType.TYPE_A), heatmapImageFiles[0]));
			worldHeatmapPluginExecutor.execute(() -> writeHeatmapImage(heatmaps.get(HeatmapNew.HeatmapType.TYPE_B), heatmapImageFiles[1]));
		}

		// if it wasn't the time to autosave an image (and therefore save the .heatmap), then check if it's time to autosave just the .heatmap file
		else if (highestGameTimeTicks % WorldHeatmapPlugin.HEATMAP_AUTOSAVE_FREQUENCY == 0)
		{
			worldHeatmapPluginExecutor.execute(() -> writeHeatmapsFile(heatmaps, heatmapFile));
		}
	}

	/**
	 * Backs up the heatmap file if it is the correct time to do so according to the backup frequency
	 *
	 * @param heatmaps          The HeatmapNew object
	 * @param heatmapBackupFile The backup file
	 * @param backupFrequency   How often to back up the heatmap file (in ticks)
	 */
	private void backupRoutine(Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps, File heatmapBackupFile, int backupFrequency)
	{
		int highestGameTimeTicks = 0;
		for (HeatmapNew.HeatmapType type : heatmaps.keySet())
		{
			if (heatmaps.get(type).getGameTimeTicks() > highestGameTimeTicks)
			{
				highestGameTimeTicks = heatmaps.get(type).getGameTimeTicks();
			}
		}
		if (highestGameTimeTicks % backupFrequency == 0)
		{
			worldHeatmapPluginExecutor.execute(() -> writeHeatmapsFile(heatmaps, heatmapBackupFile));
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
	 * Returns the "diagonal distance" (the maximum of the horizontal and vertical distance) between two points
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

	/**
	 * Loads heatmap from local storage. For each heatmap type that is not found, a new one is NOT created.
	 * Call initializeMissingHeatmaps() to create any missing heatmaps (it's a separate function so that you can
	 * specify what type you want an old Heatmap file type to be if read, which default will be HeatmapNew.HeatmapType.UNKNOWN)
	 *
	 * @param heatmapFile The heatmap file
	 * @return HashMap of HeatmapNew objects
	 * @throws FileNotFoundException If the file does not exist
	 */
	private HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFile(File heatmapFile) throws FileNotFoundException
	{
		log.info("Loading heatmap file '" + heatmapFile.getName() + "'");
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
				HashMap<HeatmapNew.HeatmapType, HeatmapNew> hashmap = new HashMap<>();
				hashmap.put(HeatmapNew.HeatmapType.UNKNOWN, result);
				return hashmap;
			}
		}
		catch (Exception e)
		{
			// If reached here, then the file was not of the older type.
		}

		// Test if it is of the newer type, or something else altogether.
		try (FileInputStream fis = new FileInputStream(heatmapFile);
			 ZipInputStream zis = new ZipInputStream(fis);
			 InputStreamReader isr = new InputStreamReader(zis, StandardCharsets.UTF_8);
			 BufferedReader reader = new BufferedReader(isr))
		{
			int entryNum = 0;
			HashMap<HeatmapNew.HeatmapType, HeatmapNew> hashmapsRead = new HashMap<>();
			ZipEntry curZipEntry;
			StringBuilder loggingOutput = new StringBuilder();
			loggingOutput.append("Heatmap types loaded: ");
			while ((curZipEntry = zis.getNextEntry()) != null)
			{
				entryNum++;
				// Read them field variables
				String[] fieldNames = reader.readLine().split(",");
				String[] fieldValues = reader.readLine().split(",");
				long userID = (fieldValues[0].isEmpty() ? -1 : Long.parseLong(fieldValues[0]));
				HeatmapNew.HeatmapType heatmapType = (fieldValues[2].isEmpty() ? HeatmapNew.HeatmapType.UNKNOWN : HeatmapNew.HeatmapType.valueOf(fieldValues[2]));
				int totalValue = (fieldValues[3].isEmpty() ? -1 : Integer.parseInt(fieldValues[3]));
				int numTilesVisited = (fieldValues[4].isEmpty() ? -1 : Integer.parseInt(fieldValues[4]));
				int maxVal = (fieldValues[5].isEmpty() ? -1 : Integer.parseInt(fieldValues[5]));
				int maxValX = (fieldValues[6].isEmpty() ? -1 : Integer.parseInt(fieldValues[6]));
				int maxValY = (fieldValues[7].isEmpty() ? -1 : Integer.parseInt(fieldValues[7]));
				int minVal = (fieldValues[8].isEmpty() ? -1 : Integer.parseInt(fieldValues[8]));
				int minValX = (fieldValues[9].isEmpty() ? -1 : Integer.parseInt(fieldValues[9]));
				int minValY = (fieldValues[10].isEmpty() ? -1 : Integer.parseInt(fieldValues[10]));
				int gameTimeTicks = (fieldValues[11].isEmpty() ? -1 : Integer.parseInt(fieldValues[11]));

				// Make ze Heatmap
				HeatmapNew heatmap = new HeatmapNew(heatmapType, userID);

				// Read the tile values
				final int[] errorCount = {0}; // Number of parsing errors occurred during read
				reader.lines().forEach(s -> {
					String[] tile = s.split(",");
					try
					{
						heatmap.set(Integer.parseInt(tile[0]), Integer.parseInt(tile[1]), Integer.parseInt(tile[2]));
					}
					catch (NumberFormatException e)
					{
						errorCount[0]++;
					}
				});
				if (errorCount[0] != 0)
				{
					log.error(errorCount[0] + " errors occurred during " + heatmapType + " heatmap file read.");
				}
				loggingOutput.append(heatmapType + " (" + numTilesVisited + " tiles), ");
				hashmapsRead.put(heatmapType, heatmap);
			}
			log.info(loggingOutput.toString());

			return hashmapsRead;
		}
		catch (FileNotFoundException e)
		{
			throw e;
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Initializes any Heatmap types that weren't loaded
	 *
	 * @param heatmaps HashMap of HeatmapNew objects
	 * @param userID   The user ID
	 * @return HashMap of HeatmapNew objects
	 */
	public void initializeMissingHeatmaps(Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps, long userID)
	{
		// Initialize any Heatmap types that weren't loaded
		Set<HeatmapNew.HeatmapType> missingTypes = new HashSet<>();
		ArrayList<String> missingTypeNames = new ArrayList<>();
		for (HeatmapNew.HeatmapType type : HeatmapNew.HeatmapType.values())
		{
			if (!heatmaps.containsKey(type))
			{
				missingTypes.add(type);
				missingTypeNames.add(type.toString());
			}
		}
		if (!missingTypeNames.isEmpty())
		{
			log.info("The following Heatmap types did not exist: " + Strings.join(missingTypeNames, ", ") + ". Initializing new Heatmaps...");
			for (HeatmapNew.HeatmapType type : missingTypes)
			{
				heatmaps.put(type, new HeatmapNew(type, userID));
			}
		}
	}

	/**
	 * Saves heatmap to 'Heatmap Files' folder.
	 */
	protected void writeHeatmapsFile(Map<HeatmapNew.HeatmapType, HeatmapNew> heatmapsToWrite, File fileOut)
	{
		if (!fileOut.isAbsolute())
		{
			fileOut = new File(HEATMAP_FILES_DIR, fileOut.toString());
		}
		log.info("Saving " + fileOut.getName() + " heatmap file to disk...");
		long startTime = System.nanoTime();
		// Make the directory path if it doesn't exist
		File file = fileOut;
		if (!Files.exists(Paths.get(file.getParent())))
		{
			new File(file.getParent()).mkdirs();
		}

		StringBuilder loggingOutput = new StringBuilder("Heatmap types saved: ");
		// Write the heatmap file
		try (FileOutputStream fos = new FileOutputStream(fileOut);
			 ZipOutputStream zos = new ZipOutputStream(fos);
			 OutputStreamWriter osw = new OutputStreamWriter(zos, StandardCharsets.UTF_8))
		{
			for (HeatmapNew.HeatmapType type : heatmapsToWrite.keySet())
			{
				String zipEntryName = type.toString() + "_HEATMAP.csv";
				ZipEntry ze = new ZipEntry(zipEntryName);
				zos.putNextEntry(ze);
				// Write them field variables
				osw.write("userID,heatmapVersion,heatmapType,totalValue,numTilesVisited,maxVal,maxValX,maxValY,minVal,minValX,minValY,gameTimeTicks\n");
				HeatmapNew curHeatmap = heatmapsToWrite.get(type);
				osw.write(curHeatmap.getUserID() +
					"," + HeatmapNew.getHeatmapVersion() +
					"," + curHeatmap.getHeatmapType() +
					"," + curHeatmap.getTotalValue() +
					"," + curHeatmap.getNumTilesVisited() +
					"," + curHeatmap.getMaxVal()[0] +
					"," + curHeatmap.getMaxVal()[1] +
					"," + curHeatmap.getMaxVal()[2] +
					"," + curHeatmap.getMinVal()[0] +
					"," + curHeatmap.getMinVal()[1] +
					"," + curHeatmap.getMinVal()[2] +
					"," + curHeatmap.getGameTimeTicks() + "\n");
				// Write the tile values
				int numTilesWritten = 0;
				for (Map.Entry<Point, Integer> e : curHeatmap.getEntrySet())
				{
					numTilesWritten++;
					int x = e.getKey().x;
					int y = e.getKey().y;
					int stepVal = e.getValue();
					osw.write(x + "," + y + "," + stepVal + "\n");
				}
				osw.flush();
				loggingOutput.append(type + " (" + numTilesWritten + " tiles), ");
			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
			log.error("World Heatmap was not able to save all heatmap files");
		}
		log.info(loggingOutput.toString());
		log.info("Finished writing " + fileOut.getName() + " heatmap file to disk after " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
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
				final int tileHeight = calculateTileHeight(config.speedMemoryTradeoff());
				final int N = reader.getHeight(0) / tileHeight;

				// Make progress listener majigger
				HeatmapProgressListener progressListener = new HeatmapProgressListener(heatmap.getHeatmapType());
				writer.addIIOWriteProgressListener(progressListener);

				// Prepare writing parameters
				ImageWriteParam writeParam = writer.getDefaultWriteParam();
				writeParam.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
				writeParam.setTiling(tileWidth, tileHeight, 0, 0);
				writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				writeParam.setCompressionType("Deflate");
				writeParam.setCompressionQuality(0);

				// Write heatmap image
				RenderedImage heatmapImage = new HeatmapImage(heatmap, reader, N, heatmapTransparency, config.heatmapSensitivity());
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
			log.error("Exception thrown whilst creating and/or writing image file: " + e.getMessage());
		}
	}

	/**
	 * Calculates the tile height based on the config setting
	 *
	 * @param configSetting The config setting
	 * @return The tile height
	 */
	private int calculateTileHeight(int configSetting)
	{
		// NOTE: these will have to be recalculated if the world map image's size is ever changed
		return new int[]{16, 32, 80, 496, 992, 2480, 4960}[configSetting];
	}

	private class HeatmapProgressListener implements IIOWriteProgressListener
	{
		HeatmapNew.HeatmapType heatmapType = HeatmapNew.HeatmapType.UNKNOWN;

		public HeatmapProgressListener(HeatmapNew.HeatmapType heatmapType)
		{
			super();
			this.heatmapType = heatmapType;
		}

		public void setHeatmapType(HeatmapNew.HeatmapType type)
		{
			this.heatmapType = type;
		}

		@Override
		public void imageStarted(ImageWriter source, int imageIndex)
		{
			panel.setEnabledHeatmapButtons(false);
			panel.writeHeatmapImageButtons.get(heatmapType).setText("Writing... 0%");
		}

		@Override
		public void imageProgress(ImageWriter source, float percentageDone)
		{
			panel.writeHeatmapImageButtons.get(heatmapType).setText(String.format("Writing... %.0f%%", percentageDone));
		}

		@Override
		public void imageComplete(ImageWriter source)
		{
			panel.writeHeatmapImageButtons.get(heatmapType).setText("Done");
			worldHeatmapPluginExecutor.schedule(() -> {
				panel.writeHeatmapImageButtons.get(heatmapType).setText("Write Heatmap Image");
				panel.writeHeatmapImageButtons.get(heatmapType).revalidate();
				panel.writeHeatmapImageButtons.get(heatmapType).repaint();
				panel.setEnabledHeatmapButtons(true);
			}, 2L, TimeUnit.SECONDS);
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
