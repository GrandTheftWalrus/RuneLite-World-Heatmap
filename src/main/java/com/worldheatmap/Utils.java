package com.worldheatmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Constants;
import net.runelite.api.Point;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
public class Utils
{

	/**
	 * Serializes heatmap as CSV, zips it, and then uploads it to HEATMAP_SITE_API_ENDPOINT using OkHttpClient.
	 *
	 * @param heatmaps
	 * @return Whether the upload was successful
	 */
	static boolean uploadHeatmaps(Map<HeatmapNew.HeatmapType, HeatmapNew> heatmaps, OkHttpClient okHttpClient)
	{
		if (heatmaps.isEmpty())
		{
			return false;
		}

		String HEATMAP_SITE_API_ENDPOINT = "https://osrsworldheatmap.com/api/upload-csv/";
		try
		{
			// Zip the CSV
			ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream))
			{
				for (HeatmapNew heatmap : heatmaps.values())
				{
					ZipEntry zipEntry = new ZipEntry(heatmap.getHeatmapType() + "_HEATMAP.csv");
					zipOutputStream.putNextEntry(zipEntry);
					OutputStreamWriter osw = new OutputStreamWriter(zipOutputStream);
					heatmap.toCSV(osw);
					osw.flush();
					zipOutputStream.closeEntry();
				}
			}

			// Prepare the request body
			RequestBody requestBody = RequestBody.create(
				MediaType.parse("application/zip"),
				byteArrayOutputStream.toByteArray()
			);

			// Build the request
			Request request = new Request.Builder()
				.url(HEATMAP_SITE_API_ENDPOINT)
				.post(requestBody)
				.build();

			// Execute the request
			try (Response response = okHttpClient.newCall(request).execute())
			{
				if (response.isSuccessful())
				{
					return true;
				}
				else
				{
					log.error("Failed to upload heatmaps: HTTP {} {}", response.code(), response.message());
				}
			}
		}
		catch (IOException e)
		{
			log.error("Error uploading heatmap to {}", HEATMAP_SITE_API_ENDPOINT, e);
		}

		log.error("Failed to upload heatmaps");
		return false;
	}

	/**
	 * Returns the list of discrete coordinates on the path between p0 and p1, as an array of Points
	 * Credit to https:// www.redblobgames.com/grids/line-drawing.html for where I figured out how
	 * to make the following linear interpolation functions
	 *
	 * @param p0 Point A
	 * @param p1 Point B
	 * @return Array of coordinates
	 */
	static Point[] getPointsBetween(Point p0, Point p1) {
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
	static int diagonalDistance(Point p0, Point p1) {
		int dx = Math.abs(p1.getX() - p0.getX());
		int dy = Math.abs(p1.getY() - p0.getY());
		return Math.max(dx, dy);
	}

	/**
	 * Rounds a floating point coordinate to its nearest integer coordinate.
	 *
	 * @param point The point to round
	 * @return Coordinates
	 */
	private static Point roundPoint(float[] point) {
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
	private static float[] lerp_point(Point p0, Point p1, float t) {
		return new float[]{lerp(p0.getX(), p1.getX(), t), lerp(p0.getY(), p1.getY(), t)};
	}

	/**
	 * Returns the floating point number that is t-percent of the way between p0 and p1.
	 *
	 * @param p0 Point A
	 * @param p1 Point B
	 * @param t  Percent distance
	 * @return Point that is t-percent of the way from A to B
	 */
	private static float lerp(int p0, int p1, float t) {
		return p0 + (p1 - p0) * t;
	}

	public static boolean isInOverworld(Point point) {
        return point.getY() < Constants.OVERWORLD_MAX_Y && point.getY() > 2500 && point.getX() >= 1024 && point.getX() < 3960;
    }
}
