package com.worldheatmap;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import static net.runelite.api.Perspective.UNIT;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws heatmap colours on the minimap.
 * Minimap drawing and clipping adapted from skretzo's Invalid Movement plugin (BSD-2-Clause).
 */
class HeatmapMinimapOverlay extends Overlay
{
	private final Client client;
	private final WorldHeatmapConfig config;
	private final HeatmapOverlayCache cache;
	private final SpriteManager spriteManager;

	private BufferedImage minimapSpriteFixed;
	private BufferedImage minimapSpriteResizeable;
	private Shape minimapClipFixed;
	private Shape minimapClipResizeable;
	private Rectangle minimapRectangle = new Rectangle();

	@Inject
	HeatmapMinimapOverlay(Client client, WorldHeatmapConfig config, HeatmapOverlayCache cache, SpriteManager spriteManager)
	{
		this.client = client;
		this.config = config;
		this.cache = cache;
		this.spriteManager = spriteManager;

		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Heatmap heatmap = cache.getHeatmap();
		if (!config.showMinimapOverlay() || heatmap == null || client.getLocalPlayer() == null)
		{
			return null;
		}

		final Shape minimapClipArea = getMinimapClipArea();
		if (minimapClipArea == null)
		{
			return null;
		}

		final Shape oldClip = graphics.getClip();
		graphics.setClip(minimapClipArea);
		try
		{
			renderMinimap(graphics, heatmap);
		}
		finally
		{
			graphics.setClip(oldClip);
		}
		return null;
	}

	private void renderMinimap(Graphics2D graphics, Heatmap heatmap)
	{
		final LocalPoint playerLocation = client.getLocalPlayer().getLocalLocation();
		final int playerX = playerLocation.getSceneX();
		final int playerY = playerLocation.getSceneY();
		final int radius = config.overlayMinimapRadius() < 0 ? Integer.MAX_VALUE / 2 : config.overlayMinimapRadius();

		final WorldView worldView = client.getTopLevelWorldView();
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

				final Point posOnMinimap = Perspective.localToMinimap(client, tile.getLocalLocation());
				if (posOnMinimap == null)
				{
					continue;
				}

				drawSquare(graphics, posOnMinimap, Utils.heatmapColor(value, sensitivity, minVal, maxVal, alpha));
			}
		}
	}

	private void drawSquare(Graphics2D graphics, Point center, Color color)
	{
		final int x = center.getX();
		final int y = center.getY();
		final double angle = (client.getCameraYawTarget() & 0x7FF) * UNIT;

		final int width = (int) client.getMinimapZoom();
		final int height = (int) client.getMinimapZoom();

		final int a = (width % 2 == 0) ? 1 : 0;
		final int b = (height % 2 == 0) ? 1 : 2;

		graphics.setColor(color);
		graphics.rotate(angle, x, y);
		graphics.fillRect(x - width / 2 + a, y - height / 2 - b, width, height);
		graphics.rotate(-angle, x, y);
	}

	private Shape getMinimapClipArea()
	{
		Widget minimapWidget = getMinimapDrawWidget();

		if (minimapWidget == null || minimapWidget.isHidden() || !minimapRectangle.equals(minimapRectangle = minimapWidget.getBounds()))
		{
			minimapClipFixed = null;
			minimapClipResizeable = null;
			minimapSpriteFixed = null;
			minimapSpriteResizeable = null;
		}

		if (client.isResized())
		{
			if (minimapClipResizeable != null)
			{
				return minimapClipResizeable;
			}
			if (minimapSpriteResizeable == null)
			{
				minimapSpriteResizeable = spriteManager.getSprite(SpriteID.RESIZE_MAP_MASK, 0);
			}
			if (minimapSpriteResizeable != null)
			{
				return minimapClipResizeable = bufferedImageToPolygon(minimapSpriteResizeable);
			}
			return getMinimapClipAreaSimple();
		}
		if (minimapClipFixed != null)
		{
			return minimapClipFixed;
		}
		if (minimapSpriteFixed == null)
		{
			minimapSpriteFixed = spriteManager.getSprite(SpriteID.FIXED_MAP_MASK, 0);
		}
		if (minimapSpriteFixed != null)
		{
			return minimapClipFixed = bufferedImageToPolygon(minimapSpriteFixed);
		}
		return getMinimapClipAreaSimple();
	}

	private Widget getMinimapDrawWidget()
	{
		if (client.isResized())
		{
			if (client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1)
			{
				return client.getWidget(InterfaceID.ToplevelPreEoc.MINIMAP);
			}
			return client.getWidget(InterfaceID.ToplevelOsrsStretch.MINIMAP);
		}
		return client.getWidget(InterfaceID.Toplevel.MINIMAP);
	}

	private Shape getMinimapClipAreaSimple()
	{
		Widget minimapDrawArea = getMinimapDrawWidget();

		if (minimapDrawArea == null || minimapDrawArea.isHidden())
		{
			return null;
		}

		Rectangle bounds = minimapDrawArea.getBounds();

		return new Ellipse2D.Double(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
	}

	private Polygon bufferedImageToPolygon(BufferedImage image)
	{
		int outsideColour = -1;
		int previousColour;
		final int width = image.getWidth();
		final int height = image.getHeight();
		List<java.awt.Point> points = new ArrayList<>();
		for (int y = 0; y < height; y++)
		{
			previousColour = outsideColour;
			for (int x = 0; x < width; x++)
			{
				int colour = image.getRGB(x, y);
				if (x == 0 && y == 0)
				{
					outsideColour = colour;
					previousColour = colour;
				}
				if (colour != outsideColour && previousColour == outsideColour)
				{
					points.add(new java.awt.Point(x, y));
				}
				if ((colour == outsideColour || x == (width - 1)) && previousColour != outsideColour)
				{
					points.add(0, new java.awt.Point(x, y));
				}
				previousColour = colour;
			}
		}
		int offsetX = 0;
		int offsetY = 0;
		Widget minimapDrawWidget = getMinimapDrawWidget();
		if (minimapDrawWidget != null)
		{
			offsetX = minimapDrawWidget.getBounds().x;
			offsetY = minimapDrawWidget.getBounds().y;
		}
		Polygon polygon = new Polygon();
		for (java.awt.Point point : points)
		{
			polygon.addPoint(point.x + offsetX, point.y + offsetY);
		}
		return polygon;
	}
}
