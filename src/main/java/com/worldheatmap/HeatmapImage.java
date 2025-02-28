package com.worldheatmap;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.imageio.*;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Class which calculates osrs heatmap image data on demand
 */
@Slf4j
public class HeatmapImage implements RenderedImage
{
	private final ImageReader worldMapImageReader;
	private final ColorModel colorModel = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

	// A queue that holds the heatmap coordinates along
	// with their values, to be sorted by coordinate left-to-right top-to-bottom
	private static LinkedList<Map.Entry<Point, Integer>> sortedHeatmapTiles;
	private final float heatmapTransparency;
	private final int heatmapSensitivity;
	private final int numXTiles = 1;
	private final int numYTiles;
	private int heatmapMinVal;
	private int heatmapMaxVal;
	int PIXEL_OFFSET_X;
	int PIXEL_OFFSET_Y;

	/**
	 * @param worldMapImageReader osrs_world_map.png
	 * @param numYTiles           Image width must be evenly divisible by numYTiles
	 */
	public HeatmapImage(HeatmapNew heatmap, ImageReader worldMapImageReader, int numYTiles, float transparency, int sensitivity, int pixelOffsetX, int pixelOffsetY)
	{
		this.worldMapImageReader = worldMapImageReader;
		this.numYTiles = numYTiles;
		this.heatmapTransparency = transparency;
		this.heatmapSensitivity = sensitivity;
		this.PIXEL_OFFSET_X = pixelOffsetX;
		this.PIXEL_OFFSET_Y = pixelOffsetY;
		initializeProcessingVariables(heatmap);
		try
		{
			if (worldMapImageReader.getHeight(0) % numYTiles != 0)
			{
                log.debug("WARNING: Image height {} is not evenly divisible by the number of Y tiles, {}.", worldMapImageReader.getHeight(0), numYTiles);
			}
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Calculates the tile height based on the config setting
	 *
	 * @param configSetting The config setting
	 * @return The tile height
	 */
	static int calculateTileHeight(int configSetting, boolean isFullMap) {
		// NOTE: these should be adjusted if the world map image's size is ever changed
		// They should evenly divide the image height or else the image will be cut off
		// Also I think they have to be multiples of 16 or something for the TIF format?
		// I forgor. it seems to be working fine though
		if (isFullMap){
			return new int[]{32, 64, 89, 178, 356, 712, 1424, 2848, 5696}[configSetting];
		}
		else{
			return new int[]{32, 64, 125, 150, 300, 600, 1600, 3200, 6400}[configSetting];
		}
	}

	protected static void writeHeatmapImage(HeatmapNew heatmap, File imageFileOut, boolean isFullMapImage, boolean isBlue, double heatmapTransparency, int heatmapSensitivity, int speedMemoryTradeoff, @Nullable IIOWriteProgressListener progressListener)
	{
		log.info("Saving {} image to disk...", imageFileOut);
		long startTime = System.nanoTime();
		if (!imageFileOut.getName().endsWith(".tif"))
		{
			imageFileOut = new File(imageFileOut.getName() + ".tif");
		}

		if (imageFileOut.getParentFile().mkdirs())
		{
			log.debug("Created directory for image file: {}", imageFileOut.getParentFile());
		}

		if (heatmapTransparency < 0)
		{
			heatmapTransparency = 0;
		}
		else if (heatmapTransparency > 1)
		{
			heatmapTransparency = 1;
		}

		String worldMapImageURL = String.format("https://raw.githubusercontent.com/GrandTheftWalrus/gtw-runelite-stuff/main/osrs_world_map%s%s.png", isFullMapImage ? "_full" : "", isBlue ? "_blue" : "");

		// Prepare the image reader
		try (InputStream inputStream = new URL(worldMapImageURL).openStream();
			 ImageInputStream worldMapImageInputStream = ImageIO.createImageInputStream(Objects.requireNonNull(inputStream, "Resource didn't exist: '" + worldMapImageURL + "'")))
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
				final int tileWidth = reader.getWidth(0);
				final int tileHeight = calculateTileHeight(speedMemoryTradeoff, isFullMapImage);
				final int N = reader.getHeight(0) / tileHeight;

				// Make progress listener majigger
				if (progressListener != null)
				{
					writer.addIIOWriteProgressListener(progressListener);
				}

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

				if (isFullMapImage)
				{
					heatmapImage = new HeatmapImage(heatmap, reader, N, (float) heatmapTransparency, heatmapSensitivity, fullMapOffsetX, fullMapOffsetY);
				}
				else
				{
					heatmapImage = new HeatmapImage(heatmap, reader, N, (float) heatmapTransparency, heatmapSensitivity, overworldMapOffsetX, overworldMapOffsetY);
				}
				writer.write(null, new IIOImage(heatmapImage, null, null), writeParam);
				reader.dispose();
				writer.dispose();
			}
			log.info("Finished writing {} image to disk after {} ms", imageFileOut, (System.nanoTime() - startTime) / 1_000_000);
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
			log.error("Exception thrown whilst creating and/or writing image file: ", e);
		}
	}

	@Override
	public Vector<RenderedImage> getSources()
	{
		return null;
	}

	@Override
	public Object getProperty(String name)
	{
		return null;
	}

	@Override
	public String[] getPropertyNames()
	{
		return new String[0];
	}

	@Override
	public ColorModel getColorModel()
	{
		return colorModel;
	}

	@Override
	public SampleModel getSampleModel()
	{
		return new ComponentSampleModel(DataBuffer.TYPE_BYTE, getWidth(), getHeight(), 1, 1, new int[]{0, 0, 0});
	}

	@Override
	public int getWidth()
	{
		try
		{
			return worldMapImageReader.getWidth(0);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Calculates the min and max values of the heatmap within the overworld
	 *
	 * @param heatmap The heatmap
	 * @return An array of length 2, where the first element is the max value and the second element is the min value
	 */
	private int[] calculateMaxMinValues(HeatmapNew heatmap)
	{
		int maxVal = 0;
		int minVal = Integer.MAX_VALUE;
		if (heatmap.getEntrySet().isEmpty())
		{
			return new int[]{0, 0};
		}
		for (Map.Entry<WorldPoint, Integer> tile : heatmap.getEntrySet())
		{
			Point point = new Point(tile.getKey().getX(), tile.getKey().getY());
			if (isGameTileInImageBounds(point))
			{
				if (tile.getValue() > maxVal)
				{
					maxVal = tile.getValue();
				}
				if (tile.getValue() < minVal)
				{
					minVal = tile.getValue();
				}
			}
		}
		return new int[]{maxVal, minVal};
	}

	public boolean isGameTileInImageBounds(Point point)
	{
		Point pixelLocation = gameCoordsToImageCoords(point);
		return pixelLocation.x >= 0 && pixelLocation.x < getWidth() && pixelLocation.y >= 0 && pixelLocation.y < getHeight();
	}

	@Override
	public int getHeight()
	{
		try
		{
			return worldMapImageReader.getHeight(0);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public int getMinX()
	{
		return 0;
	}

	@Override
	public int getMinY()
	{
		return 0;
	}

	@Override
	public int getNumXTiles()
	{
		return numXTiles;
	}

	@Override
	public int getNumYTiles()
	{
		return numYTiles;
	}

	@Override
	public int getMinTileX()
	{
		return 0;
	}

	@Override
	public int getMinTileY()
	{
		return 0;
	}

	@Override
	public int getTileWidth()
	{
		return getWidth() / numXTiles;
	}

	@Override
	public int getTileHeight()
	{
		return getHeight() / numYTiles;
	}

	@Override
	public int getTileGridXOffset()
	{
		return 0;
	}

	@Override
	public int getTileGridYOffset()
	{
		return 0;
	}

	@Override
	public Raster getTile(int tileX, int tileY)
	{
		int x = tileX * getTileWidth();
		int y = tileY * getTileHeight();
		return getData(new Rectangle(x, y, getTileWidth(), getTileHeight()));
	}

	@Override
	public Raster getData()
	{
		return getData(new Rectangle(0, 0, getWidth(), getHeight()));
	}

	@Override
	public Raster getData(Rectangle rect)
	{
		ImageReadParam readParam = worldMapImageReader.getDefaultReadParam();
		readParam.setSourceRegion(rect);
		try
		{
			// Reads only the specified rect from osrs_world_map.png into memory
			BufferedImage bi = worldMapImageReader.read(0, readParam);
			processImageRegion(bi, rect);
			return bi.getData();
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public WritableRaster copyData(WritableRaster raster)
	{
		return null;
	}

	/**
	 * Assumes that the image will be processed in natural reading order pixel-wise (left-to-right, top-to-bottom) otherwise it won't work.
	 * Make sure that initializeProcessingParameters() has been run before running this
	 *
	 * @param imageRegion The image region to be drawn on
	 * @param region      The x,y,width,height coordinates of where the imageRegion came from in the whole image
	 */
	public void processImageRegion(BufferedImage imageRegion, Rectangle region)
	{
		// Run them heatmap tiles through the ol' rigamarole
		// For each pixel in current image region
		while (!sortedHeatmapTiles.isEmpty())
		{
			Map.Entry<Point, Integer> gameTile = sortedHeatmapTiles.poll();
			Point tilePixel = gameTile.getKey(); // tilePixel is the upper-left coordinate of the 4x4 pixel square that this tile covers
			tilePixel = gameCoordsToImageCoords(tilePixel);
			boolean isInImageBounds = (tilePixel.x >= 0 && tilePixel.y >= 0 && tilePixel.x < getWidth() && tilePixel.y < getHeight());
			int tileValue = gameTile.getValue();

			boolean pixelIsBeforeRegion = (compareNaturalReadingOrder(tilePixel.x, tilePixel.y, region.x, region.y) < 0);
			boolean pixelIsAfterRegion = (compareNaturalReadingOrder(tilePixel.x, tilePixel.y, region.x + region.width, region.y + region.height) > 0);
			// If current tile is after bottom right edge of current image region in reading order
			if (pixelIsAfterRegion)
			{
				// put it back in the front of the queue and return
				sortedHeatmapTiles.addFirst(gameTile);
				return;
			}
			// If current tile is before upper left edge of current image region, or is out of bounds, or hasn't been stepped on, skip this tile
			if (pixelIsBeforeRegion || !isInImageBounds || tileValue == 0)
			{
				continue;
			}
			// Else continue

			// Calculate color
			double currHue = calculateHue(tileValue, heatmapSensitivity, heatmapMinVal, heatmapMaxVal);
			// Reassign the new RGB values to the corresponding 9 pixels (each tile covers 3x3 image pixels)
			for (int x_offset = 0; x_offset < 4; x_offset++)
			{
				for (int y_offset = 0; y_offset < 4; y_offset++)
				{
					int curX = tilePixel.x - region.x + x_offset;
					int curY = tilePixel.y - region.y + y_offset;
					if (curY >= imageRegion.getHeight())
					{
						// put it back in the front of the queue and return
						sortedHeatmapTiles.addFirst(gameTile);
						return;
					}
					else if (curX >= imageRegion.getWidth()){
						// Skip this pixel
						continue;
					}
					int srcRGB = imageRegion.getRGB(curX, curY);
					int r = (srcRGB >> 16) & 0xFF;
					int g = (srcRGB >> 8) & 0xFF;
					int b = (srcRGB) & 0xFF;
					float brightness = Color.RGBtoHSB(r, g, b, null)[2] * (1 - heatmapTransparency) + heatmapTransparency;
					// convert HSB to RGB with the calculated Hue, with Saturation=1
					int currRGB = Color.HSBtoRGB((float) currHue, 1, brightness);
					imageRegion.setRGB(curX, curY, currRGB);
				}
			}
		}
	}

	private double calculateHue(int tileValue, int heatmapSensitivity, int minVal, int maxVal)
	{
		double nthRoot = 1 + (heatmapSensitivity - 1.0) / 2;
		int logBase = 4;
		double minHue = 1 / 3.0;
		double maxHue = 0.0;
		double currHue = (float) ((Math.log(tileValue) / Math.log(logBase)) / (Math.log(maxVal + 1 - minVal) / Math.log(logBase)));
		currHue = Math.pow(currHue, 1.0 / nthRoot);
		currHue = (float) (minHue + (currHue * (maxHue - minHue))); // Assign a hue based on normalized step value (values [0, 1] are mapped linearly to hues of [0, 0.333] aka green then yellow, then red)
		return currHue;
	}

	private int compareNaturalReadingOrder(int x1, int y1, int x2, int y2)
	{
		// This should return the difference in the row-major order of the two points
		int rowMajor1 = y1 * getWidth() + x1;
		int rowMajor2 = y2 * getWidth() + x2;
		return rowMajor1 - rowMajor2;
	}

	private void initializeProcessingVariables(HeatmapNew heatmap)
	{
		// Get min/max values within writeable region to be written
		int[] maxMin = calculateMaxMinValues(heatmap);
		heatmapMaxVal = maxMin[0];
		heatmapMinVal = maxMin[1];

		// Create sorted heatmap tiles array (sorted left-to-right top-to-bottom)
		// Converting Points to WorldPoints
		sortedHeatmapTiles = heatmap.getEntrySet().stream()
			.filter(e -> e.getKey().getPlane() == 0) // Keep only plane 0 overworld tiles
			.map(e -> new AbstractMap.SimpleEntry<>(new Point(e.getKey().getX(), e.getKey().getY()), e.getValue()))
			.sorted((tile1, tile2) -> {
				Point coords1 = tile1.getKey();
				Point coords2 = tile2.getKey();
				return compareNaturalReadingOrder(coords1.x, -coords1.y, coords2.x, -coords2.y);
			}).collect(Collectors.toCollection(LinkedList::new));
	}

	/**
	 * @param gameCoord True gameworld coordinate
	 * @return The upper-left of the 9-pixel square location on the image osrs_world_map.png that this game coordinate responds to (1 game coordinate = 3x3 pixels). If it is out of bounds, then (-1, -1) is returned
	 */
	public Point gameCoordsToImageCoords(Point gameCoord)
	{
		final int IMAGE_WIDTH = getWidth();
		final int IMAGE_HEIGHT = getHeight();
		gameCoord = remapGameTiles(gameCoord);
		Point pixelLocation = new Point(4 * gameCoord.x + PIXEL_OFFSET_X, IMAGE_HEIGHT - (4 * gameCoord.y) + PIXEL_OFFSET_Y);
		if (pixelLocation.x < 0 || pixelLocation.y < 0 || pixelLocation.x > IMAGE_WIDTH || pixelLocation.y > IMAGE_HEIGHT)
		{
			return new Point(-1, -1);
		}
		else
		{
			return pixelLocation;
		}
	}

	/**
	 * This function remaps game tiles, so for example steps made in Prifdinnas which is actually located outside the overworld will be remapped to Prifdinnas's overworld location
	 *
	 * @param point Old location
	 * @return New location
	 */
	private static Point remapGameTiles(Point point)
	{
		// Prifdinnas
		Rectangle prifdinnas = new Rectangle(3391, 5952, 255, 255);
		if (prifdinnas.contains(point))
		{
			return new Point(point.x - 1024, point.y - 2752);
		}

		return point;
	}
}