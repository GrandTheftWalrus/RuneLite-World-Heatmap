package com.worldheatmap;

import com.google.inject.Provides;

import java.awt.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.inject.Inject;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.api.Varbits;
import net.runelite.http.api.worlds.World;

import java.awt.image.BufferedImage;
import java.util.concurrent.*;

import net.runelite.client.game.ItemManager;

import net.runelite.http.api.worlds.WorldResult;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
        name = "World Heatmap",
        internalName = "world-heatmap",
        legacyDataDirectory = "worldheatmap"
)
public class WorldHeatmapPlugin extends Plugin {
    private int lastX = 0;
    private int lastY = 0;
	private int lastZ = 0;
	private boolean shouldIncrementLoginLogout;
	private boolean shouldIncrementWorldHops;
	protected int currentPlayerAccountType;
	protected int currentPlayerCombatLevel;
	protected long currentLocalAccountHash;
	protected GameState previousGameState, previousPreviousGameState = GameState.UNKNOWN;
	protected String currentSeasonalType;
    protected Filepath pluginDirectory;
    protected Map<Heatmap.HeatmapType, Heatmap> heatmaps = new HashMap<>();
	// Last seen value of the enabledHeatmaps setting, for working out which types were toggled
	private Set<Heatmap.HeatmapType> enabledHeatmapTypes;
    private NavigationButton toolbarButton;
    protected WorldHeatmapPanel panel;
	private Instant timeLastDied;
    private final ArrayList<Integer> randomEventNPCIDs = new ArrayList<>(Arrays.asList(NpcID.GATHERING_EVENT_BEES_BEEKEEPER,
            NpcID.MACRO_COMBILOCK_PIRATE,
            NpcID.MACRO_DWARF,
            NpcID.PINBALL_INVITATION,
            NpcID.MACRO_GILES,
            NpcID.MACRO_GILES_UNDERWATER,
            NpcID.MACRO_MILES,
            NpcID.MACRO_MILES_UNDERWATER,
            NpcID.MACRO_NILES,
            NpcID.MACRO_NILES_UNDERWATER,
            NpcID.MACRO_PILLORY_GUARD,
            NpcID.POSTIE_PETE,
            NpcID.MACRO_HIGHWAYMAN,
            NpcID.MACRO_HIGHWAYMAN_UNDERWATER,
            NpcID.MACRO_DRILLDEMON,
            NpcID.MACRO_FORESTER_INVITATION,
            NpcID.MACRO_FROG_CRIER,
			NpcID.MACRO_PIRATE,
            NpcID.MACRO_GENI,
            NpcID.MACRO_GENI_UNDERWATER,
            NpcID.MACRO_JEKYLL,
            NpcID.MACRO_JEKYLL_UNDERWATER,
            NpcID.MACRO_EVIL_BOB_OUTSIDE,
            NpcID.MACRO_EVIL_BOB_ISLAND,
            NpcID.MACRO_EVIL_BOB_PRISON,
			NpcID.MACRO_BEEKEEPER_INVITATION,
			NpcID.MACRO_FORESTER_INVITATION,
            NpcID.MACRO_GRAVEDIGGER_INVITATION,
            NpcID.MACRO_CUBENPC,
            NpcID.MACRO_MYSTERIOUS_OLD_MAN,
            NpcID.MACRO_MYSTERIOUS_OLD_MAN_UNDERWATER,
            NpcID.MACRO_MAZE_INVITATION,
            NpcID.MACRO_MIME_INVITATION,
            NpcID.MACRO_COUNTCHECK_SURFACE,
            NpcID.MACRO_COUNTCHECK_UNDERWATER,
            NpcID.PATTERN_INVITATION,
            NpcID.MACRO_SANDWICH_LADY_NPC,
            NpcID.MACRO_TRIFFIDSEED,
			NpcID.MACRO_MAGNESON_INVITATION));
    Map<Integer, Instant> timeLastSeenBobTheCatPerWorld = new HashMap<>();
    @Inject
    ItemManager itemManager;
    private int[] previousXP = new int[Skill.values().length];
    protected String currentPlayerName;
	private CompletableFuture<Void> loading = new CompletableFuture<>();
	private boolean isLoading;
	HeatmapFileManager heatmapFileManager;

	@Inject
    private Client client;

    @Inject
    protected ScheduledExecutorService executor;

    @Inject
    WorldHeatmapConfig config;

	@Inject
	OkHttpClient okHttpClient;

    @Inject
    private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HeatmapOverlayCache overlayCache;

	@Inject
	private HeatmapSceneOverlay sceneOverlay;

	@Inject
	private HeatmapMinimapOverlay minimapOverlay;

	@Inject
	private HeatmapWorldMapOverlay worldMapOverlay;

	@Inject
	private WorldMapHeatmapSelector worldMapSelector;

	@Inject
	private WorldMapHeatmapList worldMapList;

	@Inject
	private GlobalHeatmapLoader globalHeatmapLoader;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private WorldService worldService;

	@Inject
	protected ClientThread clientThread;

	@Inject
	protected ConfigManager configManager;

	@Inject
	protected ChatMessageManager chatMessageManager;

	@Provides
    WorldHeatmapConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WorldHeatmapConfig.class);
    }

	protected void loadHeatmaps() {
		isLoading = true;
		// Make sure player metadata is loaded and up to date
		assert currentLocalAccountHash != 0 && currentLocalAccountHash != -1;
		assert currentPlayerName != null && !currentPlayerName.isBlank();
		assert currentPlayerCombatLevel >= 3;
		assert currentPlayerAccountType >= 0 && currentPlayerAccountType <= 10;
		assert currentSeasonalType != null;

		// First, perform any necessary fixes to the heatmaps
		heatmapFileManager.fixHeatmapsFiles(currentLocalAccountHash, currentPlayerName);

        log.info("Loading most recent {}heatmaps under user ID {}...", currentSeasonalType.isBlank() ? "" : currentSeasonalType + " ", currentLocalAccountHash);
        Filepath latestHeatmapsFile = heatmapFileManager.getLatestFile(currentLocalAccountHash, currentSeasonalType);

        // Load all heatmaps from the file
        if (latestHeatmapsFile != null && latestHeatmapsFile.exists()) {
			try {
				heatmaps = heatmapFileManager.readHeatmapsFromFile(latestHeatmapsFile, getEnabledHeatmapTypes());
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			for (Heatmap heatmap : heatmaps.values()){
				// Set metadata for each heatmap in case they were wrong or missing
                heatmap.setUserID(currentLocalAccountHash);
                heatmap.setAccountType(currentPlayerAccountType);
                heatmap.setCurrentCombatLevel(currentPlayerCombatLevel);
				heatmap.setSeasonalType(currentSeasonalType);
            }
        }

        initializeMissingHeatmaps(heatmaps);
        panel.setEnabledHeatmapButtons(true);
		// Initialize previousXP values
		for (Skill skill : Skill.values()) {
			previousXP[skill.ordinal()] = client.getSkillExperience(skill);
		}
		panel.setEnabledHeatmapButtons(true);
		loading.complete(null);
		isLoading = false;
    }

    @Override
    protected void startUp() throws IOException {
		migrateHeatmapToggles();
		migrateOpacitySettings();
		enabledHeatmapTypes = EnumSet.noneOf(Heatmap.HeatmapType.class);
		enabledHeatmapTypes.addAll(config.enabledHeatmaps());
        panel = new WorldHeatmapPanel(this);
        panel.rebuild();
		pluginDirectory = getPluginDirectory();
		heatmapFileManager = new HeatmapFileManager(this, pluginDirectory);
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/WorldHeatmap.png");
        toolbarButton = NavigationButton.builder()
                .tooltip("World Heatmap")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(toolbarButton);
        panel.setEnabledHeatmapButtons(false);
		spriteManager.addSpriteOverrides(WorldHeatmapSprites.values());
		overlayManager.add(sceneOverlay);
		overlayManager.add(minimapOverlay);
		overlayManager.add(worldMapOverlay);
		overlayManager.add(worldMapList);
		mouseManager.registerMouseListener(worldMapList);
		clientThread.invoke(this::displayUpdateMessage);

		if (client.getGameState() == GameState.LOGGED_IN) {
			loading = new CompletableFuture<>();
		}
    }

	/**
	 * Displays a message to the user about the latest update.
	 * Only displays the message once per update.
	 */
	private void displayUpdateMessage() {
		String noticeKey = "shownNoticeV1.6.0";
		if (configManager.getConfiguration("worldheatmap", noticeKey) == null) {
			// Send a message in game chat
			final String message = new ChatMessageBuilder()
				.img(11863)
				.append(Color.decode("#00a12b"), "World Heatmap has been updated to 1.6.0! We would love your help in crowdsourcing data for " +
					"the global heatmap by opting-in in the config. Also, if you've played Leagues V, please log into " +
					"a Leagues V world again before it ends, if possible, in order for an automatic fix to occur.")
				.build();
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(message)
				.build());
			configManager.setConfiguration("worldheatmap", noticeKey, "shown");
		}
	}

    @Override
    protected void shutDown() {
        if (heatmaps != null && !heatmaps.isEmpty()) {
			panel.setEnabledHeatmapButtons(false);
			executor.execute(this::saveHeatmapsFile);
			executor.execute(() -> heatmaps = new HashMap<>());
        }
        clientToolbar.removeNavigation(toolbarButton);
		spriteManager.removeSpriteOverrides(WorldHeatmapSprites.values());
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(worldMapOverlay);
		overlayManager.remove(worldMapList);
		mouseManager.unregisterMouseListener(worldMapList);
		overlayCache.clear();
		clientThread.invoke(worldMapSelector::hide);
		clientThread.invoke(globalHeatmapLoader::clear);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged) {
		GameState gameState = gameStateChanged.getGameState();

		boolean justLoggedIn = gameState == GameState.LOGGED_IN &&
			previousGameState == GameState.LOADING &&
			previousPreviousGameState == GameState.LOGGING_IN;
		boolean justHopped = gameState == GameState.LOGGED_IN &&
			previousGameState == GameState.LOADING &&
			previousPreviousGameState == GameState.HOPPING;

		// This is when to load the heatmaps
		if (justLoggedIn || justHopped) {
			// Schedule the loading of the heatmap files
			loading = new CompletableFuture<>();
		}

		// LOGIN_LOGOUT
		if (justLoggedIn && isHeatmapEnabled(Heatmap.HeatmapType.LOGIN_LOGOUT)) {
			shouldIncrementLoginLogout = true;
		}

		// WORLD_HOPS
		if (justHopped && isHeatmapEnabled(Heatmap.HeatmapType.WORLD_HOPS)) {
			shouldIncrementWorldHops = true;
		}

		// This is when to save & unload the heatmaps
		boolean heatmapsLoaded = heatmaps != null && !heatmaps.isEmpty();
        if (heatmapsLoaded &&
			gameState == GameState.HOPPING ||
			gameState == GameState.LOGIN_SCREEN) {
			panel.setEnabledHeatmapButtons(false);
			executor.execute(this::saveHeatmapsFile);
			executor.execute(this::reinitializeVariables);
			executor.execute(() -> heatmaps = new HashMap<>());
		}

		previousPreviousGameState = previousGameState;
		previousGameState = gameState;
    }

	private void reinitializeVariables()
	{
		lastX = 0;
		lastY = 0;
		previousXP = new int[Skill.values().length];
		timeLastSeenBobTheCatPerWorld = new HashMap<>();
		loading = new CompletableFuture<>();
	}

	@Subscribe
	public void onAccountHashChanged(AccountHashChanged event) {
		currentLocalAccountHash = client.getAccountHash();
		SwingUtilities.invokeLater(panel::updatePlayerID);
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event) {
		updateSeasonalType();
	}

	public void updateSeasonalType() {
		String seasonalType;
		boolean isSeasonal = client.getWorldType().contains(WorldType.SEASONAL) ||
			client.getWorldType().contains(WorldType.BETA_WORLD) ||
			client.getWorldType().contains(WorldType.TOURNAMENT_WORLD);
		if (isSeasonal) {
			WorldResult worlds = worldService.getWorlds();
			assert worlds != null;
			World world = worlds.findWorld(client.getWorld());
			assert world != null;
			String worldActivity = world.getActivity();
			if (worldActivity != null && !worldActivity.isBlank()) {
				seasonalType = worldActivity.split(" - ")[0].replaceAll("\\s", "_").toUpperCase();
			}
			else {
				seasonalType = "UNKNOWN_SEASONAL";
			}
		}
		else {
			seasonalType = "";
		}
		currentSeasonalType = seasonalType;
		loading.complete(null);
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event) {
		if (event.getPlayer() == client.getLocalPlayer() && client.getLocalPlayer() != null) {
			updatePlayerMetadata();
		}
	}

	public void updatePlayerMetadata() {
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null) {
			return;
		}
		currentPlayerName = localPlayer.getName();
		currentPlayerCombatLevel = localPlayer.getCombatLevel();
		currentPlayerAccountType = client.getVarbitValue(Varbits.ACCOUNT_TYPE);
	}

	@Subscribe
	public void onClientTick(ClientTick clientTick) {
		globalHeatmapLoader.update();
		worldMapSelector.onClientTick();
	}

    @Subscribe
    public void onGameTick(GameTick gameTick) {
		// Start loading them heatmaps if not already
		if (!loading.isDone() && !isLoading) {
			currentLocalAccountHash = client.getAccountHash();
			SwingUtilities.invokeLater(panel::updatePlayerID);
			updatePlayerMetadata();
			updateSeasonalType();
			executor.execute(this::loadHeatmaps);
		}
		// Runs before the loaded check below so the overlays drop stale data while heatmaps are (re)loading
		overlayCache.onGameTick();

		// The following code requires the heatmap files to have been loaded
		if (!loading.isDone() || heatmaps == null || heatmaps.isEmpty()) {
			return;
		}

        // Increment game time ticks of each heatmap
        for (Heatmap.HeatmapType type : heatmaps.keySet()) {
            heatmaps.get(type).incrementGameTimeTicks();
        }

        WorldPoint currentCoords = client.getLocalPlayer().getWorldLocation();
        int currentX = currentCoords.getX();
        int currentY = currentCoords.getY();
		int currentZ = currentCoords.getPlane();
        boolean playerMovedSinceLastTick = (currentX != lastX || currentY != lastY || currentZ != lastZ);

		if (shouldIncrementLoginLogout && heatmaps.get(Heatmap.HeatmapType.LOGIN_LOGOUT) != null) {
			heatmaps.get(Heatmap.HeatmapType.LOGIN_LOGOUT).increment(currentX, currentY, currentZ);
			shouldIncrementLoginLogout = false;
		}
		if (shouldIncrementWorldHops && heatmaps.get(Heatmap.HeatmapType.WORLD_HOPS) != null) {
			heatmaps.get(Heatmap.HeatmapType.WORLD_HOPS).increment(currentX, currentY, currentZ);
			shouldIncrementWorldHops = false;
		}

        /* When running, players cover more than one tile per tick, which creates spotty paths.
         * We fix this by drawing a line between the current coordinates and the previous coordinates,
         * but we have to be sure that the player indeed ran from point A to point B, differentiating the movement from teleportation.
         * Since it's too hard to check if the player is actually running, we'll just check if the distance covered since last tick
         * was less than 4 tiles
         */
        int diagDistance = Utils.diagonalDistance(new Point(lastX, lastY), new Point(currentX, currentY));
        if (diagDistance <= 3 && currentZ == lastZ) {
            // Gets all the tiles between last position and new position
            for (Point tile : Utils.getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY))) {
                // TYPE_A
                if (playerMovedSinceLastTick && isHeatmapEnabled(Heatmap.HeatmapType.TYPE_A) && heatmaps.get(Heatmap.HeatmapType.TYPE_A) != null) {
                    heatmaps.get(Heatmap.HeatmapType.TYPE_A).increment(tile.getX(), tile.getY(), currentZ);
                }

                // TYPE_B
                if (isHeatmapEnabled(Heatmap.HeatmapType.TYPE_B) && heatmaps.get(Heatmap.HeatmapType.TYPE_B) != null) {
                    heatmaps.get(Heatmap.HeatmapType.TYPE_B).increment(tile.getX(), tile.getY(), currentZ);
                }
            }
        }

		boolean isRecentlyDead = timeLastDied != null && Instant.now().isBefore(timeLastDied.plusSeconds(10));

        // TELEPORT_PATHS
        if (isHeatmapEnabled(Heatmap.HeatmapType.TELEPORT_PATHS) &&
			heatmaps.get(Heatmap.HeatmapType.TELEPORT_PATHS) != null &&
			diagDistance > 15 &&
			Utils.isInOverworld(new Point(lastX, lastY)) &&
			Utils.isInOverworld(new Point(currentX, currentY)) && //we don't draw lines between the overworld and caves etc.
			currentZ == lastZ &&
			!isRecentlyDead
		)
        {
            if (isHeatmapEnabled(Heatmap.HeatmapType.TELEPORT_PATHS)) {
                for (Point tile : Utils.getPointsBetween(new Point(lastX, lastY), new Point(currentX, currentY))) {
                    heatmaps.get(Heatmap.HeatmapType.TELEPORT_PATHS).increment(tile.getX(), tile.getY(), currentZ);
                }
            }
        }

        // TELEPORTED_TO and TELEPORTED_FROM
		// We do count teleports between different planes for these, since in the overworld, the planes are (x, y)
		// aligned, and unlike TELEPORT_PATHS, we wouldn't have to draw a line between planes
        if (diagDistance > 15 &&
			Utils.isInOverworld(new Point(lastX, lastY)) &&
			Utils.isInOverworld(new Point(currentX, currentY)) && //we only track teleports between overworld tiles
			!isRecentlyDead)
        {
            if (isHeatmapEnabled(Heatmap.HeatmapType.TELEPORTED_TO) && heatmaps.get(Heatmap.HeatmapType.TELEPORTED_TO) != null) {
                heatmaps.get(Heatmap.HeatmapType.TELEPORTED_TO).increment(currentX, currentY, currentZ);
            }
            if (isHeatmapEnabled(Heatmap.HeatmapType.TELEPORTED_FROM) && heatmaps.get(Heatmap.HeatmapType.TELEPORTED_FROM) != null) {
                heatmaps.get(Heatmap.HeatmapType.TELEPORTED_FROM).increment(lastX, lastY, lastZ);
            }
        }

        // Routines
		executor.execute(this::backupRoutine);
		executor.execute(this::autosaveRoutine);
		executor.execute(this::uploadHeatmapRoutine);

        // Update panel step counter
        SwingUtilities.invokeLater(panel::updateCounts);

        // Update memory usage + heatmap age tooltips
		SwingUtilities.invokeLater(panel::updateMemoryUsageLabels);

        // Update last coords
        lastX = currentX;
        lastY = currentY;
		lastZ = currentZ;
    }

	@Subscribe
	public void onWidgetClosed(WidgetClosed widgetClosed) {
		// TRADES
		if (widgetClosed.getGroupId() == InterfaceID.TRADECONFIRM) {
			if (isHeatmapEnabled(Heatmap.HeatmapType.TRADES) && heatmaps.get(Heatmap.HeatmapType.TRADES) != null) {
				WorldPoint loc = client.getLocalPlayer().getWorldLocation();
				heatmaps.get(Heatmap.HeatmapType.TRADES).increment(loc.getX(), loc.getY(), loc.getPlane());
			}
		}
	}

    @Subscribe
    public void onActorDeath(ActorDeath actorDeath) {
        if (actorDeath.getActor() instanceof Player) {
            Player deadPlayer = (Player) actorDeath.getActor();
            if (deadPlayer.getId() == client.getLocalPlayer().getId()) {
				timeLastDied = Instant.now();
                // DEATHS
                if (isHeatmapEnabled(Heatmap.HeatmapType.DEATHS)) {
					WorldPoint loc = deadPlayer.getWorldLocation();
                    heatmaps.get(Heatmap.HeatmapType.DEATHS).increment(loc.getX(), loc.getY(), loc.getPlane());
                }
            }
        } else if (actorDeath.getActor() instanceof NPC) {
			NPC deadNPC = (NPC) actorDeath.getActor();
            if (heatmaps.get(Heatmap.HeatmapType.NPC_DEATHS) != null && isHeatmapEnabled(Heatmap.HeatmapType.NPC_DEATHS)) {
                // NPC_DEATHS
				WorldPoint loc = deadNPC.getWorldLocation();
                heatmaps.get(Heatmap.HeatmapType.NPC_DEATHS).increment(loc.getX(), loc.getY(), loc.getPlane());
            }
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied hitsplatApplied) {
		WorldPoint localPlayerLoc = client.getLocalPlayer().getWorldLocation();
		Actor hitsplatActor = hitsplatApplied.getActor();
		Hitsplat hitsplat = hitsplatApplied.getHitsplat();
		int localId = client.getLocalPlayer().getId();
		boolean isMeTakingDamage = hitsplatActor instanceof Player && ((Player) hitsplatActor).getId() == localId;
        if (hitsplat.getAmount() == 0 || !hitsplat.isMine()) {
            return;
        }

		// Get list of Players interacting with us (potentially attacking us)
		IndexedObjectSet<? extends Player> players = client.getWorldView(WorldView.TOPLEVEL).players();
		Player[] interactingPlayers = players.stream().filter(p -> {
			if (p.getInteracting() instanceof Player){
				return ((Player) p.getInteracting()).getId() == localId;
			}
			else {
				return false;
			}
		}).toArray(Player[]::new);

		// PVP_DAMAGE_TAKEN
		if (isMeTakingDamage && interactingPlayers.length > 0) {
			if (isHeatmapEnabled(Heatmap.HeatmapType.PVP_DAMAGE_TAKEN)) {
				heatmaps.get(Heatmap.HeatmapType.PVP_DAMAGE_TAKEN).increment(localPlayerLoc.getX(), localPlayerLoc.getY(), localPlayerLoc.getPlane(), hitsplat.getAmount());
			}
		}

		// PVP_DAMAGE_GIVEN
		if (hitsplatActor instanceof Player && hitsplat.isMine() && !isMeTakingDamage) {
			if (isHeatmapEnabled(Heatmap.HeatmapType.PVP_DAMAGE_GIVEN)) {
				heatmaps.get(Heatmap.HeatmapType.PVP_DAMAGE_GIVEN).increment(localPlayerLoc.getX(), localPlayerLoc.getY(), localPlayerLoc.getPlane(), hitsplat.getAmount());
			}
		}

        // NPC_DAMAGE_TAKEN
		if (isMeTakingDamage & interactingPlayers.length == 0) {
			if (isHeatmapEnabled(Heatmap.HeatmapType.NPC_DAMAGE_TAKEN)) {
				heatmaps.get(Heatmap.HeatmapType.NPC_DAMAGE_TAKEN).increment(localPlayerLoc.getX(), localPlayerLoc.getY(), localPlayerLoc.getPlane(), hitsplat.getAmount());
			}
		}

		// NPC_DAMAGE_GIVEN
		if (hitsplatApplied.getActor() instanceof NPC && hitsplat.isMine()) {
			if (isHeatmapEnabled(Heatmap.HeatmapType.NPC_DAMAGE_GIVEN)) {
				heatmaps.get(Heatmap.HeatmapType.NPC_DAMAGE_GIVEN).increment(localPlayerLoc.getX(), localPlayerLoc.getY(), localPlayerLoc.getPlane(), hitsplat.getAmount());
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
            if (isHeatmapEnabled(Heatmap.HeatmapType.PLACES_SPOKEN_AT) && heatmaps.get(Heatmap.HeatmapType.PLACES_SPOKEN_AT) != null) {
				WorldPoint loc = client.getLocalPlayer().getWorldLocation();
                heatmaps.get(Heatmap.HeatmapType.PLACES_SPOKEN_AT).increment(loc.getX(), loc.getY(), loc.getPlane());
            }
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged statChanged) {
        // NOTE: this happens 24 times when you log in, and at such time, the heatmaps haven't been loaded in, so you can't call .get() on them
        // Get difference between previous and current XP
        int skillIndex = statChanged.getSkill().ordinal();
        int xpDifference = client.getSkillExperience(statChanged.getSkill()) - previousXP[skillIndex];

        // Update previous XP
        previousXP[skillIndex] = client.getSkillExperience(statChanged.getSkill());

        // XP_GAINED
        if (isHeatmapEnabled(Heatmap.HeatmapType.XP_GAINED) && heatmaps.get(Heatmap.HeatmapType.XP_GAINED) != null) {
			WorldPoint loc = client.getLocalPlayer().getWorldLocation();
            heatmaps.get(Heatmap.HeatmapType.XP_GAINED).increment(loc.getX(), loc.getY(), loc.getPlane(), xpDifference);
        }
    }

    @Subscribe
    public void onNpcSpawned(final NpcSpawned npcSpawned) {
        // Currently it counts all random event spawns, not just random events meant for the local player
        if (randomEventNPCIDs.contains(npcSpawned.getNpc().getId())) {
            // RANDOM_EVENT_SPAWNS
            if (isHeatmapEnabled(Heatmap.HeatmapType.RANDOM_EVENT_SPAWNS) && heatmaps.get(Heatmap.HeatmapType.RANDOM_EVENT_SPAWNS) != null) {
				WorldPoint loc = npcSpawned.getNpc().getWorldLocation();
                heatmaps.get(Heatmap.HeatmapType.RANDOM_EVENT_SPAWNS).increment(loc.getX(), loc.getY(), loc.getPlane());
            }
        }

        // BOB_THE_CAT_SIGHTING
        if (isHeatmapEnabled(Heatmap.HeatmapType.BOB_THE_CAT_SIGHTING) && npcSpawned.getNpc().getId() == NpcID.DEATH_GROWNCAT_BLACK_VIS) {
            // Only count Bob the Cat sightings once per hour per world
            if (timeLastSeenBobTheCatPerWorld.get(client.getWorld()) == null || Instant.now().isAfter(timeLastSeenBobTheCatPerWorld.get(client.getWorld()).plusSeconds(60))) {
                if (heatmaps.get(Heatmap.HeatmapType.BOB_THE_CAT_SIGHTING) != null) {
					WorldPoint loc = npcSpawned.getNpc().getWorldLocation();
                    heatmaps.get(Heatmap.HeatmapType.BOB_THE_CAT_SIGHTING).increment(loc.getX(), loc.getY(), loc.getPlane());
                    timeLastSeenBobTheCatPerWorld.put(client.getWorld(), Instant.now());
                }
            }
        }
    }

    @Subscribe
    public void onNpcLootReceived(final NpcLootReceived npcLootReceived) {
        // LOOT_VALUE
        if (isHeatmapEnabled(Heatmap.HeatmapType.LOOT_VALUE)) {
            for (ItemStack itemStack : npcLootReceived.getItems()) {
                WorldPoint location = npcLootReceived.getNpc().getWorldLocation();
                int x = location.getX();
                int y = location.getY();
				int z = location.getPlane();

                long totalValue = itemStack.getQuantity() * itemManager.getItemPrice(itemStack.getId());
                if (heatmaps.get(Heatmap.HeatmapType.LOOT_VALUE) != null) {
                    heatmaps.get(Heatmap.HeatmapType.LOOT_VALUE).increment(x, y, z, totalValue);
                }
            }
        }
    }

    /**
     * Autosave the heatmap file and/or write the 'TYPE_A' and 'TYPE_B' heatmap images if it is the correct time to so
     */
    private void autosaveRoutine() {
        // Determine if autosave should happen
		final int AUTOSAVE_FREQUENCY = 3000; // Autosave every 30 minutes of game time
        if (heatmaps.keySet().isEmpty()) {
            return;
        }
        int highestGameTimeTicks = 0;
        for (Heatmap.HeatmapType type : heatmaps.keySet()) {
            if (heatmaps.get(type).getGameTimeTicks() > highestGameTimeTicks) {
                highestGameTimeTicks = heatmaps.get(type).getGameTimeTicks();
            }
        }
        boolean shouldWriteImages = config.typeABImageAutosave() &&
			highestGameTimeTicks % config.typeABImageAutosaveFrequency() == 0 &&
			highestGameTimeTicks != 0;
		boolean shouldAutosaveFiles = highestGameTimeTicks % AUTOSAVE_FREQUENCY == 0 && highestGameTimeTicks != 0;

        // Autosave the heatmap file if it is the correct time to do so, or if image is about to be written
        if (shouldAutosaveFiles || shouldWriteImages) {
			executor.execute(this::saveHeatmapsFile);
        }

        // Autosave the 'TYPE_A' and 'TYPE_B' heatmap images if it is the correct time to do so
        if (shouldWriteImages) {
            Filepath typeAImageFile = heatmapFileManager.getNewImageFile(currentLocalAccountHash, Heatmap.HeatmapType.TYPE_A, currentSeasonalType);
            Filepath typeBImageFile = heatmapFileManager.getNewImageFile(currentLocalAccountHash, Heatmap.HeatmapType.TYPE_B, currentSeasonalType);

            // Write the image files
            if (isHeatmapEnabled(Heatmap.HeatmapType.TYPE_A)) {
                executor.execute(() -> HeatmapImage.writeHeatmapImage(okHttpClient, heatmaps.get(Heatmap.HeatmapType.TYPE_A), typeAImageFile, false, config.isBlueMapEnabled(), config.imageOpacity() / 100.0, config.heatmapSensitivity(), config.speedMemoryTradeoff(), new HeatmapProgressListener(this, Heatmap.HeatmapType.TYPE_A)));
            }
            if (isHeatmapEnabled(Heatmap.HeatmapType.TYPE_B)) {
                executor.execute(() -> HeatmapImage.writeHeatmapImage(okHttpClient, heatmaps.get(Heatmap.HeatmapType.TYPE_B), typeBImageFile, false, config.isBlueMapEnabled(), config.imageOpacity() / 100.0, config.heatmapSensitivity(), config.speedMemoryTradeoff(), new HeatmapProgressListener(this, Heatmap.HeatmapType.TYPE_B)));
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
        for (Heatmap.HeatmapType type : heatmaps.keySet()) {
            if (heatmaps.get(type).getGameTimeTicks() > highestGameTimeTicks) {
                highestGameTimeTicks = heatmaps.get(type).getGameTimeTicks();
            }
        }

        // Make new backup
        if (highestGameTimeTicks % config.heatmapBackupFrequency() == 0 && highestGameTimeTicks != 0) {
            executor.execute(this::saveNewHeatmapsFile);
        }
    }

    /**
     * Updates the most recent heatmap file with the latest data, renaming it after the current date and time.
	 * If a most recent file does not exist, it will create a new file.
     */
    protected void saveHeatmapsFile() {
		saveHeatmapsFile(getEnabledHeatmaps());
	}

	private void saveHeatmapsFile(Collection<Heatmap> heatmapsToSave) {
		if (heatmapsToSave == null || heatmapsToSave.isEmpty()) {
			return;
		}

		String seasonalType = heatmapsToSave.iterator().next().getSeasonalType();
		long localAccountHash = heatmapsToSave.iterator().next().getUserID();
		Filepath latestFile = heatmapFileManager.getLatestFile(localAccountHash, seasonalType);

		// If there is no latest file, create a new file
		if (latestFile == null || !latestFile.exists()) {
			saveNewHeatmapsFile(heatmapsToSave);
			return;
		}

		heatmapFileManager.writeHeatmapsToFile(heatmapsToSave, latestFile);

		// Rename the latest file to be the current date and time, unless dated into the future
		Filepath newFile = heatmapFileManager.getCurrentFile(localAccountHash, seasonalType);
		try {
			latestFile.moveTo(newFile);
		} catch (IOException e) {
			log.error("Failed to rename latest heatmap file {} to {}", latestFile.getFileName(), newFile.getFileName());
		}
    }

    /**
     * Saves the heatmaps to a new dated file, carrying over disabled/unprovided heatmaps from the most recently dated heatmaps file
     */
    protected void saveNewHeatmapsFile() {
		saveNewHeatmapsFile(getEnabledHeatmaps());
	}

	private void saveNewHeatmapsFile(Collection<Heatmap> heatmapsToSave) {
		if (heatmapsToSave == null || heatmapsToSave.isEmpty()) {
			return;
		}
		String seasonalType = heatmapsToSave.iterator().next().getSeasonalType();
		long localAccountHash = heatmapsToSave.iterator().next().getUserID();

        // Write heatmaps to new file, carrying over disabled/unprovided heatmaps from previous heatmaps file
        Filepath latestFile = heatmapFileManager.getLatestFile(localAccountHash, seasonalType);
        Filepath newFile = heatmapFileManager.getNewFile(localAccountHash, seasonalType);
        heatmapFileManager.writeHeatmapsToFile(heatmapsToSave, newFile, latestFile);
    }

	/**
     * Initializes any enabled Heatmap types in the given Map of Heatmaps that weren't loaded
     *
     * @param heatmaps HashMap of HeatmapNew objects
     */
    public void initializeMissingHeatmaps(Map<Heatmap.HeatmapType, Heatmap> heatmaps) {
        // Get the heatmaps that are enabled but were not loaded
        ArrayList<Heatmap.HeatmapType> missingTypes = new ArrayList<>();
        for (Heatmap.HeatmapType type : Heatmap.HeatmapType.values()) {
            if (isHeatmapEnabled(type) && (!heatmaps.containsKey(type) || heatmaps.get(type) == null)) {
                missingTypes.add(type);
            }
        }
        if (missingTypes.isEmpty())
            return;

        List<String> missingTypesNames = new ArrayList<>();
        for (Heatmap.HeatmapType type : missingTypes) {
            missingTypesNames.add(type.toString());
        }
        log.info("Initializing missing heatmaps: {}", String.join(", ", missingTypesNames));
        for (Heatmap.HeatmapType type : missingTypes) {
            heatmaps.put(type, new Heatmap(type, currentLocalAccountHash, currentPlayerAccountType, currentSeasonalType, currentPlayerCombatLevel));
        }
    }

    /**
     * Returns the given heatmap if loading has finished and it is loaded, otherwise null (also for a null type).
     */
    Heatmap getLoadedHeatmap(Heatmap.HeatmapType type) {
        Map<Heatmap.HeatmapType, Heatmap> loadedHeatmaps = heatmaps;
        if (type == null || !loading.isDone() || loadedHeatmaps == null) {
            return null;
        }
        return loadedHeatmaps.get(type);
    }

    Collection<Heatmap> getEnabledHeatmaps() {
        return heatmaps.values().stream().filter(heatmap -> isHeatmapEnabled(heatmap.getHeatmapType())).collect(Collectors.toList());
    }

    Collection<Heatmap.HeatmapType> getEnabledHeatmapTypes() {
        List<Heatmap.HeatmapType> enabledTypes = new ArrayList<>();
        for (Heatmap.HeatmapType type : Heatmap.HeatmapType.values()) {
            if (isHeatmapEnabled(type)) {
                enabledTypes.add(type);
            }
        }
        return enabledTypes;
    }

    boolean isHeatmapEnabled(Heatmap.HeatmapType type) {
        return config.enabledHeatmaps().contains(type);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals(WorldHeatmapConfig.GROUP)) {
            return;
        }

        // Handled before the null check below, since resetting the setting unsets it
        if (event.getKey().equals("enabledHeatmaps")) {
            handleEnabledHeatmapsChanged();
            return;
        }

        if (event.getNewValue() == null) {
            return;
        }

        if (event.getKey().startsWith("overlay") || event.getKey().startsWith("show") || event.getKey().equals("heatmapSensitivity")) {
            overlayCache.invalidate();
        }
    }

    /**
     * Loads/unloads whichever heatmap types were added to or removed from the enabled heatmaps setting.
     */
    private void handleEnabledHeatmapsChanged() {
        Set<Heatmap.HeatmapType> nowEnabled = EnumSet.noneOf(Heatmap.HeatmapType.class);
        nowEnabled.addAll(config.enabledHeatmaps());
        Set<Heatmap.HeatmapType> wasEnabled = enabledHeatmapTypes;
        enabledHeatmapTypes = nowEnabled;
        if (wasEnabled == null) {
            return;
        }

        for (Heatmap.HeatmapType type : Heatmap.HeatmapType.values()) {
            boolean enabled = nowEnabled.contains(type);
            if (enabled != wasEnabled.contains(type)) {
                executor.execute(() -> handleHeatmapToggled(enabled, type));
            }
        }
    }

    /**
     * One-time carry-over of the old per-heatmap on/off settings (removed in favour of the enabledHeatmaps set).
     * Keys that were never set were at their default of enabled. The old keys are deleted afterwards so that resetting
     * the new setting later doesn't re-run this.
     */
    /**
     * One-time carry-over of the old image opacity setting (0-1, "HeatmapAlpha") to the percentage "imageOpacity".
     * Also removes the unreleased 0-255 "overlayOpacity" setting, replaced by "overlayOpacityPercent".
     */
    private void migrateOpacitySettings() {
        String oldImageOpacity = configManager.getConfiguration(WorldHeatmapConfig.GROUP, "HeatmapAlpha");
        if (oldImageOpacity != null) {
            if (configManager.getConfiguration(WorldHeatmapConfig.GROUP, "imageOpacity") == null) {
                try {
                    double alpha = Math.max(0, Math.min(1, Double.parseDouble(oldImageOpacity)));
                    configManager.setConfiguration(WorldHeatmapConfig.GROUP, "imageOpacity", (int) Math.round(alpha * 100));
                } catch (NumberFormatException e) {
                    log.warn("Couldn't carry over old image opacity setting '{}'", oldImageOpacity);
                }
            }
            configManager.unsetConfiguration(WorldHeatmapConfig.GROUP, "HeatmapAlpha");
        }
        if (configManager.getConfiguration(WorldHeatmapConfig.GROUP, "overlayOpacity") != null) {
            configManager.unsetConfiguration(WorldHeatmapConfig.GROUP, "overlayOpacity");
        }
    }

    private void migrateHeatmapToggles() {
        if (configManager.getConfiguration(WorldHeatmapConfig.GROUP, "enabledHeatmaps") != null) {
            return;
        }

        Map<String, Heatmap.HeatmapType> oldKeys = new LinkedHashMap<>();
        oldKeys.put("isHeatmapTypeAEnabled", Heatmap.HeatmapType.TYPE_A);
        oldKeys.put("isHeatmapTypeBEnabled", Heatmap.HeatmapType.TYPE_B);
        oldKeys.put("isHeatmapXPGainedEnabled", Heatmap.HeatmapType.XP_GAINED);
        oldKeys.put("isHeatmapTeleportPathsEnabled", Heatmap.HeatmapType.TELEPORT_PATHS);
        oldKeys.put("isHeatmapTeleportedToEnabled", Heatmap.HeatmapType.TELEPORTED_TO);
        oldKeys.put("isHeatmapTeleportedFromEnabled", Heatmap.HeatmapType.TELEPORTED_FROM);
        oldKeys.put("isHeatmapLootValueEnabled", Heatmap.HeatmapType.LOOT_VALUE);
        oldKeys.put("isHeatmapPlacesSpokenAtEnabled", Heatmap.HeatmapType.PLACES_SPOKEN_AT);
        oldKeys.put("isHeatmapRandomEventSpawnsEnabled", Heatmap.HeatmapType.RANDOM_EVENT_SPAWNS);
        oldKeys.put("isHeatmapDeathsEnabled", Heatmap.HeatmapType.DEATHS);
        oldKeys.put("isHeatmapNPCDeathsEnabled", Heatmap.HeatmapType.NPC_DEATHS);
        oldKeys.put("isHeatmapBobTheCatSightingEnabled", Heatmap.HeatmapType.BOB_THE_CAT_SIGHTING);
        oldKeys.put("isHeatmapNPCDamageTakenEnabled", Heatmap.HeatmapType.NPC_DAMAGE_TAKEN);
        oldKeys.put("isHeatmapNPCDamageGivenEnabled", Heatmap.HeatmapType.NPC_DAMAGE_GIVEN);
        oldKeys.put("isHeatmapPVPDamageTakenEnabled", Heatmap.HeatmapType.PVP_DAMAGE_TAKEN);
        oldKeys.put("isHeatmapPVPDamageGivenEnabled", Heatmap.HeatmapType.PVP_DAMAGE_GIVEN);
        oldKeys.put("isHeatmapWorldHopsEnabled", Heatmap.HeatmapType.WORLD_HOPS);
        oldKeys.put("isHeatmapLoginLogoutEnabled", Heatmap.HeatmapType.LOGIN_LOGOUT);
        oldKeys.put("isHeatmapTradesEnabled", Heatmap.HeatmapType.TRADES);

        boolean foundOldKey = false;
        Set<Heatmap.HeatmapType> enabled = EnumSet.noneOf(Heatmap.HeatmapType.class);
        for (Map.Entry<String, Heatmap.HeatmapType> e : oldKeys.entrySet()) {
            String oldValue = configManager.getConfiguration(WorldHeatmapConfig.GROUP, e.getKey());
            foundOldKey |= oldValue != null;
            if (!"false".equals(oldValue)) {
                enabled.add(e.getValue());
            }
        }
        if (!foundOldKey) {
            return;
        }

        log.info("Migrating per-heatmap on/off settings to the enabled heatmaps list: {}", enabled);
        configManager.setConfiguration(WorldHeatmapConfig.GROUP, "enabledHeatmaps", enabled);
        for (String key : oldKeys.keySet()) {
            configManager.unsetConfiguration(WorldHeatmapConfig.GROUP, key);
        }
    }

    /**
     * Saves heatmap to file when enabled, and reads heatmap from file when enabled.
     * @param isHeatmapEnabled Whether the heatmap is enabled
     * @param heatmapType The type of heatmap
     */
    private void handleHeatmapToggled(boolean isHeatmapEnabled, Heatmap.HeatmapType heatmapType){
        if (isHeatmapEnabled) {
            // If heatmaps haven't been loaded yet, loadHeatmaps() will load this one along with the rest
            if (!loading.isDone()) {
                return;
            }
            log.debug("Enabling {} heatmap...", heatmapType);
            Heatmap heatmap = null;
            // Load the heatmap from the file if it exists
			Filepath heatmapsFile = heatmapFileManager.getLatestFile(currentLocalAccountHash, currentSeasonalType);
            if (heatmapsFile != null && heatmapsFile.exists()) {
                try {
                    heatmap = heatmapFileManager.readHeatmapsFromFile(heatmapsFile, Collections.singletonList(heatmapType)).get(heatmapType);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (heatmap != null) {
                heatmap.setUserID(currentLocalAccountHash);
                heatmap.setAccountType(currentPlayerAccountType);
                heatmap.setCurrentCombatLevel(currentPlayerCombatLevel);
                heatmap.setSeasonalType(currentSeasonalType);
            } else {
                // Not in the latest file (or there's no file yet), so start it fresh
                heatmap = new Heatmap(heatmapType, currentLocalAccountHash, currentPlayerAccountType, currentSeasonalType, currentPlayerCombatLevel);
            }
            heatmaps.put(heatmapType, heatmap);
        } else {
            log.debug("Disabling {} heatmap...", heatmapType);
            Heatmap disabledHeatmap = heatmaps.get(heatmapType);
            if (disabledHeatmap != null) {
                // It no longer counts as enabled, so include it explicitly or its unsaved progress would be lost
                List<Heatmap> heatmapsToSave = new ArrayList<>(getEnabledHeatmaps());
                heatmapsToSave.add(disabledHeatmap);
                saveHeatmapsFile(heatmapsToSave);
                heatmaps.remove(heatmapType);
            }
        }

        panel.rebuild();
    }

    private void uploadHeatmapRoutine() {
        int uploadFrequency = 36_000; // Every 6 hours of game time

        if (!config.isUploadEnabled()){
            return;
        }

		// Determine if it is time to upload the heatmaps
		int highestGameTimeTicks = 0;
        for (Heatmap.HeatmapType type : heatmaps.keySet()) {
            if (isHeatmapEnabled(type)) {
                // Check if it's time to upload each heatmap based on the upload frequencies
				int gameTimeTicks = heatmaps.get(type).getGameTimeTicks();
                highestGameTimeTicks = Math.max(highestGameTimeTicks, gameTimeTicks);
            }
        }
		boolean shouldUpload = highestGameTimeTicks % uploadFrequency == 0 && highestGameTimeTicks != 0;

        // Upload the heatmaps
        if (shouldUpload && Utils.uploadHeatmaps(heatmaps, okHttpClient)){
            log.info("Heatmaps uploaded successfully");
        }
    }


	/**
	 * Shows an image write's progress in the heatmap's panel row.
	 */
	static class HeatmapProgressListener implements IIOWriteProgressListener {
        private final WorldHeatmapPlugin worldHeatmapPlugin;
        Heatmap.HeatmapType heatmapType;

        WorldHeatmapPanel panel;

        public HeatmapProgressListener(WorldHeatmapPlugin worldHeatmapPlugin, Heatmap.HeatmapType heatmapType) {
            super();
            this.worldHeatmapPlugin = worldHeatmapPlugin;
            this.heatmapType = heatmapType;
            this.panel = worldHeatmapPlugin.panel;
        }

        @Override
        public void imageStarted(ImageWriter source, int imageIndex) {
            panel.setEnabledHeatmapButtons(false);
            panel.setImageWriteStatus(heatmapType, "Writing... 0%", Color.YELLOW);
        }

        @Override
        public void imageProgress(ImageWriter source, float percentageDone) {
            panel.setImageWriteStatus(heatmapType, String.format("Writing... %.0f%%", percentageDone), Color.YELLOW);
        }

        @Override
        public void imageComplete(ImageWriter source) {
            panel.setEnabledHeatmapButtons(true);
            showResultBriefly("Done", Color.GREEN);
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
            panel.setEnabledHeatmapButtons(true);
            showResultBriefly("Error", Color.RED);
        }

        private void showResultBriefly(String result, Color colour) {
            panel.setImageWriteStatus(heatmapType, result, colour);
            worldHeatmapPlugin.executor.schedule(() -> panel.clearImageWriteStatus(heatmapType), 2L, TimeUnit.SECONDS);
        }
    }
}
