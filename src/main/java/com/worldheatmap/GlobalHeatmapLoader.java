package com.worldheatmap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.GZIPInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads, caches and loads the global heatmap for the overlays. Only one is held in memory at a time.
 * <p>
 * Files are cached in the plugin directory and revalidated (by ETag) once per client session per heatmap, so they're
 * only downloaded again when they change. If the server can't be reached, the cached copy is used.
 * {@link #update} must be called from the client thread; loading happens on the executor.
 */
@Slf4j
@Singleton
class GlobalHeatmapLoader
{
	private static final String BASE_URL = "https://raw.githubusercontent.com/GrandTheftWalrus/gtw-runelite-stuff/main/heatmaps/";
	// The global files are a few MB at most; refuse anything unreasonably large
	private static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;

	enum Status
	{
		NONE,
		LOADING,
		READY,
		// The server has no global data for this heatmap
		UNAVAILABLE,
		ERROR,
	}

	private final WorldHeatmapPlugin plugin;
	private final WorldHeatmapConfig config;
	private final OkHttpClient okHttpClient;
	private final ScheduledExecutorService executor;

	// File names already checked with the server this session, and ones the server doesn't have
	private final Set<String> revalidated = Collections.synchronizedSet(new HashSet<>());
	private final Set<String> unavailable = Collections.synchronizedSet(new HashSet<>());

	private Heatmap.HeatmapType requestedType;
	// Incremented on every new request, so a slow load for a previous request is discarded
	private volatile int generation;
	private volatile GlobalHeatmap heatmap;
	private volatile Status status = Status.NONE;

	@Inject
	GlobalHeatmapLoader(WorldHeatmapPlugin plugin, WorldHeatmapConfig config, OkHttpClient okHttpClient, ScheduledExecutorService executor)
	{
		this.plugin = plugin;
		this.config = config;
		this.okHttpClient = okHttpClient;
		this.executor = executor;
	}

	/**
	 * Loads the selected heatmap's global data if global mode is on, or frees it if not. Call every client tick.
	 */
	void update()
	{
		final boolean anyOverlay = config.showWorldMapOverlay() || config.showSceneOverlay() || config.showMinimapOverlay();
		final Heatmap.HeatmapType wanted = anyOverlay && config.overlayUseGlobalHeatmaps()
			? config.overlayHeatmapType().getHeatmapType() : null;
		if (wanted == requestedType)
		{
			return;
		}

		requestedType = wanted;
		final int thisGeneration = ++generation;
		heatmap = null;
		if (wanted == null)
		{
			status = Status.NONE;
			return;
		}
		status = Status.LOADING;
		executor.execute(() -> load(wanted, thisGeneration));
	}

	/**
	 * @return the loaded global heatmap, or null if none is loaded (see {@link #getStatus})
	 */
	GlobalHeatmap getHeatmap()
	{
		return heatmap;
	}

	Status getStatus()
	{
		return status;
	}

	void clear()
	{
		requestedType = null;
		generation++;
		heatmap = null;
		status = Status.NONE;
	}

	/**
	 * The file names the global data for a heatmap type may be published under, in order of preference.
	 */
	private static List<String> fileNames(Heatmap.HeatmapType type)
	{
		switch (type)
		{
			// Older global data predates splitting damage into NPC and PVP, under the old type names
			case NPC_DAMAGE_TAKEN:
				return Arrays.asList("NPC_DAMAGE_TAKEN.whmg.gz", "DAMAGE_TAKEN.whmg.gz");
			case NPC_DAMAGE_GIVEN:
				return Arrays.asList("NPC_DAMAGE_GIVEN.whmg.gz", "DAMAGE_GIVEN.whmg.gz");
			default:
				return Collections.singletonList(type.name() + ".whmg.gz");
		}
	}

	/**
	 * Loads the first of the type's file names that the server has (or, if it can't be reached, that's cached).
	 */
	private void load(Heatmap.HeatmapType type, int requestGeneration)
	{
		final Filepath dir = plugin.pluginDirectory.join("Global Heatmaps");
		boolean failed = false;

		for (String name : fileNames(type))
		{
			if (unavailable.contains(name))
			{
				continue;
			}
			final Filepath file = dir.joinSegment(name);
			final Filepath etagFile = dir.joinSegment(name + ".etag");

			try
			{
				if (!revalidated.contains(name))
				{
					if (download(BASE_URL + name, dir, file, etagFile) == Status.UNAVAILABLE)
					{
						// Not (or no longer) published, so don't fall back to a stale cached copy either
						unavailable.add(name);
						file.deleteIfExists();
						etagFile.deleteIfExists();
						continue;
					}
					revalidated.add(name);
				}

				if (!file.exists())
				{
					failed = true;
					continue;
				}

				try (InputStream fileIn = file.openInputStream();
					 DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(fileIn, 64 * 1024))))
				{
					final GlobalHeatmap loaded = GlobalHeatmap.read(in, type);
					log.debug("Loaded global {} heatmap from {}: {} regions, max {}", type, name, loaded.getRegions().size(), loaded.getMaxValue());
					finish(requestGeneration, loaded, Status.READY);
					return;
				}
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("Failed to load global {} heatmap from {}", type, name, e);
				failed = true;
			}
		}

		finish(requestGeneration, null, failed ? Status.ERROR : Status.UNAVAILABLE);
	}

	private void finish(int requestGeneration, GlobalHeatmap loaded, Status newStatus)
	{
		// Ignore results for a request that's been replaced
		if (requestGeneration == generation)
		{
			heatmap = loaded;
			status = newStatus;
		}
	}

	/**
	 * Downloads the file if the server's copy differs from the cached one.
	 *
	 * @return UNAVAILABLE if the server has no such file, otherwise READY (including when the server couldn't be
	 * reached, in which case any cached copy is used)
	 */
	private Status download(String url, Filepath dir, Filepath file, Filepath etagFile) throws IOException
	{
		final Request.Builder request = new Request.Builder().url(url);
		if (file.exists() && etagFile.exists())
		{
			request.header("If-None-Match", readString(etagFile));
		}

		try (Response response = okHttpClient.newCall(request.build()).execute())
		{
			if (response.code() == 304)
			{
				return Status.READY;
			}
			if (response.code() == 404)
			{
				return Status.UNAVAILABLE;
			}
			final ResponseBody body = response.body();
			if (!response.isSuccessful() || body == null)
			{
				log.warn("Global heatmap download from {} failed with HTTP {}; using any cached copy", url, response.code());
				return Status.READY;
			}

			dir.createDirectories();
			final Filepath temp = dir.createTempFile("global", ".tmp");
			try
			{
				try (InputStream in = body.byteStream(); OutputStream out = temp.openOutputStream())
				{
					final byte[] buffer = new byte[64 * 1024];
					long total = 0;
					int read;
					while ((read = in.read(buffer)) != -1)
					{
						total += read;
						if (total > MAX_DOWNLOAD_BYTES)
						{
							throw new IOException("Global heatmap download from " + url + " is too large");
						}
						out.write(buffer, 0, read);
					}
				}
				temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING);
			}
			finally
			{
				temp.deleteIfExists();
			}

			final String etag = response.header("ETag");
			if (etag != null)
			{
				etagFile.write(etag);
			}
			else
			{
				etagFile.deleteIfExists();
			}
			return Status.READY;
		}
		catch (IOException e)
		{
			if (!file.exists())
			{
				throw e;
			}
			log.warn("Couldn't check for a newer global heatmap at {}; using the cached copy", url, e);
			return Status.READY;
		}
	}

	private static String readString(Filepath file) throws IOException
	{
		try (InputStream in = file.openInputStream())
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
	}
}
