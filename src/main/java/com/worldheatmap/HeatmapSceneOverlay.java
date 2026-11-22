package com.worldheatmap;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Tints scene tiles with their heatmap colour.
 * Scene iteration adapted from skretzo's Invalid Movement plugin (BSD-2-Clause).
 * <p>
 * Overlays always draw on top of the rendered scene, so to make the tiles look like they're on the floor,
 * they're drawn into an offscreen buffer and the screen silhouettes of actors/objects (their projected model
 * triangles) are erased from it before it's composited. The floor is the lowest surface, so anything overlapping a floor tile on screen is in front of it.
 */
class HeatmapSceneOverlay extends Overlay
{
	private static final int LOCAL_TILE_SIZE = Perspective.LOCAL_TILE_SIZE;
	// Used when an object's model height isn't known, so it's still considered as a possible occluder
	private static final int FALLBACK_OBJECT_HEIGHT = 1024;
	private static final int OCCUPANCY_CELL_SIZE = 32;

	private final Client client;
	private final WorldHeatmapConfig config;
	private final HeatmapOverlayCache cache;

	private BufferedImage buffer;
	private int[] bufferPixels;
	// Reused per model for projected vertex positions
	private int[] projectedX = new int[0];
	private int[] projectedY = new int[0];

	@Inject
	HeatmapSceneOverlay(Client client, WorldHeatmapConfig config, HeatmapOverlayCache cache)
	{
		this.client = client;
		this.config = config;
		this.cache = cache;

		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final HeatmapOverlayCache.TileColours tileColours = config.showSceneOverlay() ? cache.getTileColours() : null;
		if (tileColours == null || client.getLocalPlayer() == null)
		{
			return null;
		}

		final WorldView worldView = client.getTopLevelWorldView();
		final List<Polygon> polys = new ArrayList<>();
		final List<Color> colours = new ArrayList<>();
		collectTiles(worldView, tileColours, polys, colours);
		if (polys.isEmpty())
		{
			return null;
		}

		if (!config.overlayUnderObjects())
		{
			drawTiles(graphics, polys, colours);
			return null;
		}

		// Region of the canvas covered by heatmap tiles, and a coarse grid of which parts of it are covered
		final Rectangle canvas = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
		Rectangle dirty = null;
		for (Polygon poly : polys)
		{
			dirty = dirty == null ? poly.getBounds() : dirty.union(poly.getBounds());
		}
		dirty = dirty.intersection(canvas);
		if (dirty.isEmpty())
		{
			return null;
		}
		final Occupancy occupancy = new Occupancy(polys);

		if (buffer == null || buffer.getWidth() != canvas.width || buffer.getHeight() != canvas.height)
		{
			buffer = new BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB);
			bufferPixels = ((DataBufferInt) buffer.getRaster().getDataBuffer()).getData();
		}

		final Graphics2D g = buffer.createGraphics();
		try
		{
			g.setRenderingHints(graphics.getRenderingHints());
			g.setComposite(AlphaComposite.Clear);
			g.fill(dirty);
			g.setComposite(AlphaComposite.SrcOver);
			drawTiles(g, polys, colours);
		}
		finally
		{
			g.dispose();
		}

		eraseOccluders(worldView, occupancy, dirty);

		graphics.drawImage(buffer.getSubimage(dirty.x, dirty.y, dirty.width, dirty.height), dirty.x, dirty.y, null);
		return null;
	}

	private void collectTiles(WorldView worldView, HeatmapOverlayCache.TileColours tileColours, List<Polygon> polys, List<Color> colours)
	{
		final LocalPoint playerLocation = client.getLocalPlayer().getLocalLocation();
		final int playerX = playerLocation.getSceneX();
		final int playerY = playerLocation.getSceneY();
		final int radius = config.overlaySceneRadius() < 0 ? Integer.MAX_VALUE / 2 : config.overlaySceneRadius();

		final int z = worldView.getPlane();
		final Tile[][] tiles = worldView.getScene().getTiles()[z];

		final int startX = Math.max(playerX - radius, 0);
		final int endX = Math.min(playerX + radius, tiles.length);
		final int startY = Math.max(playerY - radius, 0);
		final int endY = Math.min(playerY + radius, tiles[0].length);

		for (int x = startX; x < endX; x++)
		{
			for (int y = startY; y < endY; y++)
			{
				final Tile tile = tiles[x][y];
				if (tile == null)
				{
					continue;
				}

				final WorldPoint wp = tile.getWorldLocation();
				final Color colour = tileColours.get(wp.getX(), wp.getY(), wp.getPlane());
				if (colour == null)
				{
					continue;
				}

				final Polygon poly = Perspective.getCanvasTilePoly(client, tile.getLocalLocation());
				if (poly == null)
				{
					continue;
				}

				polys.add(poly);
				colours.add(colour);
			}
		}
	}

	private static void drawTiles(Graphics2D graphics, List<Polygon> polys, List<Color> colours)
	{
		for (int i = 0; i < polys.size(); i++)
		{
			graphics.setColor(colours.get(i));
			graphics.fill(polys.get(i));
		}
	}

	/**
	 * Erases the screen silhouettes of walls, scenery, wall decorations and ground items on the current plane from the
	 * tile buffer. Players and NPCs are included too, since the client adds them to the scene as GameObjects each frame.
	 * Floor decorations (GroundObjects) are left out since they lie flat on the floor.
	 * Positions/orientations follow RuneLite's ModelOutlineRenderer. Projecting models is relatively expensive, so each
	 * object's rough screen bounds are checked against the heatmap tiles first.
	 */
	private void eraseOccluders(WorldView worldView, Occupancy occupancy, Rectangle clip)
	{
		final Tile[][] tiles = worldView.getScene().getTiles()[worldView.getPlane()];
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}

				final GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects != null)
				{
					for (GameObject object : gameObjects)
					{
						// Multi-tile objects are on every tile they cover; only take them from their SW tile
						if (object == null || !object.getSceneMinLocation().equals(tile.getSceneLocation()))
						{
							continue;
						}
						final Renderable renderable = object.getRenderable();
						if (mayOverlap(object, object.sizeX(), object.sizeY(), modelHeight(renderable), occupancy))
						{
							eraseRenderable(worldView, renderable, object.getX(), object.getY(), object.getZ() - animationHeightOffset(renderable), object.getModelOrientation(), occupancy, clip);
						}
					}
				}

				final WallObject wall = tile.getWallObject();
				if (wall != null && mayOverlap(wall, 1, 1, Math.max(modelHeight(wall.getRenderable1()), modelHeight(wall.getRenderable2())), occupancy))
				{
					eraseRenderable(worldView, wall.getRenderable1(), wall.getX(), wall.getY(), wall.getZ() - animationHeightOffset(wall.getRenderable1()), 0, occupancy, clip);
					eraseRenderable(worldView, wall.getRenderable2(), wall.getX(), wall.getY(), wall.getZ() - animationHeightOffset(wall.getRenderable2()), 0, occupancy, clip);
				}

				final DecorativeObject decoration = tile.getDecorativeObject();
				if (decoration != null && mayOverlap(decoration, 1, 1, Math.max(modelHeight(decoration.getRenderable()), modelHeight(decoration.getRenderable2())), occupancy))
				{
					eraseRenderable(worldView, decoration.getRenderable(), decoration.getX() + decoration.getXOffset(), decoration.getY() + decoration.getYOffset(),
						decoration.getZ() - animationHeightOffset(decoration.getRenderable()), 0, occupancy, clip);
					eraseRenderable(worldView, decoration.getRenderable2(), decoration.getX() + decoration.getXOffset2(), decoration.getY() + decoration.getYOffset2(),
						decoration.getZ() - animationHeightOffset(decoration.getRenderable2()), 0, occupancy, clip);
				}

				final ItemLayer items = tile.getItemLayer();
				if (items != null)
				{
					int height = items.getHeight() + modelHeight(items.getBottom()) + modelHeight(items.getMiddle()) + modelHeight(items.getTop());
					if (mayOverlap(items, 1, 1, height, occupancy))
					{
						final int z = items.getZ() - items.getHeight();
						eraseRenderable(worldView, items.getBottom(), items.getX(), items.getY(), z, 0, occupancy, clip);
						eraseRenderable(worldView, items.getMiddle(), items.getX(), items.getY(), z, 0, occupancy, clip);
						eraseRenderable(worldView, items.getTop(), items.getX(), items.getY(), z, 0, occupancy, clip);
					}
				}
			}
		}
	}

	private static int animationHeightOffset(Renderable renderable)
	{
		return renderable == null ? 0 : renderable.getAnimationHeightOffset();
	}

	/**
	 * Projects a renderable's model and clears the pixels of its visible faces from the tile buffer.
	 * Faces are skipped the same way RuneLite's ModelOutlineRenderer skips them: behind the camera, (nearly) fully
	 * transparent, or back-facing.
	 */
	private void eraseRenderable(WorldView worldView, Renderable renderable, int x, int y, int z, int orientation, Occupancy occupancy, Rectangle clip)
	{
		if (renderable == null)
		{
			return;
		}
		final Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model == null)
		{
			return;
		}

		final int vertexCount = model.getVerticesCount();
		if (projectedX.length < vertexCount)
		{
			projectedX = new int[vertexCount];
			projectedY = new int[vertexCount];
		}
		// Note the Y/Z swap: model Y is height, matching ModelOutlineRenderer's call
		Perspective.modelToCanvas(client, worldView, vertexCount, x, y, z, orientation,
			model.getVerticesX(), model.getVerticesZ(), model.getVerticesY(), projectedX, projectedY);

		// Skip the faces entirely if the projected model doesn't reach any heatmap tile
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (int i = 0; i < vertexCount; i++)
		{
			if (projectedY[i] == Integer.MIN_VALUE)
			{
				continue;
			}
			minX = Math.min(minX, projectedX[i]);
			maxX = Math.max(maxX, projectedX[i]);
			minY = Math.min(minY, projectedY[i]);
			maxY = Math.max(maxY, projectedY[i]);
		}
		if (minX > maxX || !occupancy.intersects(new Rectangle(minX, minY, maxX - minX, maxY - minY)))
		{
			return;
		}

		final int faceCount = model.getFaceCount();
		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();
		final byte[] transparencies = model.getFaceTransparencies();
		for (int f = 0; f < faceCount; f++)
		{
			final int a = indices1[f];
			final int b = indices2[f];
			final int c = indices3[f];
			if (projectedY[a] == Integer.MIN_VALUE || projectedY[b] == Integer.MIN_VALUE || projectedY[c] == Integer.MIN_VALUE)
			{
				continue;
			}
			if (transparencies != null && (transparencies[f] & 0xff) >= 254)
			{
				continue;
			}
			final int x1 = projectedX[a];
			final int y1 = projectedY[a];
			final int x2 = projectedX[b];
			final int y2 = projectedY[b];
			final int x3 = projectedX[c];
			final int y3 = projectedY[c];
			// Back-facing
			if ((y2 - y1) * (x3 - x2) - (x2 - x1) * (y3 - y2) <= 0)
			{
				continue;
			}
			clearTriangle(clip, x1, y1, x2, y2, x3, y3);
		}
	}

	/**
	 * Clears (makes transparent) the buffer pixels covered by a triangle, within clip. Rounds outwards, so it may
	 * clear up to a pixel extra around the edges, which is fine for hiding the floor behind a model.
	 */
	private void clearTriangle(Rectangle clip, int x1, int y1, int x2, int y2, int x3, int y3)
	{
		// Sort the vertices by y
		int t;
		if (y1 > y2)
		{
			t = x1; x1 = x2; x2 = t;
			t = y1; y1 = y2; y2 = t;
		}
		if (y1 > y3)
		{
			t = x1; x1 = x3; x3 = t;
			t = y1; y1 = y3; y3 = t;
		}
		if (y2 > y3)
		{
			t = x2; x2 = x3; x3 = t;
			t = y2; y2 = y3; y3 = t;
		}

		final int clipBottom = clip.y + clip.height - 1;
		final int clipRight = clip.x + clip.width - 1;
		if (y3 < clip.y || y1 > clipBottom || y1 == y3)
		{
			return;
		}

		final int stride = buffer.getWidth();
		final int yStart = Math.max(y1, clip.y);
		final int yEnd = Math.min(y3, clipBottom);
		for (int y = yStart; y <= yEnd; y++)
		{
			// Long edge (1 to 3) and whichever short edge spans this row
			final double xLong = x1 + (double) (x3 - x1) * (y - y1) / (y3 - y1);
			final double xShort;
			if (y < y2)
			{
				xShort = x1 + (double) (x2 - x1) * (y - y1) / (y2 - y1);
			}
			else
			{
				xShort = y3 == y2 ? x2 : x2 + (double) (x3 - x2) * (y - y2) / (y3 - y2);
			}

			final int left = Math.max(clip.x, (int) Math.floor(Math.min(xLong, xShort)));
			final int right = Math.min(clipRight, (int) Math.ceil(Math.max(xLong, xShort)));
			if (left <= right)
			{
				final int row = y * stride;
				Arrays.fill(bufferPixels, row + left, row + right + 1, 0);
			}
		}
	}

	private static int modelHeight(Renderable renderable)
	{
		return renderable == null ? 0 : Math.max(renderable.getModelHeight(), 0);
	}

	/**
	 * Cheap check of whether an object could overlap any heatmap tile on screen, by projecting the corners of a box
	 * around it (its footprint, from its base up to its model height).
	 */
	private boolean mayOverlap(TileObject object, int sizeX, int sizeY, int height, Occupancy occupancy)
	{
		final LocalPoint lp = object.getLocalLocation();
		if (lp == null)
		{
			return false;
		}

		final int boxHeight = height > 0 ? height + LOCAL_TILE_SIZE / 2 : FALLBACK_OBJECT_HEIGHT;
		final int halfX = sizeX * LOCAL_TILE_SIZE / 2;
		final int halfY = sizeY * LOCAL_TILE_SIZE / 2;
		final int baseZ = object.getZ();

		Rectangle bounds = null;
		for (int dx = -halfX; dx <= halfX; dx += 2 * halfX)
		{
			for (int dy = -halfY; dy <= halfY; dy += 2 * halfY)
			{
				for (int dz = 0; dz >= -boxHeight; dz -= boxHeight)
				{
					final Point p = Perspective.localToCanvas(client, lp.getX() + dx, lp.getY() + dy, baseZ + dz);
					if (p == null)
					{
						continue;
					}
					if (bounds == null)
					{
						bounds = new Rectangle(p.getX(), p.getY(), 0, 0);
					}
					else
					{
						bounds.add(p.getX(), p.getY());
					}
				}
			}
		}
		// If no corner projects (e.g. partly behind the camera), fall back to computing the real shape
		return bounds == null || occupancy.intersects(bounds);
	}

	/**
	 * Coarse screen-space grid of which cells are covered by heatmap tile polygons' bounds.
	 */
	private static class Occupancy
	{
		private final Rectangle area;
		private final int cols;
		private final int rows;
		private final boolean[] cells;

		Occupancy(List<Polygon> polys)
		{
			Rectangle union = null;
			for (Polygon poly : polys)
			{
				union = union == null ? poly.getBounds() : union.union(poly.getBounds());
			}
			area = union;
			cols = area.width / OCCUPANCY_CELL_SIZE + 1;
			rows = area.height / OCCUPANCY_CELL_SIZE + 1;
			cells = new boolean[cols * rows];
			for (Polygon poly : polys)
			{
				final Rectangle b = poly.getBounds();
				for (int cx = col(b.x); cx <= col(b.x + b.width); cx++)
				{
					for (int cy = row(b.y); cy <= row(b.y + b.height); cy++)
					{
						cells[cy * cols + cx] = true;
					}
				}
			}
		}

		boolean intersects(Rectangle r)
		{
			if (!area.intersects(r) && !area.contains(r.x, r.y))
			{
				return false;
			}
			for (int cx = col(r.x); cx <= col(r.x + r.width); cx++)
			{
				for (int cy = row(r.y); cy <= row(r.y + r.height); cy++)
				{
					if (cells[cy * cols + cx])
					{
						return true;
					}
				}
			}
			return false;
		}

		private int col(int x)
		{
			return Math.max(0, Math.min(cols - 1, (x - area.x) / OCCUPANCY_CELL_SIZE));
		}

		private int row(int y)
		{
			return Math.max(0, Math.min(rows - 1, (y - area.y) / OCCUPANCY_CELL_SIZE));
		}
	}
}
