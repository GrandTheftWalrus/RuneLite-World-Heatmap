package com.worldheatmap;

import com.google.inject.Provides;

import java.awt.Color;
import java.awt.Point;
import java.awt.image.RenderedImage;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.InflaterInputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.swing.*;

import joptsimple.internal.Strings;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.ClientToolbar;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import net.runelite.client.game.ItemManager;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

@Slf4j
@PluginDescriptor(
        name = "World Heatmap"
)
public class WorldHeatmapPlugin extends Plugin {
    private static final int HEATMAP_AUTOSAVE_FREQUENCY = 3000; // How often to autosave the .heatmap file (in ticks)
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
    private final ArrayList<Integer> randomEventNPCIDs = new ArrayList<>(Arrays.asList(NpcID.BEE_KEEPER_6747,
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
    WorldHeatmapConfig config;

    @Inject
    private ClientToolbar clientToolbar;

    @Provides
    WorldHeatmapConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WorldHeatmapConfig.class);
    }

    @SneakyThrows
    protected void loadHeatmaps() {
        log.debug("Loading heatmaps under user ID " + mostRecentLocalUserID + "...");

        // To fix/deal with how previous versions of the plugin used player names
        // (which can change) instead of player IDs, we also do the following check
        File filepathUserID = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserID + ".heatmaps"); //NOTE: changed from .heatmap to .heatmaps
        File filepathTypeAUsername = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName + "_TypeA.heatmap");
        File filepathTypeBUsername = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName + "_TypeB.heatmap");

        if (filepathUserID.exists()) {
            // Load all heatmaps from the file
            heatmaps = readHeatmapsFromFile(filepathUserID);
        } else // If the file doesn't exist, then check for the old username files
        {
            log.info("File '" + filepathUserID.getName() + "' did not exist. Checking for old version files '" + filepathTypeAUsername.getName() + "' and '" + filepathTypeBUsername.getName() + "'...");
            if (filepathTypeAUsername.exists()) {
                HeatmapNew heatmapTypeA = readHeatmapOld(filepathTypeAUsername);
                heatmapTypeA.setHeatmapType(HeatmapNew.HeatmapType.TYPE_A);
                heatmaps.put(HeatmapNew.HeatmapType.TYPE_A, heatmapTypeA);
            } else {
                log.info("File '" + filepathTypeAUsername.getName() + "' did not exist.");
            }
            if (filepathTypeBUsername.exists()) {
                HeatmapNew heatmapTypeB = readHeatmapOld(filepathTypeBUsername);
                heatmapTypeB.setHeatmapType(HeatmapNew.HeatmapType.TYPE_B);
                heatmaps.put(HeatmapNew.HeatmapType.TYPE_B, heatmapTypeB);
            } else {
                log.info("File '" + filepathTypeBUsername.getName() + "' did not exist.");
            }
        }

        initializeMissingHeatmaps(heatmaps, mostRecentLocalUserID);
        panel.setEnabledHeatmapButtons(true);
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
        panel.setEnabledHeatmapButtons(false);
    }

    @Override
    protected void shutDown() {
        if (loadHeatmapsFuture != null && loadHeatmapsFuture.isDone()) {
            String filepath = Paths.get(mostRecentLocalUserID + ".heatmaps").toString();
            worldHeatmapPluginExecutor.execute(() -> writeHeatmapsToFile(heatmaps.values(), new File(filepath)));
        }
        clientToolbar.removeNavigation(toolbarButton);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGGING_IN) {
            shouldLoadHeatmaps = true;
            loadHeatmapsFuture = null;
        }

        // If you're at the login screen and heatmaps have been loaded (implying that you were previously logged in, but now you're logged out)
        if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN && loadHeatmapsFuture != null && loadHeatmapsFuture.isDone()) {
            String filepath = Paths.get(mostRecentLocalUserID + ".heatmaps").toString();
            worldHeatmapPluginExecutor.execute(() -> writeHeatmapsToFile(heatmaps.values(), new File(filepath)));
            loadHeatmapsFuture = null;
        }
        if (gameStateChanged.getGameState() != GameState.LOGGED_IN) {
            // Disable write heatmap image buttons
            panel.setEnabledHeatmapButtons(false);
        }
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
            // Enable write heatmap image buttons
            panel.setEnabledHeatmapButtons(true);
        }
    }

    @Subscribe
    public void onGameTick(GameTick gameTick) {
        if (client.getAccountHash() == -1) {
            return;
        }
        // If the player has changed, update the player ID
        if (mostRecentLocalUserID != client.getAccountHash()) {
            mostRecentLocalUserName = client.getLocalPlayer().getName();
            mostRecentLocalUserID = client.getAccountHash();
        }
        if (panel.mostRecentLocalUserID != mostRecentLocalUserID) {
            SwingUtilities.invokeLater(panel::updatePlayerID);
        }

        // Load the heatmaps from disk if needed
        if (shouldLoadHeatmaps && client.getGameState().equals(GameState.LOGGED_IN)) {
            // Populate previousXP array with current XP values
            for (Skill skill : Skill.values()) {
                previousXP[skill.ordinal()] = client.getSkillExperience(skill);
            }

            // Schedule the loading of the heatmap files
            shouldLoadHeatmaps = false;
            loadHeatmapsFuture = worldHeatmapPluginExecutor.submit(this::loadHeatmaps);
        }
        // The following code requires the heatmap files to have been loaded
        if (loadHeatmapsFuture != null && !loadHeatmapsFuture.isDone()) {
            return;
        }

        // Increment game time ticks of each heatmap
        for (HeatmapNew.HeatmapType type : heatmaps.keySet()) {
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
        if (diagDistance <= 3) {
            // Gets all the tiles between last position and new position
            for (Point tile : getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY))) {
                // TYPE_A
                if (playerMovedSinceLastTick && config.isHeatmapTypeAEnabled()) {
                    heatmaps.get(HeatmapNew.HeatmapType.TYPE_A).increment(tile.x, tile.y);
                }

                // TYPE_B
                if (config.isHeatmapTypeBEnabled()) {
                    heatmaps.get(HeatmapNew.HeatmapType.TYPE_B).increment(tile.x, tile.y);
                }
            }
        }

        // TELEPORT_PATHS
        if (config.isHeatmapTeleportPathsEnabled() && diagDistance > 15 && isInOverworld(new Point(lastX, lastY)) && isInOverworld(new Point(currentX, currentY))) //we don't draw lines between the overworld and caves etc.
        {
            if (config.isHeatmapTeleportPathsEnabled()) {
                for (Point tile : getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY))) {
                    heatmaps.get(HeatmapNew.HeatmapType.TELEPORT_PATHS).increment(tile.x, tile.y);
                }
            }
        }

        // TELEPORTED_TO and TELEPORTED_FROM
        if (diagDistance > 15 && isInOverworld(new Point(lastX, lastY)) && isInOverworld(new Point(currentX, currentY))) //we only track teleports between overworld tiles
        {
            if (config.isHeatmapTeleportedToEnabled()) {
                heatmaps.get(HeatmapNew.HeatmapType.TELEPORTED_TO).increment(currentX, currentY);
            }
            if (config.isHeatmapTeleportedFromEnabled()) {
                heatmaps.get(HeatmapNew.HeatmapType.TELEPORTED_FROM).increment(lastX, lastY);
            }
        }

        // Backup/autosave routines
        autosaveRoutine();
        backupRoutine();

        // Update panel step counter
        SwingUtilities.invokeLater(panel::updateCounts);

        // Update memory usage label/tooltips every 10 ticks
        if (client.getTickCount() % 10 == 0) {
            SwingUtilities.invokeLater(panel::updateMemoryUsages);
        }

        // Update last coords
        lastX = currentX;
        lastY = currentY;
    }

    @Subscribe
    public void onActorDeath(ActorDeath actorDeath) {
        if (actorDeath.getActor() instanceof Player) {
            Player actor = (Player) actorDeath.getActor();
            if (actor.getId() == client.getLocalPlayer().getId()) {
                // DEATHS
                if (config.isHeatmapDeathsEnabled()) {
                    heatmaps.get(HeatmapNew.HeatmapType.DEATHS).increment(actor.getWorldLocation().getX(), actor.getWorldLocation().getY());
                }
            }
        } else if (actorDeath.getActor() instanceof NPC) {
            if (heatmaps.get(HeatmapNew.HeatmapType.NPC_DEATHS) != null && config.isHeatmapNPCDeathsEnabled()) {
                // NPC_DEATHS
                heatmaps.get(HeatmapNew.HeatmapType.NPC_DEATHS).increment(actorDeath.getActor().getWorldLocation().getX(), actorDeath.getActor().getWorldLocation().getY());
            }
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied hitsplatApplied) {
        if (hitsplatApplied.getHitsplat().getAmount() == 0) {
            return;
        }
        // DAMAGE_GIVEN
        if (hitsplatApplied.getHitsplat().getHitsplatType() == net.runelite.api.HitsplatID.DAMAGE_ME && hitsplatApplied.getActor() instanceof NPC) {
            NPC actor = (NPC) hitsplatApplied.getActor();
            if (config.isHeatmapDamageGivenEnabled()) {
                heatmaps.get(HeatmapNew.HeatmapType.DAMAGE_GIVEN).increment(actor.getWorldLocation().getX(), actor.getWorldLocation().getY(), hitsplatApplied.getHitsplat().getAmount());
            }
        }

        // DAMAGE_TAKEN
        if (hitsplatApplied.getHitsplat().getHitsplatType() == HitsplatID.DAMAGE_ME && hitsplatApplied.getActor() instanceof Player) {
            Player actor = (Player) hitsplatApplied.getActor();
            if (actor.getId() == client.getLocalPlayer().getId()) {
                if (config.isHeatmapDamageTakenEnabled()) {
                    heatmaps.get(HeatmapNew.HeatmapType.DAMAGE_TAKEN).increment(actor.getWorldLocation().getX(), actor.getWorldLocation().getY(), hitsplatApplied.getHitsplat().getAmount());
                }
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) {
            return;
        }
        if (chatMessage.getType() == ChatMessageType.PUBLICCHAT && chatMessage.getName().contains(client.getLocalPlayer().getName())) {
            // PLACES_SPOKEN_AT
            if (config.isHeatmapPlacesSpokenAtEnabled() && heatmaps.get(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT) != null) {
                heatmaps.get(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT).increment(client.getLocalPlayer().getWorldLocation().getX(), client.getLocalPlayer().getWorldLocation().getY());
            }
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged statChanged) {
        // NOTE: this happens 23 times when you log in, and at such time, the heatmaps haven't been loaded in, so you can't call .get() on them
        // Get difference between previous and current XP
        int skillIndex = statChanged.getSkill().ordinal();
        int xpDifference = client.getSkillExperience(statChanged.getSkill()) - previousXP[skillIndex];

        // Update previous XP
        previousXP[skillIndex] = client.getSkillExperience(statChanged.getSkill());

        // XP_GAINED
        if (config.isHeatmapXPGainedEnabled() && heatmaps.get(HeatmapNew.HeatmapType.XP_GAINED) != null) {
            heatmaps.get(HeatmapNew.HeatmapType.XP_GAINED).increment(client.getLocalPlayer().getWorldLocation().getX(), client.getLocalPlayer().getWorldLocation().getY(), xpDifference);
        }
    }

    @Subscribe
    public void onNpcSpawned(final NpcSpawned npcSpawned) {
        // Currently it counts all random event spawns, not just random events meant for the local player
        if (randomEventNPCIDs.contains(npcSpawned.getNpc().getId())) {
            // RANDOM_EVENT_SPAWNS
            if (config.isHeatmapRandomEventSpawnsEnabled() && heatmaps.get(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS) != null) {
                heatmaps.get(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS).increment(npcSpawned.getNpc().getWorldLocation().getX(), npcSpawned.getNpc().getWorldLocation().getY());
            }
        }

        // BOB_THE_CAT_SIGHTING
        if (config.isHeatmapBobTheCatSightingEnabled() && npcSpawned.getNpc().getId() == NpcID.BOB_8034) {
            // Only count Bob the Cat sightings once per hour at most
            if (timeLastSeenBobTheCatPerWorld.get(client.getWorld()) == null || Instant.now().isAfter(timeLastSeenBobTheCatPerWorld.get(client.getWorld()).plusSeconds(3600))) {
                if (heatmaps.get(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING) != null) {
                    heatmaps.get(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING).increment(npcSpawned.getNpc().getWorldLocation().getX(), npcSpawned.getNpc().getWorldLocation().getY());
                    timeLastSeenBobTheCatPerWorld.put(client.getWorld(), Instant.now());
                }
            }
        }
    }

    @Subscribe
    public void onNpcLootReceived(final NpcLootReceived npcLootReceived) {
        // LOOT_VALUE
        if (config.isHeatmapLootValueEnabled()) {
            for (ItemStack itemStack : npcLootReceived.getItems()) {
                int localX = itemStack.getLocation().getX();
                int localY = itemStack.getLocation().getY();
                WorldPoint worldPoint = WorldPoint.fromLocal(client, localX, localY, client.getPlane());
                int totalValue = itemStack.getQuantity() * itemManager.getItemPrice(itemStack.getId());
                if (heatmaps.get(HeatmapNew.HeatmapType.LOOT_VALUE) != null) {
                    heatmaps.get(HeatmapNew.HeatmapType.LOOT_VALUE).increment(worldPoint.getX(), worldPoint.getY(), totalValue);
                }
            }
        }
    }

    /**
     * Autosave the heatmap file/write the 'TYPE_A' and 'TYPE_B' heatmap images if it is the correct time to do each respective thing according to their frequencies
     */
    private void autosaveRoutine() {
        File heatmapsFile = Paths.get(mostRecentLocalUserID + ".heatmaps").toFile();
        File typeAImageFile = new File(mostRecentLocalUserID + "_" + HeatmapNew.HeatmapType.TYPE_A + ".tif");
        File typeBImageFile = new File(mostRecentLocalUserID + "_" + HeatmapNew.HeatmapType.TYPE_B + ".tif");

        // Find the largest game time ticks of all the heatmaps
        int highestGameTimeTicks = 0;
        for (HeatmapNew.HeatmapType type : heatmaps.keySet()) {
            if (heatmaps.get(type).getGameTimeTicks() > highestGameTimeTicks) {
                highestGameTimeTicks = heatmaps.get(type).getGameTimeTicks();
            }
        }

        // If it's time to autosave the image, then save heatmap file (so file is in sync with image) and write image file
        if (config.typeABImageAutosave() && highestGameTimeTicks % config.typeABImageAutosaveFrequency() == 0) {
            worldHeatmapPluginExecutor.execute(() -> writeHeatmapsToFile(heatmaps.values(), heatmapsFile));
            if (config.isHeatmapTypeAEnabled()) {
                worldHeatmapPluginExecutor.execute(() -> writeHeatmapImage(heatmaps.get(HeatmapNew.HeatmapType.TYPE_A), typeAImageFile, false));
            }
            if (config.isHeatmapTypeBEnabled()) {
                worldHeatmapPluginExecutor.execute(() -> writeHeatmapImage(heatmaps.get(HeatmapNew.HeatmapType.TYPE_B), typeBImageFile, false));
            }
        }
        // if it wasn't the time to autosave an image (and therefore save the .heatmap), then check if it's time to autosave just the .heatmap file
        else if (highestGameTimeTicks % WorldHeatmapPlugin.HEATMAP_AUTOSAVE_FREQUENCY == 0) {
            worldHeatmapPluginExecutor.execute(() -> writeHeatmapsToFile(heatmaps.values(), heatmapsFile));
        }
    }

    /**
     * Backs up the heatmap file if it is the correct time to do so according to the backup frequency
     */
    private void backupRoutine() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        File heatmapsBackupFile = new File("Backups", mostRecentLocalUserID + "-" + java.time.LocalDateTime.now().format(formatter) + ".heatmaps");
        int highestGameTimeTicks = 0;
        for (HeatmapNew.HeatmapType type : heatmaps.keySet()) {
            if (heatmaps.get(type).getGameTimeTicks() > highestGameTimeTicks) {
                highestGameTimeTicks = heatmaps.get(type).getGameTimeTicks();
            }
        }
        if (highestGameTimeTicks % config.heatmapBackupFrequency() == 0) {
            worldHeatmapPluginExecutor.execute(() -> writeHeatmapsToFile(heatmaps.values(), heatmapsBackupFile));
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
     * Returns the "diagonal distance" (the maximum of the horizontal and vertical distance) between two points
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

    public boolean isInOverworld(Point point) {
        return point.y < Constants.OVERWORLD_MAX_Y && point.y > 2500 && point.x >= 1024 && point.x < 3960;
    }

    /**
     * Loads heatmap of old style from local storage.
     *
     * @param heatmapFile The heatmap file
     * @return HeatmapNew object
     */
    private HeatmapNew readHeatmapOld(File heatmapFile) {
        log.info("Loading heatmap file '" + heatmapFile.getName() + "'");
        try (FileInputStream fis = new FileInputStream(heatmapFile);
             InflaterInputStream iis = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(iis)) {
            Heatmap heatmap = (Heatmap) ois.readObject();
            log.info("Attempting to convert old-style heatmap file to new style...");
            long startTime = System.nanoTime();
            HeatmapNew result = HeatmapNew.convertOldHeatmapToNew(heatmap, mostRecentLocalUserID);
            log.info("Finished converting old-style heatmap to new style in " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
            return result;
        } catch (Exception e) {
            log.error("Exception occurred while reading heatmap file '" + heatmapFile.getName() + "'");
            throw new RuntimeException(e);
        }
    }

    private HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapsFile, List<HeatmapNew.HeatmapType> types) throws FileNotFoundException {
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI uri = URI.create("jar:" + heatmapsFile.toURI());
        try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
            HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmapsRead = new HashMap<>();
            StringBuilder loggingOutput = new StringBuilder();
            loggingOutput.append("Heatmap types loaded: ");

            for (HeatmapNew.HeatmapType curType : types) {
                Path curHeatmapPath = fs.getPath("/" + curType.toString() + "_HEATMAP.csv");
                if (!Files.exists(curHeatmapPath)) {
                    continue;
                }
                try (InputStreamReader isr = new InputStreamReader(Files.newInputStream(curHeatmapPath), StandardCharsets.UTF_8);
                     BufferedReader reader = new BufferedReader(isr)) {
                    // Read them field variables
                    String[] fieldNames = reader.readLine().split(",");
                    String[] fieldValues = reader.readLine().split(",");
                    long userID = (fieldValues[0].isEmpty() ? -1 : Long.parseLong(fieldValues[0]));
                    String heatmapTypeString = fieldValues[2];
                    int totalValue = (fieldValues[3].isEmpty() ? -1 : Integer.parseInt(fieldValues[3]));
                    int numTilesVisited = (fieldValues[4].isEmpty() ? -1 : Integer.parseInt(fieldValues[4]));
                    int maxVal = (fieldValues[5].isEmpty() ? -1 : Integer.parseInt(fieldValues[5]));
                    int maxValX = (fieldValues[6].isEmpty() ? -1 : Integer.parseInt(fieldValues[6]));
                    int maxValY = (fieldValues[7].isEmpty() ? -1 : Integer.parseInt(fieldValues[7]));
                    int minVal = (fieldValues[8].isEmpty() ? -1 : Integer.parseInt(fieldValues[8]));
                    int minValX = (fieldValues[9].isEmpty() ? -1 : Integer.parseInt(fieldValues[9]));
                    int minValY = (fieldValues[10].isEmpty() ? -1 : Integer.parseInt(fieldValues[10]));
                    int gameTimeTicks = (fieldValues[11].isEmpty() ? -1 : Integer.parseInt(fieldValues[11]));

                    // Get HeatmapType from field value if legit
                    HeatmapNew.HeatmapType recognizedHeatmapType;
                    if (Arrays.stream(HeatmapNew.HeatmapType.values()).noneMatch(type -> type.toString().equals(heatmapTypeString))) {
                        log.warn("Heatmap type '" + heatmapTypeString + "' from ZipEntry '" + curHeatmapPath + "' is not a valid Heatmap type (at least in this program version). Ignoring...");
                        // Stop reading and go to next Heatmap type
                        continue;
                    } else {
                        recognizedHeatmapType = HeatmapNew.HeatmapType.valueOf(heatmapTypeString);
                    }

                    if (!isHeatmapEnabled(recognizedHeatmapType) && recognizedHeatmapType != HeatmapNew.HeatmapType.UNKNOWN) {
                        log.info("Heatmap type '" + recognizedHeatmapType + "' is disabled. Ignoring and not loading...");
                        continue;
                    }

                    // Make ze Heatmap
                    HeatmapNew heatmap = new HeatmapNew(recognizedHeatmapType, userID);

                    // Read and load the tile values
                    final int[] errorCount = {0}; // Number of parsing errors occurred during read
                    reader.lines().forEach(s -> {
                        String[] tile = s.split(",");
                        try {
                            heatmap.set(Integer.parseInt(tile[0]), Integer.parseInt(tile[1]), Integer.parseInt(tile[2]));
                        } catch (NumberFormatException e) {
                            errorCount[0]++;
                        }
                    });
                    if (errorCount[0] != 0) {
                        log.error(errorCount[0] + " errors occurred during " + recognizedHeatmapType + " heatmap file read.");
                    }
                    loggingOutput.append(recognizedHeatmapType + " (" + numTilesVisited + " tiles), ");
                    heatmapsRead.put(recognizedHeatmapType, heatmap);
                } catch (IOException e) {
                    log.error("Error reading " + curType + " heatmap from .heatmaps entry '" + curHeatmapPath + "'");
                }
            }
            log.info(loggingOutput.toString());
            return heatmapsRead;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads all heatmap types from the given .heatmaps file.
     *
     * @param heatmapFile The .heatmaps file
     * @return HashMap of HeatmapNew objects
     * @throws FileNotFoundException If the file does not exist
     */
    private HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapFile) throws FileNotFoundException {
        return readHeatmapsFromFile(heatmapFile, Arrays.asList(HeatmapNew.HeatmapType.values()));
    }

    /**
     * Initializes any enabled Heatmap types in the given Map of Heatmaps that weren't loaded
     *
     * @param heatmaps HashMap of HeatmapNew objects
     * @param userID   The user ID
     */
    public void initializeMissingHeatmaps(Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps, long userID) {
        // Get the heatmaps that are enabled but were not loaded
        ArrayList<HeatmapNew.HeatmapType> missingTypes = new ArrayList<>();
        for (HeatmapNew.HeatmapType type : HeatmapNew.HeatmapType.values()) {
            if (isHeatmapEnabled(type) && (!heatmaps.containsKey(type) || heatmaps.get(type) == null)) {
                missingTypes.add(type);
            }
        }
        if (missingTypes.isEmpty())
            return;

        List<String> missingTypesNames = new ArrayList<>();
        for (HeatmapNew.HeatmapType type : missingTypes) {
            missingTypesNames.add(type.toString());
        }
        log.info("Initializing the following types: " + Strings.join(missingTypesNames, ", "));
        for (HeatmapNew.HeatmapType type : missingTypes) {
            heatmaps.put(type, new HeatmapNew(type, userID));
        }
    }

    /**
     * Saves a specified heatmap to the specified .heatmaps file within the 'Heatmap Files' folder. Forces overwrite of disabled heatmaps.
     */
    protected void writeHeatmapsToFile(HeatmapNew heatmap, File fileOut) {
        writeHeatmapsToFile(Collections.singletonList(heatmap), fileOut, true);
    }

    /**
     * Saves provided heatmaps to specified file in 'Heatmap Files' folder. Disabled heatmaps are left unchanged in the file.
     */
    protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile) {
        writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, false);
    }

    /**
     * Saves provided heatmaps to specified file in 'Heatmap Files' folder. Disabled heatmaps are left unchanged in the file.
     */
    protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, boolean forceOverwrite) {
        if (!heatmapsFile.isAbsolute()) {
            heatmapsFile = new File(HEATMAP_FILES_DIR, heatmapsFile.toString());
        }
        // Make the directory path if it doesn't exist
        if (!Files.exists(Paths.get(heatmapsFile.getParent()))) {
            if (!new File(heatmapsFile.getParent()).mkdirs()) {
                log.error("World Heatmap was not able to create the directory for the heatmap file");
            }
        }

        // Creates the zip file if it doesn't exist
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI uri = URI.create("jar:" + heatmapsFile.toURI());

        log.info("Saving heatmaps to file '" + heatmapsFile.getName() + "'...");
        long startTime = System.nanoTime();
        StringBuilder loggingOutput = new StringBuilder("Heatmap types saved: ");
        for (HeatmapNew heatmap : heatmapsToWrite) {
            if (isHeatmapEnabled(heatmap.getHeatmapType()) || forceOverwrite) {
                // Write the heatmap file
                try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
                    Path zipEntryFile = fs.getPath("/" + heatmap.getHeatmapType().toString() + "_HEATMAP.csv");
                    try (OutputStreamWriter osw = new OutputStreamWriter(Files.newOutputStream(zipEntryFile), StandardCharsets.UTF_8)) {
                        // Write them field variables
                        osw.write("userID,heatmapVersion,heatmapType,totalValue,numTilesVisited,maxVal,maxValX,maxValY,minVal,minValX,minValY,gameTimeTicks\n");
                        osw.write(heatmap.getUserID() +
                                "," + HeatmapNew.getHeatmapVersion() +
                                "," + heatmap.getHeatmapType() +
                                "," + heatmap.getTotalValue() +
                                "," + heatmap.getNumTilesVisited() +
                                "," + heatmap.getMaxVal()[0] +
                                "," + heatmap.getMaxVal()[1] +
                                "," + heatmap.getMaxVal()[2] +
                                "," + heatmap.getMinVal()[0] +
                                "," + heatmap.getMinVal()[1] +
                                "," + heatmap.getMinVal()[2] +
                                "," + heatmap.getGameTimeTicks() + "\n");
                        // Write the tile values
                        for (Map.Entry<Point, Integer> e : heatmap.getEntrySet()) {
                            int x = e.getKey().x;
                            int y = e.getKey().y;
                            int stepVal = e.getValue();
                            osw.write(x + "," + y + "," + stepVal + "\n");
                        }
                        osw.flush();
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                    log.error("World Heatmap was not able to save heatmap type '" + heatmap.getHeatmapType() + "' to file '" + heatmapsFile.getName() + "'");
                }
                loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getNumTilesVisited() + " tiles), ");
            }
        }
        log.info(loggingOutput.toString());
        log.info("Finished writing '" + heatmapsFile.getName() + "' heatmap file to disk after " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
    }

    protected void writeHeatmapImage(HeatmapNew heatmap, File imageFileOut, boolean isFullMapImage) {
        log.info("Saving " + imageFileOut + " image to disk...");
        long startTime = System.nanoTime();
        if (!imageFileOut.getName().endsWith(".tif")) {
            imageFileOut = new File(imageFileOut.getName() + ".tif");
        }

        if (!imageFileOut.isAbsolute()) {
            imageFileOut = new File(HEATMAP_IMAGE_DIR, imageFileOut.getName());
        }

        float heatmapTransparency = (float) config.heatmapAlpha();
        if (heatmapTransparency < 0) {
            heatmapTransparency = 0;
        } else if (heatmapTransparency > 1) {
            heatmapTransparency = 1;
        }

        String worldMapImageURL;
        if (isFullMapImage) {
            worldMapImageURL = "https://raw.githubusercontent.com/GrandTheftWalrus/gtw-runelite-stuff/main/osrs_world_map_full.png";
        } else {
            worldMapImageURL = "https://raw.githubusercontent.com/GrandTheftWalrus/gtw-runelite-stuff/main/osrs_world_map.png";
        }

        // Prepare the image reader
        try (InputStream inputStream = new URL(worldMapImageURL).openStream();
             ImageInputStream worldMapImageInputStream = ImageIO.createImageInputStream(Objects.requireNonNull(inputStream, "Resource didn't exist: '" + worldMapImageURL + "'"))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("PNG").next();
            reader.setInput(worldMapImageInputStream, true);

            // Prepare the image writer
            try (FileOutputStream fos = new FileOutputStream(imageFileOut);
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
                ImageWriter writer = ImageIO.getImageWritersByFormatName("tif").next();
                writer.setOutput(ios);
                final int tileWidth = reader.getWidth(0);
                final int tileHeight;
                if (isFullMapImage) {
                    tileHeight = calculateTileHeightFullMap(config.speedMemoryTradeoff());
                } else {
                    tileHeight = calculateTileHeight(config.speedMemoryTradeoff());
                }
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
                RenderedImage heatmapImage;
                // Get latest offset values from git repo
                URL offsetsURL = new URL("https://raw.githubusercontent.com/GrandTheftWalrus/gtw-runelite-stuff/main/offsets.csv");
                Scanner scanner = new Scanner(offsetsURL.openStream());
                scanner.next(); // Skip the headers
                scanner.useDelimiter(",");
                int fullMapOffsetX = Integer.parseInt(scanner.next().trim());
                int fullMapOffsetY = Integer.parseInt(scanner.next().trim());
                int overworldMapOffsetX = Integer.parseInt(scanner.next().trim());
                int overworldMapOffsetY = Integer.parseInt(scanner.next().trim());
                scanner.close();

                if (isFullMapImage) {
                    heatmapImage = new HeatmapImage(heatmap, reader, N, heatmapTransparency, config.heatmapSensitivity(), fullMapOffsetX, fullMapOffsetY);
                } else {
                    heatmapImage = new HeatmapImage(heatmap, reader, N, heatmapTransparency, config.heatmapSensitivity(), overworldMapOffsetX, overworldMapOffsetY);
                }
                writer.write(null, new IIOImage(heatmapImage, null, null), writeParam);
                reader.dispose();
                writer.dispose();
            }
            log.info("Finished writing " + imageFileOut + " image to disk after " + (System.nanoTime() - startTime) / 1_000_000 + " ms");
        } catch (OutOfMemoryError e) {
            log.error("OutOfMemoryError thrown whilst creating and/or writing image file. " +
                    "If you're not able to fix the issue by lowering the memory usage settings " +
                    "(if they exist in this version of the plugin) then perhaps consider submitting" +
                    "an Issue on the GitHub");
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Exception thrown whilst creating and/or writing image file");
        }
    }

    /**
     * Calculates the tile height based on the config setting
     *
     * @param configSetting The config setting
     * @return The tile height
     */
    private int calculateTileHeight(int configSetting) {
        // NOTE: these should be adjusted if the world map image's size is ever changed
        // They should evenly divide the image height or else the image will be cut off
        // Also I think they have to be multiples of 16 or something for the TIF format?
        // old: return new int[]{16, 32, 64, 125, 150, 300, 600, 1600, 3200, 6400}[configSetting];
        return new int[]{32, 64, 125, 150, 300, 600, 1600, 3200, 6400}[configSetting];
    }

    /**
     * Calculates the tile height based on the config setting
     *
     * @param configSetting The config setting
     * @return The tile height
     */
    private int calculateTileHeightFullMap(int configSetting) {
        // NOTE: these should be adjusted if the world map image's size is ever changed
        // They should evenly divide the image height or else the image will be cut off
        // Also I think they have to be multiples of 16 or something for the TIF format?
        // old: return new int[]{16, 32, 64, 89, 178, 356, 712, 1424, 2848, 5696}[configSetting];
        return new int[]{32, 64, 89, 178, 356, 712, 1424, 2848, 5696}[configSetting];
    }

    private class HeatmapProgressListener implements IIOWriteProgressListener {
        Color originalColor;
        HeatmapNew.HeatmapType heatmapType;

        public HeatmapProgressListener(HeatmapNew.HeatmapType heatmapType) {
            super();
            this.heatmapType = heatmapType;
            if (panel.writeHeatmapImageButtons.get(heatmapType) != null) {
                originalColor = panel.writeHeatmapImageButtons.get(heatmapType).getForeground();
            }
        }

        public void setHeatmapType(HeatmapNew.HeatmapType type) {
            this.heatmapType = type;
        }

        @Override
        public void imageStarted(ImageWriter source, int imageIndex) {
            panel.setEnabledHeatmapButtons(false);
            panel.writeHeatmapImageButtons.get(heatmapType).setForeground(Color.GREEN);
            panel.writeHeatmapImageButtons.get(heatmapType).setText("Writing... 0%");
        }

        @Override
        public void imageProgress(ImageWriter source, float percentageDone) {
            panel.writeHeatmapImageButtons.get(heatmapType).setForeground(Color.GREEN);
            panel.writeHeatmapImageButtons.get(heatmapType).setText(String.format("Writing... %.2f%%", percentageDone));
        }

        @Override
        public void imageComplete(ImageWriter source) {
            panel.writeHeatmapImageButtons.get(heatmapType).setText("Done");
            worldHeatmapPluginExecutor.schedule(() -> {
                panel.writeHeatmapImageButtons.get(heatmapType).setText("Write Heatmap Image");
                panel.writeHeatmapImageButtons.get(heatmapType).setForeground(originalColor);
                panel.writeHeatmapImageButtons.get(heatmapType).revalidate();
                panel.writeHeatmapImageButtons.get(heatmapType).repaint();
                panel.setEnabledHeatmapButtons(true);
            }, 2L, TimeUnit.SECONDS);
        }

        @Override
        public void thumbnailStarted(ImageWriter source, int imageIndex, int thumbnailIndex) {
        }

        @Override
        public void thumbnailProgress(ImageWriter source, float percentageDone) {
        }

        @Override
        public void thumbnailComplete(ImageWriter source) {
        }

        @Override
        public void writeAborted(ImageWriter source) {
        }
    }

    boolean isHeatmapEnabled(HeatmapNew.HeatmapType type) {
        // Kinda ugly but seemed like the most logical way to make this function
        switch (type) {
            case TYPE_A:
                return config.isHeatmapTypeAEnabled();
            case TYPE_B:
                return config.isHeatmapTypeBEnabled();
            case XP_GAINED:
                return config.isHeatmapXPGainedEnabled();
            case TELEPORT_PATHS:
                return config.isHeatmapTeleportPathsEnabled();
            case TELEPORTED_TO:
                return config.isHeatmapTeleportedToEnabled();
            case TELEPORTED_FROM:
                return config.isHeatmapTeleportedFromEnabled();
            case LOOT_VALUE:
                return config.isHeatmapLootValueEnabled();
            case PLACES_SPOKEN_AT:
                return config.isHeatmapPlacesSpokenAtEnabled();
            case RANDOM_EVENT_SPAWNS:
                return config.isHeatmapRandomEventSpawnsEnabled();
            case DEATHS:
                return config.isHeatmapDeathsEnabled();
            case NPC_DEATHS:
                return config.isHeatmapNPCDeathsEnabled();
            case BOB_THE_CAT_SIGHTING:
                return config.isHeatmapBobTheCatSightingEnabled();
            case DAMAGE_TAKEN:
                return config.isHeatmapDamageTakenEnabled();
            case DAMAGE_GIVEN:
                return config.isHeatmapDamageGivenEnabled();
            default:
                return false;
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        File filepathUserID = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserID + ".heatmaps");
        if (event.getGroup().equals("worldheatmap")) {
            try {
                switch (event.getKey()) {
                    case "isHeatmapTypeAEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.TYPE_A + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.TYPE_A)).get(HeatmapNew.HeatmapType.TYPE_A);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.TYPE_A, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.TYPE_A + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.TYPE_A), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.TYPE_A);
                        }
                        break;
                    case "isHeatmapTypeBEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.TYPE_B + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.TYPE_B)).get(HeatmapNew.HeatmapType.TYPE_B);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.TYPE_B, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.TYPE_B + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.TYPE_B), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.TYPE_B);
                        }
                        break;
                    case "isHeatmapXPGainedEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.XP_GAINED + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.XP_GAINED)).get(HeatmapNew.HeatmapType.XP_GAINED);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.XP_GAINED, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.XP_GAINED + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.XP_GAINED), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.XP_GAINED);
                        }
                        break;
                    case "isHeatmapTeleportPathsEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.TELEPORT_PATHS + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.TELEPORT_PATHS)).get(HeatmapNew.HeatmapType.TELEPORT_PATHS);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.TELEPORT_PATHS, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.TELEPORT_PATHS + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.TELEPORT_PATHS), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.TELEPORT_PATHS);
                        }
                        break;
                    case "isHeatmapTeleportedToEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.TELEPORTED_TO + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.TELEPORTED_TO)).get(HeatmapNew.HeatmapType.TELEPORTED_TO);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.TELEPORTED_TO, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.TELEPORTED_TO + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.TELEPORTED_TO), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.TELEPORTED_TO);
                        }
                        break;
                    case "isHeatmapTeleportedFromEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.TELEPORTED_FROM + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.TELEPORTED_FROM)).get(HeatmapNew.HeatmapType.TELEPORTED_FROM);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.TELEPORTED_FROM, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.TELEPORTED_FROM + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.TELEPORTED_FROM), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.TELEPORTED_FROM);
                        }
                        break;
                    case "isHeatmapLootValueEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.LOOT_VALUE + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.LOOT_VALUE)).get(HeatmapNew.HeatmapType.LOOT_VALUE);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.LOOT_VALUE, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.LOOT_VALUE + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.LOOT_VALUE), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.LOOT_VALUE);
                        }
                        break;
                    case "isHeatmapPlacesSpokenAtEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.PLACES_SPOKEN_AT + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT)).get(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.PLACES_SPOKEN_AT + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT);
                        }
                        break;
                    case "isHeatmapRandomEventSpawnsEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS)).get(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS);
                        }
                        break;
                    case "isHeatmapDeathsEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.DEATHS + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.DEATHS)).get(HeatmapNew.HeatmapType.DEATHS);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.DEATHS, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.DEATHS + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.DEATHS), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.DEATHS);
                        }
                        break;
                    case "isHeatmapNPCDeathsEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.NPC_DEATHS + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.NPC_DEATHS)).get(HeatmapNew.HeatmapType.NPC_DEATHS);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.NPC_DEATHS, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.NPC_DEATHS + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.NPC_DEATHS), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.NPC_DEATHS);
                        }
                        break;
                    case "isHeatmapBobTheCatSightingEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING)).get(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING, hmap);
                            }

                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING);
                        }
                        break;
                    case "isHeatmapDamageTakenEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.DAMAGE_TAKEN + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.DAMAGE_TAKEN)).get(HeatmapNew.HeatmapType.DAMAGE_TAKEN);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.DAMAGE_TAKEN, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.DAMAGE_TAKEN + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.DAMAGE_TAKEN), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.DAMAGE_TAKEN);
                        }
                        break;
                    case "isHeatmapDamageGivenEnabled":
                        if (event.getNewValue().equals("true")) {
                            log.info("Enabling " + HeatmapNew.HeatmapType.DAMAGE_GIVEN + " heatmap...");
                            HeatmapNew hmap = readHeatmapsFromFile(filepathUserID, Collections.singletonList(HeatmapNew.HeatmapType.DAMAGE_GIVEN)).get(HeatmapNew.HeatmapType.DAMAGE_GIVEN);
                            if (hmap != null) {
                                heatmaps.put(HeatmapNew.HeatmapType.DAMAGE_GIVEN, hmap);
                            }
                        } else {
                            log.info("Disabling " + HeatmapNew.HeatmapType.DAMAGE_GIVEN + " heatmap...");
                            writeHeatmapsToFile(heatmaps.get(HeatmapNew.HeatmapType.DAMAGE_GIVEN), filepathUserID);
                            heatmaps.remove(HeatmapNew.HeatmapType.DAMAGE_GIVEN);
                        }
                        break;
                }
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } finally {
                initializeMissingHeatmaps(heatmaps, mostRecentLocalUserID);
                panel.rebuild();
            }

        }
    }
}
