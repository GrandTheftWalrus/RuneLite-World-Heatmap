package com.worldheatmap;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import net.runelite.api.ChatMessageType;
import static net.runelite.client.RuneLite.RUNELITE_DIR;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.hiscore.HiscoreEndpoint;
import net.runelite.client.hiscore.HiscoreManager;
import net.runelite.client.hiscore.HiscoreResult;

@Slf4j
public class HeatmapFileManager
{
    protected final static String HEATMAP_EXTENSION = ".heatmaps";
    protected final static File WORLD_HEATMAP_DIR = new File(RUNELITE_DIR.toString(), "worldheatmap");
    protected final static File HEATMAP_FILES_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Files").toFile();
    protected final static File HEATMAP_IMAGE_DIR = Paths.get(WORLD_HEATMAP_DIR.toString(), "Heatmap Images").toFile();
	protected final static ZonedDateTime startOfLeaguesV = ZonedDateTime.of(LocalDateTime.of(2024, 11, 27, 12, 0), ZoneId.of("GMT"));
	protected final static ZonedDateTime endOfLeaguesV = ZonedDateTime.of(LocalDateTime.of(2025, 1, 23, 0, 0), ZoneId.of("GMT"));
	protected final static DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
	private final WorldHeatmapPlugin plugin;
	private final HiscoreManager hiscoreManager;
	private final Map<String,  HiscoreResult> hiscoreResults = new HashMap<>();

	public HeatmapFileManager(WorldHeatmapPlugin plugin, HiscoreManager hiscoreManager){
		this.plugin = plugin;
		this.hiscoreManager = hiscoreManager;
	}

	/**
	 * Return a File in the correct directory, either named after the current time or the latest file in the directory,
	 * whichever comes last. If they are equal, returns a new file named onConflictOffset minutes later than the
	 * latest file. If onConflictOffset is zero, then it returns the latest file.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	public File getHeatmapFile(long userId, String seasonalType, String username, int onConflictOffset) {
		boolean isSeasonal = !seasonalType.isBlank();
		File userIdDir = new File(HEATMAP_FILES_DIR, Long.toString(userId) + (isSeasonal ? "_" + seasonalType : ""));
		// Find the next available filename
		File latestFile = getLatestHeatmapFile(userId, seasonalType, username);
		LocalDateTime dateOfLatestFile = null;
		if (latestFile != null && latestFile.exists()) {
			String name = latestFile.getName();
			int pos = name.lastIndexOf(HEATMAP_EXTENSION);
			name = name.substring(0, pos);
			dateOfLatestFile = LocalDateTime.parse(name, dateFormat);
		}

		LocalDateTime timeToUse;
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
		if (dateOfLatestFile == null || now.isAfter(dateOfLatestFile)) {
			timeToUse = now;
		}
		else {
			timeToUse = dateOfLatestFile.plus(Duration.ofMinutes(onConflictOffset));
		}
		String fileName = timeToUse.format(dateFormat) + HEATMAP_EXTENSION;

		return new File(userIdDir, fileName);
	}

	/**
	 * Returns a new heatmaps File named after the current time, or one minute past the latest file if it already exists.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
    public File getNewHeatmapFile(long userId, String seasonalType, String username) {
		return getHeatmapFile(userId, seasonalType, username, 1);
    }

	/**
	 * Returns a new heatmaps File named after the current time, or the latest file if it already exists.
	 * Doesn't actually create the file, just a File object.
	 * @param userId
	 * @param seasonalType
	 * @return
	 */
	public File getCurrentHeatmapFile(long userId, String seasonalType, String username) {
		return getHeatmapFile(userId, seasonalType, username, 0);
	}

	/**
	 * Returns a File named after the current date and time, even if it already exists.
	 * @param userId The user ID
	 * @param type The heatmap type
	 * @param seasonalType The seasonal type, or null if not seasonal
	 * @return
	 */
    public File getNewImageFile(long userId, HeatmapNew.HeatmapType type, String seasonalType) {
		boolean isSeasonal = !seasonalType.isBlank();
        String dateString = formatDate(LocalDateTime.now());
        File userIdDir = new File(HEATMAP_IMAGE_DIR, Long.toString(userId) + (isSeasonal ? "_" + seasonalType : ""));

        return new File(userIdDir, type + "_" + dateString + ".tif");
    }

    /**
     * Get the File that contains the latest heatmaps based on the filename being a date.
     * Returns null if no such file exists.
	 * This method should only be performed via the executor thread because it may do some heavy I/O for
	 * fixing previous files due to bad planning
	 * @param accountHash the user ID/account hash
	 * @param seasonalType the seasonal type, or null if not seasonal
	 * @param username the player's username. Used for detecting legacy files.
     * @return the youngest heatmaps file.
     */
    public File getLatestHeatmapFile(long accountHash, String seasonalType, String username) {
		boolean isSeasonal = !seasonalType.isBlank();
		long startTime = System.nanoTime();
		// currentDir may be normal or seasonal
        File currentDir = new File(HEATMAP_FILES_DIR, accountHash + (isSeasonal ? "_" + seasonalType : ""));
		File normalDir = new File(HEATMAP_FILES_DIR, Long.toString(accountHash));
		File leaguesVDir = new File(HEATMAP_FILES_DIR, accountHash + "_LEAGUES_V");

		runFixes(accountHash, username, normalDir, leaguesVDir);

		long endTime = System.nanoTime();
		log.debug("Time taken to get latest heatmap files (with fix checks): {} ms", (endTime - startTime) / 1_000_000);
		// If the latest file is found, return it
		return getLatestFile(currentDir);
	}

	/**
	 * Runs fixes on the user's heatmaps files, if necessary, for backwards compatability.
	 * @param accountHash the user ID/account hash
	 * @param username the player's username
	 * @param normalDir the normal heatmaps directory
	 * @param leaguesVDir the Leagues V heatmaps directory
	 */
	private void runFixes(long accountHash, String username, File normalDir, File leaguesVDir) {
		log.debug("Running check for heatmap file fixes for user {}...", username);
		// Modernize any legacy heatmap files, stashing them in the regular directory
		// (since they won't have seasonal type encoded)
		carryOverV1_0Files(username, accountHash, normalDir);
		carryOverV1_2Files(accountHash, normalDir);

		// Perform V1.6 file naming scheme fix on normal directory if necessary
		fileNamingSchemeFix(normalDir);

		// Check if Leagues V decontamination is needed, and perform if so
		leaguesDecontaminationFix(normalDir, leaguesVDir, username);
	}

	private boolean userIsOnLeaguesHiscores(String username)
	{
		if (hiscoreResults.get(username) != null)
		{
			log.debug("User {} already looked up on Leagues hiscores, and they do be on it", username);
			return true;
		}

		try
		{
			hiscoreResults.put(username, hiscoreManager.lookup(username, HiscoreEndpoint.LEAGUE));
		}
		catch (IOException e)
		{
			log.error("Error looking up user on Leagues hiscores: {}", e.toString());
			return false;
		}

		log.debug("User {} is {} Leagues hiscores", username, hiscoreResults.get(username) != null ? "on" : "NOT on");
		return hiscoreResults.get(username) != null;
	}

	/**
	 * Detects V1.2 heatmaps files, moves them to the new location, and renames them after their date modified.
	 *
	 * @param accountHash The user ID/account hash
	 * @param newDir      The new directory to move the files to
	 */
	private void carryOverV1_2Files(long accountHash, File newDir) {
		log.debug("Running check for V1.2 heatmap files for user {}...", "" + accountHash);
		File oldFile = new File(HEATMAP_FILES_DIR, accountHash + HEATMAP_EXTENSION);
		if (oldFile.exists()) {
			// Move the old file to the new location, naming it after its date modified
			long epochMillis = oldFile.lastModified();
			ZonedDateTime lastModified = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
			String newName = dateFormat.format(lastModified);
			File newFile = new File(newDir, newName + HEATMAP_EXTENSION);

			// Move the file by rewriting it, to update its version
			try {
				log.info("Moving heatmaps file from legacy (V1.2) location {} to new location {}", oldFile, newFile);
				HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = readHeatmapsFromFile(oldFile, List.of(HeatmapNew.HeatmapType.values()), false);
				// Update the account hash cuz we have it available
				heatmaps.values().forEach(h -> {
					h.setUserID(accountHash);
					h.setSeasonalType("");
				});
				writeHeatmapsToFile(heatmaps.values(), newFile, false);

				// Delete the old file
				Files.delete(oldFile.toPath());

				// Set its date modified to what it was before
				newFile.setLastModified(epochMillis);

				// If the moved file is the latest one in the folder,
				// make a second copy of the moved file, named one minute later,
				// so we can keep the other one as an updated backup
				if (getLatestFile(newDir) == newFile) {
					File newFile2 = new File(newDir, dateFormat.format(lastModified.plusMinutes(1)) + HEATMAP_EXTENSION);
					Files.copy(newFile.toPath(), newFile2.toPath());
					newFile2.setLastModified(lastModified.plusMinutes(1).toInstant().toEpochMilli());
				}
			} catch (IOException e) {
				log.error("Moving heatmaps file from legacy (V1.2) location failed");
			}

		}

		// If the file doesn't exist, return null
	}

	/**
	 * Returns V1.0 heatmap files if they exist, after having converted them to the new format and moving them to the new location.
	 * Also keeps an '.old' copy of the old files
	 * Also renames the old 'Backups' directory to 'Backups.old' if it exists
	 *
	 * @param currentPlayerName The player name
	 * @param newDir            The new directory to move the files to
	 */
	private void carryOverV1_0Files(String currentPlayerName, long accountHash, File newDir) {
		log.debug("Running check for V1.0 heatmap files for user {}...", currentPlayerName);
		// Check if TYPE_A V1.0 file exists, and if it does, convert it to the new format
		// rename the old file to .old, and write the new file
		File typeAFile = new File(HEATMAP_FILES_DIR.toString(), currentPlayerName + "_TypeA.heatmap");
		HeatmapNew typeAHeatmap = new HeatmapNew(HeatmapNew.HeatmapType.TYPE_A, -1, -1, null, -1);
		ZonedDateTime typeAModified = null;
		boolean typeAExisted = false;
		if (typeAFile.exists()) {
			log.debug("Found legacy Type A heatmap file for user {}. Converting to new format and moving to proper directory...", currentPlayerName);
			typeAExisted = true;
			// Get date modified
			typeAModified = ZonedDateTime.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(typeAFile.lastModified()), ZoneId.systemDefault()), ZoneId.systemDefault());

			// Load and convert the legacy heatmap file
			typeAHeatmap = HeatmapNew.readLegacyV1HeatmapFile(typeAFile);
			typeAHeatmap.setUserID(accountHash);
			typeAHeatmap.setSeasonalType("");
			typeAHeatmap.setHeatmapType(HeatmapNew.HeatmapType.TYPE_A);

			// Append '.old' to legacy file name
			File dotOldTypeA = new File(typeAFile + ".old");
			typeAFile.renameTo(dotOldTypeA);
		}

		// Repeat for Type B
		File typeBFile = new File(HEATMAP_FILES_DIR.toString(), currentPlayerName + "_TypeB.heatmap");
		HeatmapNew typeBHeatmap = new HeatmapNew(HeatmapNew.HeatmapType.TYPE_B, -1, -1, null, -1);
		ZonedDateTime typeBModified = null;
		boolean typeBExisted = false;
		if (typeBFile.exists()) {
			log.debug("Found legacy Type B heatmap file for user {}. Converting to new format and moving to proper directory...", currentPlayerName);
			typeBExisted = true;
			// Get date modified
			typeBModified = ZonedDateTime.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(typeBFile.lastModified()), ZoneId.systemDefault()), ZoneId.systemDefault());

			// Load, rename, and rewrite the legacy heatmap file to the proper location
			typeBHeatmap = HeatmapNew.readLegacyV1HeatmapFile(typeBFile);
			typeBHeatmap.setUserID(accountHash);
			typeBHeatmap.setSeasonalType("");
			typeBHeatmap.setHeatmapType(HeatmapNew.HeatmapType.TYPE_B);

			// Make '.old' copy
			File dotOldTypeB = new File(typeBFile + ".old");
			typeBFile.renameTo(dotOldTypeB);
		}

		// Append .old to Paths.get(HEATMAP_FILES_DIR, "Backups") folder if it exists
		File backupDir = Paths.get(HEATMAP_FILES_DIR.toString(), "Backups").toFile();
		if (backupDir.exists()) {
			File oldBackupDir = new File(backupDir.toString() + ".old");
			backupDir.renameTo(oldBackupDir);
		}

		// If either one existed, write them to a new file
		if (typeAExisted || typeBExisted) {
			// Set the new file's date modified to the latest of the two legacy files
			ZonedDateTime latestModified;
			if (typeAModified == null) {
				latestModified = typeBModified;
			}
			else if (typeBModified == null) {
				latestModified = typeAModified;
			}
			else {
				latestModified = typeAModified.isAfter(typeBModified) ? typeAModified : typeBModified;
			}

			File newFile = new File(newDir, dateFormat.format(latestModified) + HEATMAP_EXTENSION);
			this.writeHeatmapsToFile(List.of(typeAHeatmap, typeBHeatmap), newFile);
			newFile.setLastModified(latestModified.toInstant().toEpochMilli());

			// If the new file is the latest one in the folder,
			// Make a second copy of the moved file, named one minute later
			// so we can keep the other one as an updated backup
			if (getLatestFile(newDir) == newFile) {
				File newFile2 = new File(newDir, dateFormat.format(latestModified.plusMinutes(1)) + HEATMAP_EXTENSION);
				try {
					Files.copy(newFile.toPath(), newFile2.toPath());
					newFile2.setLastModified(latestModified.plusMinutes(1).toInstant().toEpochMilli());
				} catch (IOException e) {
					log.error("Failed to make extra copy of converted legacy shmeatmap file: {}", e.toString());
				}
			}

		}
	}

	/**
	 * The following is a fix for the realization that leagues (and all other world-based game modes)
	 * were being permanently mixed with regular heatmaps. This fix moves all post-leagues V
	 * heatmap files into a "LEAGUES_V" directory, and rolls back normal heatmaps to a state before the
	 * leagues contamination, on the criteria that the user is on the Leagues V hiscores, has any "normal"
	 * heatmaps dated during Leagues V, and has no Leagues V dir (aka this process hasn't run yet).
	 * It also creates a text file explaining the situation.
	 * The criteria for possible contamination
	 * @param normalDir the directory containing the regular heatmaps
	 * @param leaguesVDir the directory to move the contaminated heatmaps to
	 */
	private void leaguesDecontaminationFix(File normalDir, File leaguesVDir, String username) {
		log.debug("Running check for Leagues V contamination for user {}...", username);
		if (username == null || username.isBlank()) {
			return;
		}
		if (leaguesVDir.exists() || !normalDir.exists() || !userIsOnLeaguesHiscores(username)){
			log.debug("User {} is not on Leagues V hiscores or has already been decontaminated", username);
			return;
		}

		// Check if they have any files dated during Leagues V
		boolean hasPossiblyContaminatedFiles = false;
		for (File file : getSortedFiles(normalDir)){
			String name = file.getName();
			int pos = name.lastIndexOf(".");
			name = name.substring(0, pos);
			ZonedDateTime fileDate = ZonedDateTime.of(LocalDateTime.parse(name, dateFormat), ZoneId.systemDefault());
			if (fileDate.isAfter(startOfLeaguesV) && fileDate.isBefore(endOfLeaguesV)){
				hasPossiblyContaminatedFiles = true;
				break;
			}
		}

		if (!hasPossiblyContaminatedFiles){
			return;
		}

		log.debug("User {} has potentially contaminated heatmaps, moving to Leagues V directory and rolling back...", username);

		// Create seasonal directory
		if (!leaguesVDir.mkdirs()) {
			log.error("Failed to create seasonal heatmaps directory");
			return;
		}

		// Move potentially contaminated files from the normal heatmap directory to the seasonal directory;
		int filesMoved = 0;
		for (File originFile : getSortedFiles(normalDir)) {
			String name = originFile.getName();
			int pos = name.lastIndexOf(".");
			name = name.substring(0, pos);
			ZonedDateTime fileDate = ZonedDateTime.of(LocalDateTime.parse(name, dateFormat), ZoneId.systemDefault());
			// Move everything dated after the start of Leagues V to the leagues folder
			if (fileDate.isAfter(startOfLeaguesV)) {
				try {
					// Move it to the seasonal directory, updating its metadata
					log.info("File {} is contaminated by Leagues V, moving to Leagues V directory", originFile.getName());
					HashMap<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = readHeatmapsFromFile(originFile, List.of(HeatmapNew.HeatmapType.values()), false);
					heatmaps.values().forEach(h -> h.setSeasonalType("LEAGUES_V"));
					File destinationFile = new File(leaguesVDir, originFile.getName());
					writeHeatmapsToFile(heatmaps.values(), destinationFile, false);

					// Delete the original file
					Files.delete(originFile.toPath());
					filesMoved++;
				}
				catch (IOException e)
				{
					log.error("Failed to move file to seasonal directory and/or update its metadata: {}", e.toString());
				}
			}
		}

		if (filesMoved > 0) {
			// Print a notice in the in-game chatbox
			plugin.clientThread.invoke(this::displayLeaguesVNotice);

			// Create a text file explaining the situation, for if/when the user eventually investigates
			String today = formatDate(LocalDateTime.now());
			File leaguesExplanationFile = new File(leaguesVDir, today + "_leagues_incident.txt");
			try {
				Files.write(leaguesExplanationFile.toPath(), (
					"This directory contains heatmaps that are possibly contaminated by Leagues V data.\n" +
						"Version 1.5.1 of the plugin didn't keep separate heatmaps for Leagues/seasonal\n" +
						"accounts and regular accounts because I didn't realize that each player's\n" +
						"Leagues/seasonal account would have the same Account ID code\n" +
						"(regular/ironman/group ironman etc.) as their regular account. So, data being\n" +
						"uploaded to the website https://osrsworldheatmap.com was looking kinda sus\n" +
						"such as how TELEPORTED_TO had a bunch of entries that would normally be impossibru\n" +
						"for regular accounts. The only way to fix it, that I could think of, was to\n" +
						"designate the potentially contaminated .heatmaps files (i.e. the ones dated after the start \n" +
						"of Leagues V) of anyone who has a record on the Leagues V hiscores, and has any heatmap saves\n" +
						"dated during Leaguees V, as a leagues heatmap (which is why you're seeing this), which\n" +
						"effectively rolls-back any regular heatmaps to the latest existing pre-leagues backup.\n" +
						"That way, the personal data that players spent a long time collecting wouldn't be\n" +
						"lost, whilst somewhat unfugging the global heatmap. In the future (or perhaps in the\n" +
						"past?) I'm  thinking that I'll add a feature to the website where you can open a local\n" +
						".heatmaps file for visualization, so you can more easily take a gander at your old\n" +
						"data in this folder. I'll probs make a Leagues V category on the global heatmap, too.\n" +
						"If you have any questions or concerns, please contact me somehow or make an issue on\n" +
						"the GitHub page for the plugin: https://github.com/GrandTheftWalrus/RuneLite-World-Heatmap\n"+
						"\n" +
						"P.S. If you're absolutely certain that you didn't play Leagues V until a certain date\n" +
						"post-release, then I gueeeeesss you could move the allegedly unaffected .heatmap files from\n" +
						"this folder back to the regular folder if you see this message in time \uD83E\uDD28 but\n" +
						"you better be deadass or else you'll be messing up the global heatmap if you're opted-in\n" +
						"to contributing to it. If not, then I spose you can whatever you want with your own data.\n"
				).getBytes());
			} catch (IOException e) {
				log.error("Failed to write leagues explanation file: {}", e.toString());
			}
		}
	}

	private String formatDate(LocalDateTime dateTime) {
		return dateTime.format(dateFormat);
	}

	/**
	 * Returns the file in the given directory whose filename is the most recent parseable date string.
	 * @param path the directory to search
	 * @return the file with the most recent date in its filename, or null if no such file exists
	 */
	private File getLatestFile(File path) {
		File[] files = getSortedFiles(path);
		if (files.length == 0) {
			return null;
		}
		return files[0];
	}

    /**
	 * Returns an array of files in the given directory whose filenames are parseable date strings, sorted by date.
	 * @param path the directory to search
	 * @return an array of files with parseable dates in their filenames, sorted by date (most recent first)
     */
	private File[] getSortedFiles(File path) {
		File[] files = path.listFiles(File::isFile);
		if (files == null || files.length == 0) {
			return new File[0];
		}

		files = Arrays.stream(files)
			.filter(file -> {
				String name = file.getName();
				int pos = name.lastIndexOf(".heatmaps");
				if (pos == -1) {
					return false;
				}
				name = name.substring(0, pos);
				try {
					LocalDateTime.parse(name, dateFormat);
					return true;
				} catch (Exception e) {
					return false;
				}
			})
			.toArray(File[]::new);

		Arrays.sort(files, (f1, f2) -> {
			try {
				String n1 = f1.getName();
				String n2 = f2.getName();
				int pos1 = n1.lastIndexOf(".heatmaps");
				int pos2 = n2.lastIndexOf(".heatmaps");
				n1 = n1.substring(0, pos1);
				n2 = n2.substring(0, pos2);

				LocalDateTime d1 = LocalDateTime.parse(n1, dateFormat);
				LocalDateTime d2 = LocalDateTime.parse(n2, dateFormat);
				return d2.compareTo(d1);
			} catch (Exception e) {
				return 0;
			}
		});

		return files;
	}

	protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, @Nullable File previousHeatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, previousHeatmapsFile, true);
	}

	protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, null, true);
	}

	/**
	 * Writes the provided heatmap data to the specified .heatmaps file. If the file already exists, it will be updated,
	 * and unprovided heatmaps already in the file will remain.
	 * @param heatmapsToWrite The heatmaps to write
	 * @param heatmapsFile The .heatmaps file
	 */
	protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, boolean verbose) {
		writeHeatmapsToFile(heatmapsToWrite, heatmapsFile, null, verbose);
	}

	/**
	 * Writes the provided heatmap data to the specified .heatmaps file. Unprovided heatmaps are carried over from the file previousHeatmapsFile, if it has them.
	 * If heatmapsFile already exists, existing unprovided heatmaps will be kept. Heatmaps in previousHeatmapsFile take precedence over heatmaps in heatmapsFile
	 * if heatmapsFile already exists.
	 * @param heatmapsToWrite The heatmaps to write
	 * @param heatmapsFile The .heatmaps file
	 * @param previousHeatmapsFile The previous .heatmaps file.
	 */
	protected void writeHeatmapsToFile(Collection<HeatmapNew> heatmapsToWrite, File heatmapsFile, @Nullable File previousHeatmapsFile, boolean verbose) {
		// Preamble
		if (verbose) {
			log.info("Saving heatmaps to file '{}'...", heatmapsFile.getName());
		}

		long startTime = System.nanoTime();
		StringBuilder loggingOutput = new StringBuilder("Heatmap types saved: ");

		// Make the directory path if it doesn't exist
		if (!Files.exists(Paths.get(heatmapsFile.getParent()))) {
			if (!new File(heatmapsFile.getParent()).mkdirs()) {
				log.error("Could not create the directory for the heatmap file");
			}
		}

		// Copy previousHeatmapsFile to heatmapsFile (if it exists)
		// in order to carry over unprovided heatmaps before updating
		if (previousHeatmapsFile != null && previousHeatmapsFile.exists()) {
			try {
				Files.copy(previousHeatmapsFile.toPath(), heatmapsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				log.error("Error copying latest heatmaps file to new location");
				return;
			}
		}

		// Zip reading params
		Map<String, String> env = new HashMap<>();
		env.put("create", "true");
		URI uri = URI.create("jar:" + heatmapsFile.toURI());

		for (HeatmapNew heatmap : heatmapsToWrite) {
			// Write the heatmap file, overwriting zip entries that already exist
			try (FileSystem fs = FileSystems.newFileSystem(uri, env)) {
				Path zipEntryFile = fs.getPath("/" + heatmap.getHeatmapType().toString() + "_HEATMAP.csv");
				try (OutputStreamWriter osw = new OutputStreamWriter(Files.newOutputStream(zipEntryFile), StandardCharsets.UTF_8)) {
					heatmap.toCSV(osw);
					osw.flush(); // Not sure if this is necessary
				}
			} catch (IOException e) {
                log.error("World Heatmap was not able to save heatmap type '{}' to file '{}'", heatmap.getHeatmapType(), heatmapsFile.getName());
				e.printStackTrace();
				return;
			}
			loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getTileCount() + " tiles), ");
		}

		if (verbose) {
			log.debug(loggingOutput.toString());
			log.debug("Finished writing '{}' heatmap file to disk after {} ms", heatmapsFile.getName(), (System.nanoTime() - startTime) / 1_000_000);
		}
	}

	/**
	 * Loads the specified heatmap types from the given .heatmaps file.
	 *
	 * @param heatmapsFile The .heatmaps file
	 * @param types        The heatmap types to load
	 * @return HashMap of HeatmapNew objects
	 * @throws FileNotFoundException If the file does not exist
	 */
	HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapsFile, Collection<HeatmapNew.HeatmapType> types) throws FileNotFoundException {
		return readHeatmapsFromFile(heatmapsFile, types, true);
	}

	/**
	 * Loads the specified heatmap types from the given .heatmaps file.
	 *
	 * @param heatmapsFile The .heatmaps file
	 * @param types        The heatmap types to load
	 * @param verbose      Whether to log the heatmap types loaded
	 * @return HashMap of HeatmapNew objects
	 * @throws FileNotFoundException If the file does not exist
	 */
	HashMap<HeatmapNew.HeatmapType, HeatmapNew> readHeatmapsFromFile(File heatmapsFile, Collection<HeatmapNew.HeatmapType> types, boolean verbose) throws FileNotFoundException {
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
					HeatmapNew heatmap = HeatmapNew.fromCSV(curType, reader);
					heatmapsRead.put(heatmap.getHeatmapType(), heatmap);
					loggingOutput.append(heatmap.getHeatmapType() + " (" + heatmap.getTileCount() + " tiles), ");
				} catch (IOException e) {
                    log.error("Error reading {} heatmap from .heatmaps entry '{}'", curType, curHeatmapPath);
				}
			}
			if (verbose) {
				log.debug(loggingOutput.toString());
			}
			return heatmapsRead;
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * V1.6.0 fix for file naming scheme. Checks the latest heatmap version in the directory, and if it's version 101 or
	 * earlier, renames the files after their dates modified.
	 * @param directory The directory to fix
	 */
	protected void fileNamingSchemeFix(File directory) {
		log.debug("Running check for pre-V1.6 file naming scheme fix on directory '{}'...", directory.getName());
		// Get latest .heatmaps file in the directory
		File latestHeatmap = getLatestFile(directory);
		if (latestHeatmap == null) {
			return;
		}

		// Find the latest heatmap version of the latest .heatmaps file
		long latestVersion = -1;
		try {
			Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps = readHeatmapsFromFile(latestHeatmap, List.of(HeatmapNew.HeatmapType.values()), false);
			for (HeatmapNew heatmap : heatmaps.values()) {
				if (heatmap.getVersionReadFrom() > latestVersion) {
					latestVersion = heatmap.getVersionReadFrom();
				}
			}
		}
		catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}

		// Rename the files if necessary
		if (latestVersion <= 101) {
			log.info("Performing naming scheme fix on .heatmaps files in directory '{}'...", directory.getName());
			List<File> heatmapFiles = new ArrayList<>();
			for (File file : Objects.requireNonNull(directory.listFiles())) {
				if (file.getName().endsWith(".heatmaps")) {
					heatmapFiles.add(file);
				}
			}

			// Sort the files by parseable date descending
			heatmapFiles.sort((f1, f2) -> {
				String name1 = f1.getName().split("\\.")[0];
				String name2 = f2.getName().split("\\.")[0];
				LocalDateTime f1Date = LocalDateTime.parse(name1, dateFormat);
				LocalDateTime f2Date = LocalDateTime.parse(name2, dateFormat);
				return f2Date.compareTo(f1Date);
			});

			// Cycle through them, renaming them after their date modified
			for (File file : heatmapFiles) {
				long epochMillis = file.lastModified();

				// Convert to ZonedDateTime in the local time zone
				ZonedDateTime lastModified = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());

				// Format the new name
				String newName = dateFormat.format(lastModified);
				File newFile = new File(directory, newName + ".heatmaps");

				// Rename the file
				if (file.renameTo(newFile)) {
					log.info("Renamed {} to {}", file.getName(), newFile.getName());
				}
				else {
					log.error("Could not rename file '{}' to '{}'", file.getName(), newFile.getName());
				}
			}
		}
	}

	/**
	 * Displays a message to the user about the latest update.
	 * Only displays the message once per update.
	 */
	private void displayLeaguesVNotice() {
		String noticeKey = "leaguesVDecontaminationNotice";
		if (plugin.configManager.getConfiguration("worldheatmap", noticeKey) == null) {
			// Send a message in game chat
			final String message = new ChatMessageBuilder()
				.append(Color.decode("#a100a1"), "An automatic fix has been applied to your World Heatmap data in " +
					"order to separate Leagues V data from regular account data. Open your heatmaps folder via " +
					"the side panel and check out the Leagues V folder for more information.")
				.build();
			plugin.chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(message)
				.build());
			plugin.configManager.setConfiguration("worldheatmap", noticeKey, "shown");
		}
	}
}
