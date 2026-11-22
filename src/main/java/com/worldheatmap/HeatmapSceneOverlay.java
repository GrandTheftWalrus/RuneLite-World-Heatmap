package com.worldheatmap;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.ItemLayer;
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
 * they're drawn into an offscreen buffer and the screen shapes of actors/objects are erased from it before
 * it's composited. The floor is the lowest surface, so anything overlapping a floor tile on screen is in front of it.
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
		Heatmap heatmap = cache.getHeatmap();
		if (!config.showSceneOverlay() || heatmap == null || client.getLocalPlayer() == null)
		{
			return null;
		}

		final WorldView worldView = client.getTopLevelWorldView();
		final List<Polygon> polys = new ArrayList<>();
		final List<Color> colours = new ArrayList<>();
		collectTiles(worldView, heatmap, polys, colours);
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

		final List<Shape> occluders = new ArrayList<>();
		collectObjectOccluders(worldView, occupancy, occluders);

		if (occluders.isEmpty())
		{
			drawTiles(graphics, polys, colours);
			return null;
		}

		if (buffer == null || buffer.getWidth() != canvas.width || buffer.getHeight() != canvas.height)
		{
			buffer = new BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB);
		}

		final Graphics2D g = buffer.createGraphics();
		try
		{
			g.setRenderingHints(graphics.getRenderingHints());
			g.setComposite(AlphaComposite.Clear);
			g.fill(dirty);
			g.setComposite(AlphaComposite.SrcOver);
			drawTiles(g, polys, colours);

			g.setComposite(AlphaComposite.Clear);
			for (Shape occluder : occluders)
			{
				g.fill(occluder);
			}
		}
		finally
		{
			g.dispose();
		}

		graphics.drawImage(buffer.getSubimage(dirty.x, dirty.y, dirty.width, dirty.height), dirty.x, dirty.y, null);
		return null;
	}

	private void collectTiles(WorldView worldView, Heatmap heatmap, List<Polygon> polys, List<Color> colours)
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

		final int sensitivity = config.heatmapSensitivity();
		final int alpha = config.overlayOpacity();
		final long minVal = cache.getMinVal();
		final long maxVal = cache.getMaxVal();

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
				final long value = heatmap.get(wp.getX(), wp.getY(), wp.getPlane());
				if (value <= 0)
				{
					continue;
				}

				final Polygon poly = Perspective.getCanvasTilePoly(client, tile.getLocalLocation());
				if (poly == null)
				{
					continue;
				}

				polys.add(poly);
				colours.add(Utils.heatmapColor(value, sensitivity, minVal, maxVal, alpha));
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
	 * Adds the screen shapes of walls, scenery, wall decorations and ground items on the current plane.
	 * Players and NPCs are included too, since the client adds them to the scene as GameObjects each frame.
	 * Floor decorations (GroundObjects) are left out since they lie flat on the floor.
	 * Hulls are relatively expensive, so each object's rough screen bounds are checked against the heatmap tiles first.
	 */
	private void collectObjectOccluders(WorldView worldView, Occupancy occupancy, List<Shape> occluders)
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
						if (mayOverlap(object, object.sizeX(), object.sizeY(), modelHeight(object.getRenderable()), occupancy))
						{
							addIfOverlapping(object.getConvexHull(), occupancy, occluders);
						}
					}
				}

				final WallObject wall = tile.getWallObject();
				if (wall != null && mayOverlap(wall, 1, 1, Math.max(modelHeight(wall.getRenderable1()), modelHeight(wall.getRenderable2())), occupancy))
				{
					addIfOverlapping(wall.getConvexHull(), occupancy, occluders);
					addIfOverlapping(wall.getConvexHull2(), occupancy, occluders);
				}

				final DecorativeObject decoration = tile.getDecorativeObject();
				if (decoration != null && mayOverlap(decoration, 1, 1, Math.max(modelHeight(decoration.getRenderable()), modelHeight(decoration.getRenderable2())), occupancy))
				{
					addIfOverlapping(decoration.getConvexHull(), occupancy, occluders);
					addIfOverlapping(decoration.getConvexHull2(), occupancy, occluders);
				}

				final ItemLayer items = tile.getItemLayer();
				if (items != null)
				{
					int height = items.getHeight() + modelHeight(items.getBottom()) + modelHeight(items.getMiddle()) + modelHeight(items.getTop());
					if (mayOverlap(items, 1, 1, height, occupancy))
					{
						addIfOverlapping(items.getClickbox(), occupancy, occluders);
					}
				}
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

	private static void addIfOverlapping(Shape shape, Occupancy occupancy, List<Shape> occluders)
	{
		if (shape != null && occupancy.intersects(shape.getBounds()))
		{
			occluders.add(shape);
		}
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
