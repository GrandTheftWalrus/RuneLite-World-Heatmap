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
import java.io.IOException;
import java.util.LinkedList;
import java.util.Map;
import java.util.Vector;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import lombok.extern.slf4j.Slf4j;

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

	/**
	 * @param worldMapImageReader osrs_world_map.png
	 * @param numYTiles           Image width must be evenly divisible by numYTiles
	 */
	public HeatmapImage(HeatmapNew heatmap, ImageReader worldMapImageReader, int numYTiles, float transparency, int sensitivity)
	{
		this.worldMapImageReader = worldMapImageReader;
		this.numYTiles = numYTiles;
		this.heatmapTransparency = transparency;
		this.heatmapSensitivity = sensitivity;
		initializeProcessingVariables(heatmap);
		try
		{
			if (worldMapImageReader.getHeight(0) % numYTiles != 0)
			{
				log.warn("WARNING: Image height " + worldMapImageReader.getHeight(0) + " is not evenly divisible by the number of Y tiles, " + numYTiles + ".");
			}
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
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
	private int[] calculateMaxMinValuesWithinOverworld(HeatmapNew heatmap)
	{
		int maxVal = 0;
		int minVal = Integer.MAX_VALUE;
		if (heatmap.getEntrySet().isEmpty())
		{
			throw new IllegalArgumentException("Heatmap is empty");
		}
		for (Map.Entry<Point, Integer> tile : heatmap.getEntrySet())
		{
			if (isGameTileInImageBounds(tile.getKey()))
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
		return pixelLocation.x > 0 && pixelLocation.x < getWidth() && pixelLocation.y > 0 && pixelLocation.y < getHeight();
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
			Map.Entry<Point, Integer> tile = sortedHeatmapTiles.poll();
			Point coords = tile.getKey();
			coords = gameCoordsToImageCoords(coords);
			boolean isInImageBounds = (coords.x > 0 && coords.y > 0 && coords.x < getWidth() && coords.y < getHeight());
			int tileValue = tile.getValue();

			int comparison1 = compareNaturalReadingOrder(coords.x, coords.y, region.x, region.y);
			int comparison2 = compareNaturalReadingOrder(coords.x, coords.y, region.x + region.width, region.y + region.height);
			// If current tile is after bottom right edge of current image region in reading order
			if (comparison2 > 0)
			{
				// put it back in the front of the queue and return
				sortedHeatmapTiles.addFirst(tile);
				break;
			}
			// If current tile is before upper left edge of current image region, or is out of bounds of the overworld, or hasn't been stepped on, skip
			if (comparison1 < 0 || !isInImageBounds || tileValue == 0)
			{
				continue;
			}
			// Else continue

			// Calculate color
			double currHue = calculateHue(tileValue, heatmapSensitivity, heatmapMinVal, heatmapMaxVal);

			// Reassign the new RGB values to the corresponding 9 pixels (each tile covers 3x3 image pixels)
			for (int x_offset = 0; x_offset <= 2; x_offset++)
			{
				for (int y_offset = 0; y_offset <= 2; y_offset++)
				{
					int curX = coords.x - region.x + x_offset;
					int curY = coords.y - region.y + y_offset;
					if (curX >= imageRegion.getWidth() || curY >= imageRegion.getHeight())
					{
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

	private static int compareNaturalReadingOrder(int x1, int y1, int x2, int y2)
	{
		if (y1 < y2)
		{
			return -1;
		}
		if (y1 > y2)
		{
			return 1;
		}
		else
		{
			return x1 - x2;
		}
	}

	private void initializeProcessingVariables(HeatmapNew heatmap)
	{
		// Create sorted heatmap tiles array (sorted left-to-right top-to-bottom)
		//int[] minMaxVals = calculateMinMaxValuesWithinOverworld(heatmap);
		//TODO: make it so the following two lines use the above instead of what they're currently using.
		//	I did it earlier but it caused a bug so I undid it so that future me can fix it
		int[] maxMin = calculateMaxMinValuesWithinOverworld(heatmap);
		heatmapMaxVal = maxMin[0];
		heatmapMinVal = maxMin[1];
		// TODO: remove these prints when I fix the stuff
		log.debug("Max Tile: " + heatmapMaxVal);
		log.debug("Min Tile: " + heatmapMinVal);
		log.debug("MAX VAL:");
		sortedHeatmapTiles = new LinkedList<>(heatmap.getEntrySet());
		sortedHeatmapTiles.sort((tile1, tile2) -> {
			Point coords1 = tile1.getKey();
			Point coords2 = tile2.getKey();
			return compareNaturalReadingOrder(coords1.x, -coords1.y, coords2.x, -coords2.y);
		});
	}

	/**
	 * @param point True gameworld coordinate
	 * @return The upper-left of the 9-pixel square location on the image osrs_world_map.png that this game coordinate responds to (1 game coordinate = 3x3 pixels). If it is out of bounds, then (-1, -1) is returned
	 */
	public static Point gameCoordsToImageCoords(Point point)
	{
		int IMAGE_WIDTH = 8800;
		int IMAGE_HEIGHT = 4960;
		int PIXEL_OFFSET_X = -3109;
		int PIXEL_OFFSET_Y = 7462;

		point = remapGameTiles(point);

		Point pixelLocation = new Point(3 * point.x + PIXEL_OFFSET_X, IMAGE_HEIGHT - (3 * point.y) + PIXEL_OFFSET_Y);
		if (pixelLocation.x < 0 || pixelLocation.y < 0 || pixelLocation.x > IMAGE_WIDTH || pixelLocation.y > IMAGE_HEIGHT)
		{
			return new Point(-1, -1);
		}
		else
		{
			return pixelLocation;
		}
	}

	public static Point imageCoordsToGameCoords(Point imageCoords)
	{
		//I haven't checked if this code works
		int IMAGE_WIDTH = 8800;
		int IMAGE_HEIGHT = 4960;
		int PIXEL_OFFSET_X = -3109;
		int PIXEL_OFFSET_Y = 7462;
		if (imageCoords.x < 0 || imageCoords.y < 0 || imageCoords.x > IMAGE_WIDTH || imageCoords.y > IMAGE_HEIGHT)
		{
			return new Point(-1, -1);
		}
		else
		{
			int x = (PIXEL_OFFSET_X - imageCoords.x) / 3;
			int y = (IMAGE_HEIGHT + PIXEL_OFFSET_Y - imageCoords.y) / 3;
			return new Point(x, y);
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