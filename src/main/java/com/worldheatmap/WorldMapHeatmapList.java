package com.worldheatmap;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The open list of the world map heatmap selector (see WorldMapHeatmapSelector), listing None plus the enabled heatmaps.
 * <p>
 * It's an overlay rather than widgets because RuneLite draws world map icons (quest starts, transports etc.) as an
 * overlay after the whole world map interface, so they'd show through widgets. RuneLite only cuts the location
 * selector's own list out of that, so this draws above all widgets instead, using that list's background/border
 * sprites so it looks the same. Clicks are handled as a mouse listener; while it's open, all clicks are consumed so
 * clicking outside it just closes it, like the location list.
 * <p>
 * Mouse events arrive on the AWT thread and rendering happens on the client thread, so the state they share is one
 * immutable snapshot swapped in and out.
 */
@Singleton
class WorldMapHeatmapList extends Overlay implements MouseListener
{
	private static final int GAP = 4;
	private static final int ROW_HEIGHT = 16;
	private static final int PADDING = 6;
	private static final int COLUMN_PADDING = 12;
	private static final Color TEXT_COLOUR = new Color(0xff981f);
	private static final Color TEXT_COLOUR_HOVER = Color.WHITE;
	private static final Color TEXT_COLOUR_SELECTED = new Color(0x00ff00);
	private static final Color FALLBACK_FILL = new Color(0x2e2a24);
	private static final Color FALLBACK_BORDER = new Color(0x0e0e0c);

	private final Client client;
	private final ConfigManager configManager;
	private final WorldHeatmapConfig config;
	private final WorldHeatmapPlugin plugin;
	private final SpriteManager spriteManager;

	/**
	 * Where the list is open and what's in it. Rows are laid out on the first render, since that needs font metrics.
	 */
	private static final class State
	{
		final Rectangle button;
		final Rectangle window;
		final Rectangle box;
		final List<OverlayHeatmap> choices;
		final List<Rectangle> rows;

		State(Rectangle button, Rectangle window, Rectangle box, List<OverlayHeatmap> choices, List<Rectangle> rows)
		{
			this.button = button;
			this.window = window;
			this.box = box;
			this.choices = choices;
			this.rows = rows;
		}
	}

	private volatile State state;
	// Whether the last press was consumed by the list, so the matching release/click are consumed too
	private volatile boolean consumingClick;

	@Inject
	WorldMapHeatmapList(Client client, ConfigManager configManager, WorldHeatmapConfig config, WorldHeatmapPlugin plugin, SpriteManager spriteManager)
	{
		this.client = client;
		this.configManager = configManager;
		this.config = config;
		this.plugin = plugin;
		this.spriteManager = spriteManager;

		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	boolean isOpen()
	{
		return state != null;
	}

	/**
	 * @param button the selector button's canvas bounds
	 * @param window the world map window's canvas bounds
	 */
	void open(Rectangle button, Rectangle window)
	{
		final List<OverlayHeatmap> choices = new ArrayList<>();
		final OverlayHeatmap current = config.overlayHeatmapType();
		for (OverlayHeatmap choice : OverlayHeatmap.values())
		{
			if (choice == OverlayHeatmap.NONE || choice == current || plugin.isHeatmapEnabled(choice.getHeatmapType()))
			{
				choices.add(choice);
			}
		}
		state = new State(button, window, null, choices, null);
	}

	void close()
	{
		state = null;
	}

	/**
	 * Whether the given canvas point is over the open list, e.g. to suppress map tooltips under it.
	 */
	boolean isOverList(int x, int y)
	{
		final State s = state;
		return s != null && s.box != null && s.box.contains(x, y);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		State s = state;
		if (s == null)
		{
			return null;
		}

		graphics.setFont(FontManager.getRunescapeFont());
		final FontMetrics metrics = graphics.getFontMetrics();
		if (s.box == null)
		{
			s = layout(s, metrics);
			// Only publish the layout if the list wasn't closed or reopened meanwhile
			if (state != null && state.box == null && state.button == s.button)
			{
				state = s;
			}
		}

		drawBackground(graphics, s.box);

		final OverlayHeatmap current = config.overlayHeatmapType();
		final Point mouse = client.getMouseCanvasPosition();
		for (int i = 0; i < s.choices.size(); i++)
		{
			final OverlayHeatmap choice = s.choices.get(i);
			final Rectangle row = s.rows.get(i);
			final boolean hovered = mouse != null && row.contains(mouse.getX(), mouse.getY());
			final Color colour = hovered ? TEXT_COLOUR_HOVER : choice == current ? TEXT_COLOUR_SELECTED : TEXT_COLOUR;

			final String text = choice.toString();
			final int textX = row.x;
			final int textY = row.y + (row.height + metrics.getAscent() - metrics.getDescent()) / 2;
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, textX + 1, textY + 1);
			graphics.setColor(colour);
			graphics.drawString(text, textX, textY);
		}
		return null;
	}

	/**
	 * Places the list above the button, in columns if it's too tall for the space above the bar.
	 */
	private static State layout(State s, FontMetrics metrics)
	{
		final int n = s.choices.size();
		final int availableHeight = s.button.y - s.window.y - GAP - 2 * PADDING;
		final int rowsPerColumn = Math.max(1, Math.min(n, availableHeight / ROW_HEIGHT));
		final int columns = (n + rowsPerColumn - 1) / rowsPerColumn;
		int columnWidth = 0;
		for (OverlayHeatmap choice : s.choices)
		{
			columnWidth = Math.max(columnWidth, metrics.stringWidth(choice.toString()));
		}
		columnWidth += COLUMN_PADDING;

		final int width = Math.max(s.button.width, columns * columnWidth + 2 * PADDING);
		final int height = rowsPerColumn * ROW_HEIGHT + 2 * PADDING;
		final int x = Math.max(s.window.x, Math.min(s.button.x, s.window.x + s.window.width - width));
		final int y = Math.max(s.window.y, s.button.y - GAP - height);
		final Rectangle box = new Rectangle(x, y, width, height);

		final List<Rectangle> rows = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
		{
			rows.add(new Rectangle(x + PADDING + (i / rowsPerColumn) * columnWidth, y + PADDING + (i % rowsPerColumn) * ROW_HEIGHT, columnWidth, ROW_HEIGHT));
		}
		return new State(s.button, s.window, box, s.choices, rows);
	}

	/**
	 * Draws the location list's background graphic and border pieces, laid out for this box the way the game would.
	 */
	private void drawBackground(Graphics2D graphics, Rectangle box)
	{
		final Widget mapListBox = client.getWidget(InterfaceID.Worldmap.MAPLIST_BOX);
		boolean drewAny = false;
		if (mapListBox != null)
		{
			// Background first so the border pieces draw over it
			drewAny = drawPieces(graphics, box, mapListBox.getStaticChildren());
			drewAny |= drawPieces(graphics, box, mapListBox.getDynamicChildren());
		}
		if (!drewAny)
		{
			graphics.setColor(FALLBACK_FILL);
			graphics.fill(box);
			graphics.setColor(FALLBACK_BORDER);
			graphics.drawRect(box.x, box.y, box.width - 1, box.height - 1);
		}
	}

	private boolean drawPieces(Graphics2D graphics, Rectangle box, Widget[] pieces)
	{
		boolean drewAny = false;
		if (pieces == null)
		{
			return false;
		}
		for (Widget piece : pieces)
		{
			if (piece == null || piece.isSelfHidden())
			{
				continue;
			}

			final Rectangle r = layoutPiece(piece, box);
			if (piece.getType() == WidgetType.GRAPHIC)
			{
				final BufferedImage sprite = piece.getSpriteId() < 0 ? null : spriteManager.getSprite(piece.getSpriteId(), 0);
				if (sprite == null)
				{
					continue;
				}
				if (piece.getSpriteTiling())
				{
					graphics.setPaint(new TexturePaint(sprite, new Rectangle(r.x, r.y, sprite.getWidth(), sprite.getHeight())));
					graphics.fill(r);
				}
				else
				{
					graphics.drawImage(sprite, r.x, r.y, r.width, r.height, null);
				}
				drewAny = true;
			}
			else if (piece.getType() == WidgetType.RECTANGLE)
			{
				final int alpha = 255 - piece.getOpacity();
				graphics.setColor(new Color((alpha << 24) | (piece.getTextColor() & 0xffffff), true));
				if (piece.isFilled())
				{
					graphics.fill(r);
				}
				else
				{
					graphics.drawRect(r.x, r.y, r.width - 1, r.height - 1);
				}
				drewAny = true;
			}
		}
		return drewAny;
	}

	/**
	 * A widget's bounds inside a parent of the given bounds, from its size/position modes (as the client lays them out).
	 */
	private static Rectangle layoutPiece(Widget w, Rectangle parent)
	{
		final int width = size(w.getWidthMode(), w.getOriginalWidth(), parent.width);
		final int height = size(w.getHeightMode(), w.getOriginalHeight(), parent.height);
		final int x = position(w.getXPositionMode(), w.getOriginalX(), parent.width, width);
		final int y = position(w.getYPositionMode(), w.getOriginalY(), parent.height, height);
		return new Rectangle(parent.x + x, parent.y + y, width, height);
	}

	private static int size(int mode, int value, int parentSize)
	{
		switch (mode)
		{
			case WidgetSizeMode.MINUS:
				return parentSize - value;
			case WidgetSizeMode.ABSOLUTE_16384THS:
				return parentSize * value >> 14;
			default:
				return value;
		}
	}

	private static int position(int mode, int value, int parentSize, int size)
	{
		switch (mode)
		{
			case WidgetPositionMode.ABSOLUTE_CENTER:
				return (parentSize - size) / 2 + value;
			case WidgetPositionMode.ABSOLUTE_RIGHT:
				return parentSize - size - value;
			case WidgetPositionMode.LEFT_16384THS:
				return parentSize * value >> 14;
			case WidgetPositionMode.CENTER_16384THS:
				return (parentSize - size) / 2 + (parentSize * value >> 14);
			case WidgetPositionMode.RIGHT_16384THS:
				return parentSize - size - (parentSize * value >> 14);
			default:
				return value;
		}
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		final State s = state;
		if (s == null || s.box == null)
		{
			consumingClick = false;
			return event;
		}

		consumingClick = true;
		event.consume();

		final Point mouse = client.getMouseCanvasPosition();
		if (SwingUtilities.isLeftMouseButton(event) && mouse != null)
		{
			for (int i = 0; i < s.rows.size(); i++)
			{
				if (s.rows.get(i).contains(mouse.getX(), mouse.getY()))
				{
					configManager.setConfiguration(WorldHeatmapConfig.GROUP, "overlayHeatmapType", s.choices.get(i));
					close();
					return event;
				}
			}
		}
		// Anywhere else (including the selector button) closes it; clicks on the box itself are ignored
		if (mouse == null || !s.box.contains(mouse.getX(), mouse.getY()))
		{
			close();
		}
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		if (consumingClick)
		{
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		if (consumingClick)
		{
			event.consume();
		}
		return event;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent event)
	{
		return event;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent event)
	{
		return event;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		return event;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		return event;
	}
}
