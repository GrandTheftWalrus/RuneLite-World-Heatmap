package com.worldheatmap;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.config.ConfigManager;

/**
 * A heatmap selector button in the world map's bottom bar, to the right of the location selector. It's made of
 * dynamic widgets copied from the location selector's own pieces so it matches the game's style. Clicking it opens
 * WorldMapHeatmapList, which sets the overlayHeatmapType config, so it stays in sync with the config panel.
 * Next to it is a globe icon that toggles global heatmaps (the overlayUseGlobalHeatmaps config).
 * Must only be used on the client thread.
 * <p>
 * The bar's layout comes from the world map's clientscripts (the location selector is centred and sized to the widest
 * map name, the zoom/overview buttons are anchored right), so this re-lays itself out every client tick from the real
 * widget positions, and rebuilds itself if the world map is reopened or its children are recreated.
 */
@Singleton
class WorldMapHeatmapSelector
{
	private static final int GAP = 4;
	// Horizontal padding the game uses around the location selector's text (its width is the text width + 52)
	private static final int BUTTON_PADDING = 52;
	private static final int MIN_BUTTON_WIDTH = 80;
	private static final String PREFIX = "Heatmap: ";
	private static final int GLOBE_SIZE = 16;
	// The globe is dimmed while global heatmaps are off (0 = opaque, 255 = invisible)
	private static final int GLOBE_OFF_OPACITY = 150;

	private final Client client;
	private final ConfigManager configManager;
	private final WorldHeatmapConfig config;
	private final WorldMapHeatmapList list;
	private final GlobalHeatmapLoader globalLoader;

	// The bottom bar this was built under, to detect the world map being reopened
	private Widget builtBottom;
	private Widget button;
	private Widget buttonLabel;
	private Widget globeButton;
	private Widget globeIcon;

	@Inject
	WorldMapHeatmapSelector(Client client, ConfigManager configManager, WorldHeatmapConfig config, WorldMapHeatmapList list, GlobalHeatmapLoader globalLoader)
	{
		this.client = client;
		this.configManager = configManager;
		this.config = config;
		this.list = list;
		this.globalLoader = globalLoader;
	}

	void onClientTick()
	{
		final Widget bottom = client.getWidget(InterfaceID.Worldmap.BOTTOM);
		final Widget display = client.getWidget(InterfaceID.Worldmap.MAPLIST_DISPLAY);
		final Widget zoomOut = client.getWidget(InterfaceID.Worldmap.ZOOM_OUT);
		if (!config.showWorldMapOverlay() || bottom == null || display == null || zoomOut == null || bottom.isHidden())
		{
			hide();
			return;
		}

		if (!isBuiltUnder(bottom) && !build(bottom, display))
		{
			return;
		}
		layoutButton(display, zoomOut);
	}

	/**
	 * Hides the selector, e.g. on plugin shutdown. Dynamic widgets can't be deleted individually, and they go away
	 * when the world map is closed anyway.
	 */
	void hide()
	{
		if (button != null && !button.isSelfHidden())
		{
			button.setHidden(true);
		}
		if (globeButton != null && !globeButton.isSelfHidden())
		{
			globeButton.setHidden(true);
		}
		list.close();
	}

	private boolean isBuiltUnder(Widget bottom)
	{
		if (button == null || bottom != builtBottom)
		{
			return false;
		}
		final Widget[] children = bottom.getDynamicChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child == button)
				{
					return true;
				}
			}
		}
		return false;
	}

	private boolean build(Widget bottom, Widget display)
	{
		// The location selector's pieces are created by a script, so wait until they exist
		final Widget[] displayPieces = display.getDynamicChildren();
		if (displayPieces == null || displayPieces.length == 0)
		{
			return false;
		}

		list.close();
		button = bottom.createChild(-1, WidgetType.LAYER);
		button.setOriginalY(display.getOriginalY());
		button.setOriginalHeight(display.getOriginalHeight());
		button.setYPositionMode(display.getYPositionMode());
		button.setHeightMode(display.getHeightMode());
		button.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		button.setWidthMode(WidgetSizeMode.ABSOLUTE);
		button.setHasListener(true);
		button.setAction(0, "Choose");
		button.setName("<col=ff9040>Heatmap</col>");
		button.setOnOpListener((JavaScriptCallback) ev -> toggleList());

		buttonLabel = null;
		for (Widget piece : displayPieces)
		{
			if (piece == null)
			{
				continue;
			}
			Widget copy = copyWidget(piece, button);
			if (buttonLabel == null && piece.getType() == WidgetType.TEXT)
			{
				buttonLabel = copy;
			}
		}
		if (buttonLabel == null)
		{
			buttonLabel = button.createChild(-1, WidgetType.TEXT);
			buttonLabel.setWidthMode(WidgetSizeMode.MINUS);
			buttonLabel.setHeightMode(WidgetSizeMode.MINUS);
			buttonLabel.setXTextAlignment(1);
			buttonLabel.setYTextAlignment(1);
			buttonLabel.setFontId(494);
			buttonLabel.setTextColor(0xff981f);
			buttonLabel.setTextShadowed(true);
		}

		globeButton = bottom.createChild(-1, WidgetType.LAYER);
		globeButton.setOriginalY(display.getOriginalY());
		globeButton.setOriginalHeight(display.getOriginalHeight());
		globeButton.setYPositionMode(display.getYPositionMode());
		globeButton.setHeightMode(display.getHeightMode());
		globeButton.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		globeButton.setWidthMode(WidgetSizeMode.ABSOLUTE);
		globeButton.setOriginalWidth(GLOBE_SIZE);
		globeButton.setHasListener(true);
		globeButton.setName("<col=ff9040>Global heatmaps</col>");
		globeButton.setOnOpListener((JavaScriptCallback) ev -> configManager.setConfiguration(
			WorldHeatmapConfig.GROUP, "overlayUseGlobalHeatmaps", !config.overlayUseGlobalHeatmaps()));

		globeIcon = globeButton.createChild(-1, WidgetType.GRAPHIC);
		globeIcon.setSpriteId(WorldHeatmapSprites.GLOBE.getSpriteId());
		globeIcon.setOriginalWidth(GLOBE_SIZE);
		globeIcon.setOriginalHeight(GLOBE_SIZE);
		globeIcon.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
		globeIcon.setYPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);

		builtBottom = bottom;
		return true;
	}

	/**
	 * Copies a widget's look and layout (not its listeners) into a new dynamic child of parent.
	 */
	private static Widget copyWidget(Widget source, Widget parent)
	{
		Widget copy = parent.createChild(-1, source.getType());
		copy.setOriginalX(source.getOriginalX());
		copy.setOriginalY(source.getOriginalY());
		copy.setOriginalWidth(source.getOriginalWidth());
		copy.setOriginalHeight(source.getOriginalHeight());
		copy.setXPositionMode(source.getXPositionMode());
		copy.setYPositionMode(source.getYPositionMode());
		copy.setWidthMode(source.getWidthMode());
		copy.setHeightMode(source.getHeightMode());
		copy.setSpriteId(source.getSpriteId());
		copy.setSpriteTiling(source.getSpriteTiling());
		copy.setFontId(source.getFontId());
		copy.setTextColor(source.getTextColor());
		copy.setTextShadowed(source.getTextShadowed());
		copy.setXTextAlignment(source.getXTextAlignment());
		copy.setYTextAlignment(source.getYTextAlignment());
		copy.setFilled(source.isFilled());
		copy.setOpacity(source.getOpacity());
		copy.setText(source.getText());
		return copy;
	}

	private void layoutButton(Widget display, Widget zoomOut)
	{
		final int x = display.getRelativeX() + display.getWidth() + GAP;
		final int available = zoomOut.getRelativeX() - GAP - x;
		final int globeSpace = GAP + GLOBE_SIZE;

		// In order of preference: full text with the globe, short text with the globe, then without the globe
		// (in which case the label says when global heatmaps are on)
		final String selected = config.overlayHeatmapType().toString();
		String text = null;
		int width = 0;
		boolean showGlobe = false;
		for (int attempt = 0; attempt < 4 && text == null; attempt++)
		{
			showGlobe = attempt < 2;
			final String candidate = (attempt % 2 == 0 ? PREFIX : "") + selected + globalSuffix(showGlobe);
			final int candidateWidth = textWidth(candidate) + BUTTON_PADDING;
			if (candidateWidth + (showGlobe ? globeSpace : 0) <= available)
			{
				text = candidate;
				width = candidateWidth;
			}
		}
		if (text == null)
		{
			// Squeeze the short text into whatever space there is
			showGlobe = false;
			text = selected + globalSuffix(false);
			width = Math.min(textWidth(text) + BUTTON_PADDING, available);
		}

		final boolean fits = width >= MIN_BUTTON_WIDTH;
		if (button.isSelfHidden() == fits)
		{
			button.setHidden(!fits);
		}
		showGlobe &= fits;
		if (globeButton.isSelfHidden() == showGlobe)
		{
			globeButton.setHidden(!showGlobe);
		}
		if (!fits)
		{
			list.close();
			return;
		}

		if (!text.equals(buttonLabel.getText()))
		{
			buttonLabel.setText(text);
		}
		if (button.getOriginalX() != x || button.getOriginalWidth() != width)
		{
			button.setOriginalX(x);
			button.setOriginalWidth(width);
			button.revalidate();
			Widget[] pieces = button.getDynamicChildren();
			if (pieces != null)
			{
				for (Widget piece : pieces)
				{
					if (piece != null)
					{
						piece.revalidate();
					}
				}
			}
			// The list is positioned from the button, so close it rather than leave it floating
			list.close();
		}

		if (showGlobe)
		{
			layoutGlobe(x + width + GAP);
		}
	}

	private void layoutGlobe(int x)
	{
		final boolean global = config.overlayUseGlobalHeatmaps();
		final int opacity = global ? 0 : GLOBE_OFF_OPACITY;
		if (globeIcon.getOpacity() != opacity)
		{
			globeIcon.setOpacity(opacity);
		}
		final String action = global ? "Turn off" : "Turn on";
		final String[] actions = globeButton.getActions();
		if (actions == null || actions.length == 0 || !action.equals(actions[0]))
		{
			globeButton.setAction(0, action);
		}
		if (globeButton.getOriginalX() != x)
		{
			globeButton.setOriginalX(x);
			globeButton.revalidate();
			globeIcon.revalidate();
		}
	}

	/**
	 * In global mode, what the global data is doing (loading etc.), or just that it's global if the globe isn't shown
	 */
	private String globalSuffix(boolean globeShown)
	{
		if (!config.overlayUseGlobalHeatmaps() || config.overlayHeatmapType() == OverlayHeatmap.NONE)
		{
			return "";
		}
		switch (globalLoader.getStatus())
		{
			case LOADING:
				return " (Loading...)";
			case UNAVAILABLE:
				return " (No global data)";
			case ERROR:
				return " (Error)";
			default:
				return globeShown ? "" : " (Global)";
		}
	}

	private int textWidth(String text)
	{
		FontTypeFace font = buttonLabel.getFont();
		return font == null ? text.length() * 6 : font.getTextWidth(text);
	}

	private void toggleList()
	{
		if (list.isOpen())
		{
			list.close();
			return;
		}
		final Widget window = client.getWidget(InterfaceID.Worldmap.WINDOW);
		if (window != null)
		{
			list.open(button.getBounds(), window.getBounds());
		}
	}
}
