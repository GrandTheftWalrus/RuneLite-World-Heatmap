package com.worldheatmap;

import com.google.inject.Provides;

import java.awt.*;
import java.awt.Point;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.inject.Inject;
import javax.swing.*;

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
import net.runelite.api.Varbits;

import java.awt.image.BufferedImage;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    protected int mostRecentAccountType;
    protected int mostRecentCombatLevel;
    private boolean shouldLoadHeatmaps;
    protected final File WORLD_HEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap");
    protected final File HEATMAP_FILES_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Files").toFile();
    protected Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = new HashMap<>();
    private NavigationButton toolbarButton;
    protected WorldHeatmapPanel panel;
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
    private Future<?> loadHeatmapsFuture;
    private final String HEATMAP_SITE_API_ENDPOINT = "https://osrsworldheatmap.com/api/upload-csv/";

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
        log.debug("Loading most recent heatmaps under user ID {}...", mostRecentLocalUserID);
        File latestHeatmapsFile = HeatmapFile.getLatestHeatmapFile(mostRecentLocalUserID);

        // Load all heatmaps from the file
        if (latestHeatmapsFile != null && latestHeatmapsFile.exists()) {
            heatmaps = HeatmapNew.readHeatmapsFromFile(latestHeatmapsFile, getEnabledHeatmapTypes());
            for (HeatmapNew heatmap : heatmaps.values()){
                heatmap.setUserID(mostRecentLocalUserID);
                heatmap.setAccountType(mostRecentAccountType);
                heatmap.setCurrentCombatLevel(mostRecentCombatLevel);
            }
        }

        handleLegacyV1HeatmapFiles();

        initializeMissingHeatmaps(heatmaps);
        panel.setEnabledHeatmapButtons(true);
    }

    /**
     * Handles loading of legacy V1 heatmap files by converting them to the new format, saving the new files, and renaming the old files to '.old'
     */
    private void handleLegacyV1HeatmapFiles() {
        File filepathTypeAUsername = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName + "_TypeA.heatmap");
        if (filepathTypeAUsername.exists()) {
            // Load and convert the legacy heatmap file
            HeatmapNew legacyHeatmapTypeA = HeatmapNew.readLegacyV1HeatmapFile(filepathTypeAUsername);
            legacyHeatmapTypeA.setUserID(mostRecentLocalUserID);
            legacyHeatmapTypeA.setAccountType(mostRecentAccountType);
            legacyHeatmapTypeA.setHeatmapType(HeatmapNew.HeatmapType.TYPE_A);
            legacyHeatmapTypeA.setCurrentCombatLevel(mostRecentCombatLevel);

            // Check if heatmap has already been loaded
            boolean typeAAlreadyLoaded = heatmaps.get(HeatmapNew.HeatmapType.TYPE_A) != null;
            if (!typeAAlreadyLoaded){
                // Load heatmap from legacy file
                log.info("Loading Type A legacy (V1) heatmap file for user ID {}...", mostRecentLocalUserID);
                heatmaps.put(HeatmapNew.HeatmapType.TYPE_A, legacyHeatmapTypeA);
                // Save as new file type
                saveNewHeatmapsFile();
            }
            // Append '.old' to legacy file name
            File oldFile = new File(filepathTypeAUsername + ".old");
            filepathTypeAUsername.renameTo(oldFile);
        }

        // Repeat for Type B
        File filepathTypeBUsername = new File(HEATMAP_FILES_DIR.toString(), mostRecentLocalUserName + "_TypeB.heatmap");
        if (filepathTypeBUsername.exists()) {
            // Load and convert the legacy heatmap file
            HeatmapNew legacyHeatmapTypeB = HeatmapNew.readLegacyV1HeatmapFile(filepathTypeBUsername);
            legacyHeatmapTypeB.setUserID(mostRecentLocalUserID);
            legacyHeatmapTypeB.setAccountType(mostRecentAccountType);
            legacyHeatmapTypeB.setHeatmapType(HeatmapNew.HeatmapType.TYPE_B);
            legacyHeatmapTypeB.setCurrentCombatLevel(mostRecentCombatLevel);

            // Check if heatmap has already been loaded
            boolean newerTypeBExists = heatmaps.get(HeatmapNew.HeatmapType.TYPE_B) != null;
            if (!newerTypeBExists){
                // Load heatmap from legacy file
                log.info("Loading Type B legacy (V1) heatmap file for user ID {}...", mostRecentLocalUserID);
                heatmaps.put(HeatmapNew.HeatmapType.TYPE_B, legacyHeatmapTypeB);
                // Save as new file type
                saveNewHeatmapsFile();
            }
            // Append '.old' to legacy file name
            File oldFile = new File(filepathTypeBUsername + ".old");
            filepathTypeBUsername.renameTo(oldFile);
        }

        // Append .old to Paths.get(HEATMAP_FILES_DIR, "Backups") folder if it exists
        File backupDir = Paths.get(HEATMAP_FILES_DIR.toString(), "Backups").toFile();
        if (backupDir.exists()) {
            File oldBackupDir = new File(backupDir.toString() + ".old");
            backupDir.renameTo(oldBackupDir);
        }
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
            saveCurrentHeatmapsFile();
        }
        clientToolbar.removeNavigation(toolbarButton);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
        if (gameStateChanged.getGameState() == GameState.LOGGING_IN) {
            shouldLoadHeatmaps = true;
            loadHeatmapsFuture = null;
        }

        // If you're at the login screen and heatmaps have already been loaded (implying that you were previously logged in, but now you're logged out)
        if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN && loadHeatmapsFuture != null && loadHeatmapsFuture.isDone()) {
            saveCurrentHeatmapsFile();
            loadHeatmapsFuture = null;
        }

        // Enable/disable "Write Heatmap" buttons if logged in/out
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN) {
            panel.setEnabledHeatmapButtons(true);
        }
        else {
            panel.setEnabledHeatmapButtons(false);
        }
    }

    @Subscribe
    public void onGameTick(GameTick gameTick) {
        if (client.getAccountHash() == -1) {
            return;
        }
        // If the player has changed, update player metadata
        if (mostRecentLocalUserID != client.getAccountHash()) {
            mostRecentLocalUserName = client.getLocalPlayer().getName();
            mostRecentLocalUserID = client.getAccountHash();
            mostRecentAccountType = client.getVarbitValue(Varbits.ACCOUNT_TYPE);
            mostRecentCombatLevel = client.getLocalPlayer().getCombatLevel();
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

        // Routines
        backupRoutine();
        autosaveRoutine();
        uploadHeatmapRoutine();

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
        if (chatMessage.getType() == ChatMessageType.PUBLICCHAT) {
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
                WorldPoint location = npcLootReceived.getNpc().getWorldLocation();
                int x = location.getX();
                int y = location.getY();

                int totalValue = itemStack.getQuantity() * itemManager.getItemPrice(itemStack.getId());
                if (heatmaps.get(HeatmapNew.HeatmapType.LOOT_VALUE) != null) {
                    heatmaps.get(HeatmapNew.HeatmapType.LOOT_VALUE).increment(x, y, totalValue);
                }
            }
        }
    }

    /**
     * Autosave the heatmap file and/or write the 'TYPE_A' and 'TYPE_B' heatmap images if it is the correct time to so
     */
    private void autosaveRoutine() {
        // Determine if autosave should happen
        if (heatmaps.keySet().isEmpty()){
            return;
        }
        int highestGameTimeTicks = 0;
        for (HeatmapNew.HeatmapType type : heatmaps.keySet()) {
            if (heatmaps.get(type).getGameTimeTicks() > highestGameTimeTicks) {
                highestGameTimeTicks = heatmaps.get(type).getGameTimeTicks();
            }
        }
        boolean shouldAutosaveFiles = highestGameTimeTicks % WorldHeatmapPlugin.HEATMAP_AUTOSAVE_FREQUENCY == 0;
        boolean shouldWriteImages = config.typeABImageAutosave() && highestGameTimeTicks % config.typeABImageAutosaveFrequency() == 0;

        // Autosave the heatmap file if it is the correct time to do so, or if image is about to be written
        if (shouldAutosaveFiles || shouldWriteImages) {
            saveCurrentHeatmapsFile();
        }

        // Autosave the 'TYPE_A' and 'TYPE_B' heatmap images if it is the correct time to do so
        if (shouldWriteImages) {
            File typeAImageFile = HeatmapFile.getCurrentImageFile(mostRecentLocalUserID, HeatmapNew.HeatmapType.TYPE_A);
            File typeBImageFile = HeatmapFile.getCurrentImageFile(mostRecentLocalUserID, HeatmapNew.HeatmapType.TYPE_B);

            // Write the image files
            if (config.isHeatmapTypeAEnabled()) {
                worldHeatmapPluginExecutor.execute(() -> HeatmapImage.writeHeatmapImage(heatmaps.get(HeatmapNew.HeatmapType.TYPE_A), typeAImageFile, false, config.heatmapAlpha(), config.heatmapSensitivity(), config.speedMemoryTradeoff(), new HeatmapProgressListener(this, HeatmapNew.HeatmapType.TYPE_A)));
            }
            if (config.isHeatmapTypeBEnabled()) {
                worldHeatmapPluginExecutor.execute(() -> HeatmapImage.writeHeatmapImage(heatmaps.get(HeatmapNew.HeatmapType.TYPE_B), typeBImageFile, false, config.heatmapAlpha(), config.heatmapSensitivity(), config.speedMemoryTradeoff(), new HeatmapProgressListener(this, HeatmapNew.HeatmapType.TYPE_B)));
            }
        }
    }

    /**
     * Backs up the heatmap file if it is the correct time to do so according to the backup frequency
     */
    private void backupRoutine() {
        // Determine if a backup should be made
        if (heatmaps.keySet().isEmpty()){
            return;
        }
        int highestGameTimeTicks = 0;
        for (HeatmapNew.HeatmapType type : heatmaps.keySet()) {
            if (heatmaps.get(type).getGameTimeTicks() > highestGameTimeTicks) {
                highestGameTimeTicks = heatmaps.get(type).getGameTimeTicks();
            }
        }

        // Make backup
        if (highestGameTimeTicks % config.heatmapBackupFrequency() == 0) {
            saveNewHeatmapsFile();
        }
    }

    /**
     * Updates the most recent heatmap file with the latest data. If the most recent file does not exist, it will create a new file.
     */
    private void saveCurrentHeatmapsFile() {
        File heatmapsFile = HeatmapFile.getLatestHeatmapFile(mostRecentLocalUserID);
        if (heatmapsFile == null) {
            heatmapsFile = HeatmapFile.getCurrentHeatmapFile(mostRecentLocalUserID);
        }
        File finalHeatmapsFile = heatmapsFile;
        worldHeatmapPluginExecutor.execute(() -> HeatmapNew.writeHeatmapsToFile(getEnabledHeatmaps(), finalHeatmapsFile, null));
    }

    /**
     * Saves the heatmaps to a new dated file, carrying over disabled/unprovided heatmaps from the most recently dated heatmaps file
     */
    private void saveNewHeatmapsFile() {
        // Write heatmaps to new file, carrying over disabled/unprovided heatmaps from previous heatmaps file
        File latestHeatmapsFile = HeatmapFile.getLatestHeatmapFile(mostRecentLocalUserID);
        File newHeatmapsFile = HeatmapFile.getCurrentHeatmapFile(mostRecentLocalUserID);
        log.debug("Backing up heatmaps to file: {}", latestHeatmapsFile);
        worldHeatmapPluginExecutor.execute(() -> HeatmapNew.writeHeatmapsToFile(getEnabledHeatmaps(), newHeatmapsFile, latestHeatmapsFile));
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
     * Initializes any enabled Heatmap types in the given Map of Heatmaps that weren't loaded
     *
     * @param heatmaps HashMap of HeatmapNew objects
     */
    public void initializeMissingHeatmaps(Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps) {
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
        log.debug("Initializing missing heatmaps: {}", String.join(", ", missingTypesNames));
        for (HeatmapNew.HeatmapType type : missingTypes) {
            heatmaps.put(type, new HeatmapNew(type, mostRecentLocalUserID, mostRecentAccountType));
        }
    }

    Collection<HeatmapNew> getEnabledHeatmaps() {
        return heatmaps.values().stream().filter(heatmap -> isHeatmapEnabled(heatmap.getHeatmapType())).collect(Collectors.toList());
    }

    Collection<HeatmapNew.HeatmapType> getEnabledHeatmapTypes() {
        List<HeatmapNew.HeatmapType> enabledTypes = new ArrayList<>();
        for (HeatmapNew.HeatmapType type : HeatmapNew.HeatmapType.values()) {
            if (isHeatmapEnabled(type)) {
                enabledTypes.add(type);
            }
        }
        return enabledTypes;
    }

    boolean isHeatmapEnabled(HeatmapNew.HeatmapType type) {
        Map<HeatmapNew.HeatmapType, Supplier> heatmapTypeSupplierMap = new HashMap<>();
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.TYPE_A, config::isHeatmapTypeAEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.TYPE_B, config::isHeatmapTypeBEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.XP_GAINED, config::isHeatmapXPGainedEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.TELEPORT_PATHS, config::isHeatmapTeleportPathsEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.TELEPORTED_TO, config::isHeatmapTeleportedToEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.TELEPORTED_FROM, config::isHeatmapTeleportedFromEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.LOOT_VALUE, config::isHeatmapLootValueEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.PLACES_SPOKEN_AT, config::isHeatmapPlacesSpokenAtEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS, config::isHeatmapRandomEventSpawnsEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.DEATHS, config::isHeatmapDeathsEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.NPC_DEATHS, config::isHeatmapNPCDeathsEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING, config::isHeatmapBobTheCatSightingEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.DAMAGE_TAKEN, config::isHeatmapDamageTakenEnabled);
        heatmapTypeSupplierMap.put(HeatmapNew.HeatmapType.DAMAGE_GIVEN, config::isHeatmapDamageGivenEnabled);
        return (boolean) heatmapTypeSupplierMap.getOrDefault(type, () -> false).get();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("worldheatmap")) {
            return;
        }
        Map<String, HeatmapNew.HeatmapType> configNameToHeatmapType = new HashMap<>();
        configNameToHeatmapType.put("isHeatmapTypeAEnabled", HeatmapNew.HeatmapType.TYPE_A);
        configNameToHeatmapType.put("isHeatmapTypeBEnabled", HeatmapNew.HeatmapType.TYPE_B);
        configNameToHeatmapType.put("isHeatmapXPGainedEnabled", HeatmapNew.HeatmapType.XP_GAINED);
        configNameToHeatmapType.put("isHeatmapTeleportPathsEnabled", HeatmapNew.HeatmapType.TELEPORT_PATHS);
        configNameToHeatmapType.put("isHeatmapTeleportedToEnabled", HeatmapNew.HeatmapType.TELEPORTED_TO);
        configNameToHeatmapType.put("isHeatmapTeleportedFromEnabled", HeatmapNew.HeatmapType.TELEPORTED_FROM);
        configNameToHeatmapType.put("isHeatmapLootValueEnabled", HeatmapNew.HeatmapType.LOOT_VALUE);
        configNameToHeatmapType.put("isHeatmapPlacesSpokenAtEnabled", HeatmapNew.HeatmapType.PLACES_SPOKEN_AT);
        configNameToHeatmapType.put("isHeatmapRandomEventSpawnsEnabled", HeatmapNew.HeatmapType.RANDOM_EVENT_SPAWNS);
        configNameToHeatmapType.put("isHeatmapDeathsEnabled", HeatmapNew.HeatmapType.DEATHS);
        configNameToHeatmapType.put("isHeatmapNPCDeathsEnabled", HeatmapNew.HeatmapType.NPC_DEATHS);
        configNameToHeatmapType.put("isHeatmapBobTheCatSightingEnabled", HeatmapNew.HeatmapType.BOB_THE_CAT_SIGHTING);
        configNameToHeatmapType.put("isHeatmapDamageTakenEnabled", HeatmapNew.HeatmapType.DAMAGE_TAKEN);
        configNameToHeatmapType.put("isHeatmapDamageGivenEnabled", HeatmapNew.HeatmapType.DAMAGE_GIVEN);

        HeatmapNew.HeatmapType toggledHeatmapType = configNameToHeatmapType.get(event.getKey());
        if (toggledHeatmapType != null) {
            boolean isEnabled = event.getNewValue() != null && event.getNewValue().equals("true");
            handleHeatmapConfigChanged(isEnabled, toggledHeatmapType);
        }
    }

    /**
     * Saves heatmap to file when enabled, and reads heatmap from file when enabled.
     * @param isHeatmapEnabled Whether the heatmap is enabled
     * @param heatmapType The type of heatmap
     */
    private void handleHeatmapConfigChanged(boolean isHeatmapEnabled, HeatmapNew.HeatmapType heatmapType){
        File heatmapsFile = HeatmapFile.getLatestHeatmapFile(mostRecentLocalUserID);

        if (isHeatmapEnabled) {
            log.debug("Enabling {} heatmap...", heatmapType);
            HeatmapNew heatmap = null;
            // Load the heatmap from the file if it exists
            if (heatmapsFile != null && heatmapsFile.exists()) {
                try {
                    heatmap = HeatmapNew.readHeatmapsFromFile(heatmapsFile, Collections.singletonList(heatmapType)).get(heatmapType);
                    heatmap.setUserID(mostRecentLocalUserID);
                    heatmap.setAccountType(mostRecentAccountType);
                    heatmap.setCurrentCombatLevel(mostRecentCombatLevel);
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
            if (heatmap != null) {
                heatmaps.put(heatmapType, heatmap);
            }
        } else {
            log.debug("Disabling {} heatmap...", heatmapType);
            if (heatmapsFile == null){
                heatmapsFile = HeatmapFile.getCurrentHeatmapFile(mostRecentLocalUserID);
            }
            HeatmapNew.writeHeatmapsToFile(List.of(heatmaps.get(heatmapType)), heatmapsFile, null);
            heatmaps.remove(heatmapType);
        }
        initializeMissingHeatmaps(heatmaps);
        panel.rebuild();
    }

    private void uploadHeatmapRoutine() {
        int uploadFrequency = 36_000; // Every 6 hours of game time

        if (!config.isHeatmapEnabled()){
            return;
        }

        // Get list of heatmaps to upload, if any
        List<HeatmapNew.HeatmapType> heatmapsToUpload = new ArrayList<>();
        for (HeatmapNew.HeatmapType type : heatmaps.keySet()) {
            if (isHeatmapEnabled(type)) {
                // Check if it's time to upload each heatmap based on the upload frequencies
                if (heatmaps.get(type).getGameTimeTicks() % uploadFrequency == 0) {
                    heatmapsToUpload.add(type);
                }
            }
        }

        if (heatmapsToUpload.isEmpty()) {
            return;
        }

        // Upload the heatmaps
        log.info("Uploading heatmaps {}...", heatmapsToUpload);
        if (uploadHeatmaps(heatmapsToUpload)){
            log.info("Heatmaps uploaded successfully");
        }
    }

    /**
     * Serializes heatmap as CSV, zip it, and then upload it to HEATMAP_SITE_API_ENDPOINT
     * @param heatmaps
     * @return
     */
    private boolean uploadHeatmaps(Collection<HeatmapNew.HeatmapType> heatmaps){
        if (heatmaps.isEmpty()){
            return false;
        }

        // Get HTTP POST output stream
        HttpURLConnection connection = null;

        try {
            URL url = new URL(HEATMAP_SITE_API_ENDPOINT);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/zip");
            connection.setDoOutput(true);
            connection.setDoInput(true);

            // Zip the CSV
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
                for (HeatmapNew.HeatmapType heatmapType : heatmaps) {
                    byte[] heatmapCSV = this.heatmaps.get(heatmapType).toCSV().getBytes();
                    ZipEntry zipEntry = new ZipEntry(heatmapType.toString() + "_HEATMAP.csv");
                    zipOutputStream.putNextEntry(zipEntry);
                    zipOutputStream.write(heatmapCSV);
                    zipOutputStream.closeEntry();
                }
            }

            // Write the zip to the output stream
            try (OutputStream outputStream = connection.getOutputStream()) {
                byteArrayOutputStream.writeTo(outputStream);
            }

            // Get the response
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return true;
            }
            else{
                log.error("Failed to upload heatmaps: HTTP {} {}", responseCode, connection.getResponseMessage());
            }
        } catch (IOException e) {
            log.error("Error uploading heatmap to " + HEATMAP_SITE_API_ENDPOINT, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        log.error("Failed to upload heatmaps");
        return false;
    }

    static class HeatmapProgressListener implements IIOWriteProgressListener {
        private final WorldHeatmapPlugin worldHeatmapPlugin;
        Color originalColor;
        HeatmapNew.HeatmapType heatmapType;

        WorldHeatmapPanel panel;

        public HeatmapProgressListener(WorldHeatmapPlugin worldHeatmapPlugin, HeatmapNew.HeatmapType heatmapType) {
            super();
            this.worldHeatmapPlugin = worldHeatmapPlugin;
            this.heatmapType = heatmapType;
            this.panel = worldHeatmapPlugin.panel;
            if (panel.writeHeatmapImageButtons.get(heatmapType) != null) {
                originalColor = panel.writeHeatmapImageButtons.get(heatmapType).getForeground();
            }
        }

        @Override
        public void imageStarted(ImageWriter source, int imageIndex) {
            panel.setEnabledHeatmapButtons(false);
            panel.writeHeatmapImageButtons.get(heatmapType).setText("Writing... 0%");
        }

        @Override
        public void imageProgress(ImageWriter source, float percentageDone) {
            panel.writeHeatmapImageButtons.get(heatmapType).setText(String.format("Writing... %.2f%%", percentageDone));
        }

        @Override
        public void imageComplete(ImageWriter source) {
            panel.setEnabledHeatmapButtons(true); // Had to do this early so that the color change would be visible
            panel.writeHeatmapImageButtons.get(heatmapType).setForeground(Color.GREEN);
            panel.writeHeatmapImageButtons.get(heatmapType).setText("Done");
            worldHeatmapPlugin.worldHeatmapPluginExecutor.schedule(() -> {
                SwingUtilities.invokeLater(() -> {
                    panel.writeHeatmapImageButtons.get(heatmapType).setText("Write Heatmap Image");
                    panel.writeHeatmapImageButtons.get(heatmapType).setForeground(originalColor);
                });
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
            panel.setEnabledHeatmapButtons(true); // Had to do this early so that the color change would be visible
            panel.writeHeatmapImageButtons.get(heatmapType).setForeground(Color.RED);
            panel.writeHeatmapImageButtons.get(heatmapType).setText("Error");
            worldHeatmapPlugin.worldHeatmapPluginExecutor.schedule(() -> {
                SwingUtilities.invokeLater(() -> {
                    panel.writeHeatmapImageButtons.get(heatmapType).setText("Write Heatmap Image");
                    panel.writeHeatmapImageButtons.get(heatmapType).setForeground(originalColor);
                });
            }, 2L, TimeUnit.SECONDS);
        }
    }
}
