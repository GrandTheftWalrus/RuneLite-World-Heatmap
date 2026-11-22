package com.worldheatmap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
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
 * Downloads, caches and loads the global heatmap for the world map overlay. Only one is held in memory at a time.
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

	// Heatmaps already checked with the server this session
	private final Set<Heatmap.HeatmapType> revalidated = Collections.synchronizedSet(new HashSet<>());

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
		final Heatmap.HeatmapType wanted = config.showWorldMapOverlay() && config.overlayUseGlobalHeatmaps()
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
	 * The file name the global data for a heatmap type is published under.
	 */
	private static String fileName(Heatmap.HeatmapType type)
	{
		switch (type)
		{
			// The global data predates splitting damage into NPC and PVP, and uses the old type names
			case NPC_DAMAGE_TAKEN:
				return "DAMAGE_TAKEN.whmg.gz";
			case NPC_DAMAGE_GIVEN:
				return "DAMAGE_GIVEN.whmg.gz";
			default:
				return type.name() + ".whmg.gz";
		}
	}

	private void load(Heatmap.HeatmapType type, int requestGeneration)
	{
		final String name = fileName(type);
		final Filepath dir = plugin.pluginDirectory.join("Global Heatmaps");
		final Filepath file = dir.joinSegment(name);
		final Filepath etagFile = dir.joinSegment(name + ".etag");

		try
		{
			if (!revalidated.contains(type))
			{
				final Status downloadStatus = download(BASE_URL + name, dir, file, etagFile);
				if (downloadStatus == Status.UNAVAILABLE)
				{
					finish(requestGeneration, null, Status.UNAVAILABLE);
					return;
				}
				revalidated.add(type);
			}

			if (!file.exists())
			{
				finish(requestGeneration, null, Status.ERROR);
				return;
			}

			try (InputStream fileIn = file.openInputStream();
				 DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(fileIn, 64 * 1024))))
			{
				final GlobalHeatmap loaded = GlobalHeatmap.read(in, type);
				log.debug("Loaded global {} heatmap: {} regions, max {}", type, loaded.getRegions().size(), loaded.getMaxValue());
				finish(requestGeneration, loaded, Status.READY);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Failed to load global {} heatmap", type, e);
			finish(requestGeneration, null, Status.ERROR);
		}
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
