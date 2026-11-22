package com.worldheatmap;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.io.IOException;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.LinkBrowser;

@Slf4j
public class WorldHeatmapPanel extends PluginPanel {

    private final WorldHeatmapPlugin plugin;
    private JLabel playerIDLabel;

    private JLabel totalMemoryUsageLabel;

    // One compact row per enabled heatmap: name on the left, total (or image write status) on the right
    private final Map<Heatmap.HeatmapType, JPanel> heatmapRows = new HashMap<>();
    private final Map<Heatmap.HeatmapType, JLabel> heatmapValueLabels = new HashMap<>();
    // Heatmaps whose row is currently showing an image write status instead of their total
    private final Set<Heatmap.HeatmapType> statusShown = new HashSet<>();
	Map<Heatmap.HeatmapType, Integer> memoryUsageEstimates = new HashMap<>();
	protected long timeOfLastMemoryEstimate = -1;
    // Whether writing images and restarting heatmaps is allowed (off while loading or writing an image)
    private boolean actionsEnabled = true;

    public WorldHeatmapPanel(WorldHeatmapPlugin plugin) {
        this.plugin = plugin;
        rebuild();
    }

    protected void rebuild() {
        removeAll();
        heatmapRows.clear();
        heatmapValueLabels.clear();
        statusShown.clear();
        Font buttonFont = new Font("Runescape", Font.BOLD, 18);

        //Main Panel
        int vGap = 5;
        int hGap = 5;
        JPanel mainPanel = new JPanel(new GridLayout(0, 1, hGap, vGap));
        mainPanel.setBorder(new EmptyBorder(vGap, hGap, vGap, hGap));
        mainPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        add(mainPanel);

        //Player ID label
        if (plugin.currentLocalAccountHash == 0 || plugin.currentLocalAccountHash == -1){
            playerIDLabel = new JLabel("Player ID: unknown");
        }
        else{
            playerIDLabel = new JLabel("Player ID: " + plugin.currentLocalAccountHash);
        }
        playerIDLabel.setHorizontalAlignment(SwingConstants.CENTER);
        mainPanel.add(playerIDLabel);

        // Total Memory Usage estimate label
        int estimatedMemoryUsage = 0;
        for (Heatmap heatmap : plugin.heatmaps.values()) {
            estimatedMemoryUsage += heatmap.estimateSize();
        }
		if (estimatedMemoryUsage == 0) {
			totalMemoryUsageLabel = new JLabel("Estimated Memory Usage: 0MB");
		}
		else {
			totalMemoryUsageLabel = new JLabel("Estimated Memory Usage: " + String.format("%.2f", estimatedMemoryUsage / 1024. / 1024) +  "MB");
		}
        totalMemoryUsageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        mainPanel.add(totalMemoryUsageLabel);

        //'Open Heatmaps Folder' button
        JButton openHeatmapFolderButton = getOpenHeatmapFolderButton(buttonFont);
        mainPanel.add(openHeatmapFolderButton);

        //'Visit Global Heatmap Website' button
        JButton visitGlobalHeatmapButton = getGlobalHeatmapButton(buttonFont);
        mainPanel.add(visitGlobalHeatmapButton);

        // Tips, since the row menus aren't obvious
        JLabel tipsLabel = new JLabel("<html>Click a heatmap to write its image, restart it, or show it on the overlays."
            + "<br>Hover over one for its tile count and age.</html>");
        tipsLabel.setFont(FontManager.getRunescapeFont());
        tipsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        tipsLabel.setBorder(new EmptyBorder(vGap, hGap, 0, hGap));
        add(tipsLabel);

        // Heatmap rows
        JPanel rowsPanel = new JPanel(new GridLayout(0, 1, 0, 1));
        rowsPanel.setBorder(new EmptyBorder(vGap, 0, vGap, 0));
        rowsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        for (Heatmap.HeatmapType heatmapType : Heatmap.HeatmapType.values()) {
            // Do not create rows for disabled heatmaps
            if (!plugin.isHeatmapEnabled(heatmapType)) {
                continue;
            }
            rowsPanel.add(createHeatmapRow(heatmapType));
        }
        add(rowsPanel);

        updateCounts();
    }

    private JPanel createHeatmapRow(Heatmap.HeatmapType heatmapType) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBorder(new EmptyBorder(4, 6, 4, 6));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Name in the centre so it's the part that gets truncated if the row is too narrow
        JLabel nameLabel = new JLabel(heatmapType.toString());
        nameLabel.setForeground(Color.WHITE);
        row.add(nameLabel, BorderLayout.CENTER);

        JLabel valueLabel = new JLabel(heatmapType.formatValue(0));
        valueLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        row.add(valueLabel, BorderLayout.EAST);

        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                // Left or right click opens the menu
                createHeatmapMenu(heatmapType, row).show(row, e.getX(), e.getY());
            }
        });

        heatmapRows.put(heatmapType, row);
        heatmapValueLabels.put(heatmapType, valueLabel);
        return row;
    }

    private JPopupMenu createHeatmapMenu(Heatmap.HeatmapType heatmapType, JPanel row) {
        JPopupMenu menu = new JPopupMenu();
        boolean loaded = plugin.heatmaps.get(heatmapType) != null;

        JMenuItem writeImage = new JMenuItem("Write image");
        writeImage.setEnabled(actionsEnabled && loaded);
        writeImage.addActionListener(e -> writeHeatmapImage(heatmapType, false));
        menu.add(writeImage);

        // Includes underground/non-overworld areas, which takes much longer
        JMenuItem writeFullImage = new JMenuItem("Write full map image (slow)");
        writeFullImage.setEnabled(actionsEnabled && loaded);
        writeFullImage.addActionListener(e -> writeHeatmapImage(heatmapType, true));
        menu.add(writeFullImage);

        JCheckBoxMenuItem showOnOverlays = new JCheckBoxMenuItem("Show on overlays");
        showOnOverlays.setSelected(plugin.config.overlayHeatmapType().getHeatmapType() == heatmapType);
        showOnOverlays.addActionListener(e -> plugin.configManager.setConfiguration(WorldHeatmapConfig.GROUP, "overlayHeatmapType",
            showOnOverlays.isSelected() ? OverlayHeatmap.of(heatmapType) : OverlayHeatmap.NONE));
        menu.add(showOnOverlays);

        menu.addSeparator();

        JMenuItem restart = new JMenuItem("Restart...");
        restart.setEnabled(actionsEnabled && loaded);
        restart.addActionListener(e -> {
            final int result = JOptionPane.showOptionDialog(row,
                    "<html>Art thou sure you want to restart your " + heatmapType.name() + " heatmap? The data will be lost.</html>",
                    "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, new String[]{"Yes", "No"}, "No");

            if (result == JOptionPane.YES_OPTION) {
                clearHeatmap(heatmapType);
            }
        });
        menu.add(restart);

        return menu;
    }

    private JButton getOpenHeatmapFolderButton(Font buttonFont) {
        JButton openHeatmapFolderButton = new JButton("Open Heatmaps Folder");
        openHeatmapFolderButton.setFont(buttonFont);
        openHeatmapFolderButton.addActionListener(e -> {
            try {
                openHeatmapsFolder();
            } catch (IOException exception) {
                log.error("Error: Exception thrown whilst opening worldheatmap folder: {}", exception.getMessage());
            }
        });
        return openHeatmapFolderButton;
    }

    /**
     * Get the button to visit the global heatmap website at osrsworldheatmap.com
     * @param buttonFont The font to use for the button
     * @return The button to visit the global heatmap website
     */
    private static JButton getGlobalHeatmapButton(Font buttonFont) {
        JButton visitGlobalHeatmapButton = new JButton("Global Heatmap Website");
        visitGlobalHeatmapButton.setFont(buttonFont);
        visitGlobalHeatmapButton.addActionListener(e -> {
			String url = "https://www.osrsworldheatmap.com/?ref=plugin";
			LinkBrowser.browse(url);
        });
        return visitGlobalHeatmapButton;
    }

    protected void updatePlayerID() {
        if (this.plugin.currentLocalAccountHash == -1 || this.plugin.currentLocalAccountHash == 0) {
            playerIDLabel = new JLabel("Player ID: unavailable");
        } else {
            playerIDLabel.setText("Player ID: " + this.plugin.currentLocalAccountHash);
        }
        updateUI();
    }

    /**
     * Updates each row's total and tooltip (tile count, memory usage and age).
     */
    protected void updateCounts() {
        for (Map.Entry<Heatmap.HeatmapType, JLabel> entry : heatmapValueLabels.entrySet()) {
            Heatmap.HeatmapType heatmapType = entry.getKey();
            Heatmap heatmap = plugin.heatmaps.get(heatmapType);
            if (!statusShown.contains(heatmapType)) {
                entry.getValue().setText(heatmapType.formatValue(heatmap == null ? 0 : heatmap.getTotalValue()));
            }

            JPanel row = heatmapRows.get(heatmapType);
            if (row == null) {
                continue;
            }
            if (heatmap == null) {
                row.setToolTipText("Not loaded");
            }
            else {
                int gameTimeSeconds = (int) (heatmap.getGameTimeTicks() * 0.6);
                String gameTimeFormatted = String.format("%02d:%02d:%02d", gameTimeSeconds / 3600, (gameTimeSeconds % 3600) / 60, gameTimeSeconds % 60);
                Integer memoryEstimate = memoryUsageEstimates.get(heatmapType);
                row.setToolTipText("<html>" + String.format("%,d", heatmap.getTileCount()) + " tiles"
                    + "<br>Estimated memory usage: " + (memoryEstimate == null ? "?" : String.format("%.2f", memoryEstimate / 1024. / 1024)) + "MB"
                    + "<br>Heatmap age: " + gameTimeFormatted
                    + "<br>Click for options</html>");
            }
        }
        updateUI();
    }

	/**
	 * Update the total memory usage label, and the per-heatmap estimates shown in the row tooltips.
	 * Only re-estimates once a minute, since it has to go through every tile.
	 */
    protected void updateMemoryUsageLabels() {
		if (timeOfLastMemoryEstimate == -1 || System.currentTimeMillis() - timeOfLastMemoryEstimate >= 60_000) {
			timeOfLastMemoryEstimate = System.currentTimeMillis();
			int totalEstimatedMemoryUsage = 0;
			for (Heatmap heatmap : plugin.heatmaps.values()) {
				int estimatedMemoryUsage = heatmap.estimateSize();
				memoryUsageEstimates.put(heatmap.getHeatmapType(), estimatedMemoryUsage);
				totalEstimatedMemoryUsage += estimatedMemoryUsage;
			}
			totalMemoryUsageLabel.setText("Estimated Memory Usage: " + String.format("%.2f", totalEstimatedMemoryUsage / 1024. / 1024) +  "MB");
			updateUI();
		}
    }

    /**
     * Shows an image write status (e.g. "Writing... 45%") in place of a heatmap's total. Safe to call from any thread.
     */
    void setImageWriteStatus(Heatmap.HeatmapType heatmapType, String status, Color colour) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = heatmapValueLabels.get(heatmapType);
            if (label == null) {
                return;
            }
            statusShown.add(heatmapType);
            label.setText(status);
            label.setForeground(colour);
        });
    }

    /**
     * Goes back to showing a heatmap's total after setImageWriteStatus. Safe to call from any thread.
     */
    void clearImageWriteStatus(Heatmap.HeatmapType heatmapType) {
        SwingUtilities.invokeLater(() -> {
            JLabel label = heatmapValueLabels.get(heatmapType);
            if (label == null) {
                return;
            }
            statusShown.remove(heatmapType);
            label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            updateCounts();
        });
    }

    private void writeHeatmapImage(Heatmap.HeatmapType heatmapType, boolean isFullMapImage) {
		Heatmap heatmap = plugin.heatmaps.get(heatmapType);
		if (heatmap == null) {
			return;
		}
        // Save all heatmap data
        plugin.executor.execute(plugin::saveHeatmapsFile);
        // Write the specified heatmap image
		Filepath imageFile = plugin.heatmapFileManager.getNewImageFile(plugin.currentLocalAccountHash, heatmapType, heatmap.getSeasonalType());
        plugin.executor.execute(() -> HeatmapImage.writeHeatmapImage(heatmap, imageFile, isFullMapImage, plugin.config.isBlueMapEnabled(), plugin.config.heatmapAlpha(), plugin.config.heatmapSensitivity(), plugin.config.speedMemoryTradeoff(), new WorldHeatmapPlugin.HeatmapProgressListener(plugin, heatmapType)));
    }

    private void clearHeatmap(Heatmap.HeatmapType heatmapType) {
		log.info("Clearing heatmap: {}", heatmapType);

		// Update the latest heatmap data file
		plugin.executor.execute(plugin::saveHeatmapsFile);

        // Replace the heatmap with a new one
		plugin.executor.execute(() -> plugin.heatmaps.put(heatmapType, new Heatmap(heatmapType, plugin.currentLocalAccountHash, plugin.currentPlayerAccountType, plugin.currentSeasonalType, plugin.currentPlayerCombatLevel)));

        // Start a new .heatmaps data file, so the pre-clearing data is not lost
		plugin.executor.execute(plugin::saveNewHeatmapsFile);
    }

    private void openHeatmapsFolder() throws IOException {
        plugin.pluginDirectory.createDirectories();
		LinkBrowser.open(plugin.pluginDirectory.toString());
	}

    /**
     * Enables/disables writing images and restarting heatmaps from the row menus (e.g. while loading or writing an image).
     */
    void setEnabledHeatmapButtons(boolean onOff) {
        actionsEnabled = onOff;
    }

}
