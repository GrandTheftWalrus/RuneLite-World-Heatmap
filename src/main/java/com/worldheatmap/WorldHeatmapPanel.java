package com.worldheatmap;

import java.awt.*;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.io.File;
import java.io.IOException;

@Slf4j
public class WorldHeatmapPanel extends PluginPanel {

    private final WorldHeatmapPlugin plugin;
    private JLabel playerIDLabel;

    private JLabel totalMemoryUsageLabel;

    Map<HeatmapNew.HeatmapType, JPanel> heatmapPanels = new HashMap<>();
    Map<HeatmapNew.HeatmapType, JLabel> heatmapTotalValueLabels = new HashMap<>();
    Map<HeatmapNew.HeatmapType, JLabel> heatmapPanelLabels = new HashMap<>();
    Map<HeatmapNew.HeatmapType, JButton> writeHeatmapImageButtons = new HashMap<>();
    Map<HeatmapNew.HeatmapType, JButton> clearHeatmapButtons = new HashMap<>();
	Map<HeatmapNew.HeatmapType, Integer> memoryUsageEstimates = new HashMap<>();
    protected long mostRecentLocalUserID;
	protected long timeOfLastMemoryEstimate = -1;

    public WorldHeatmapPanel(WorldHeatmapPlugin plugin) {
        this.plugin = plugin;
        rebuild();
    }

    protected void rebuild() {
        removeAll();
        Font buttonFont = new Font("Runescape", Font.BOLD, 18);
        Font sectionLabelFont = new Font("Runescape", Font.BOLD, 18);

        //Main Panel
        int vGap = 5;
        int hGap = 5;
        JPanel mainPanel = new JPanel(new GridLayout(0, 1, hGap, vGap));
        mainPanel.setBorder(new EmptyBorder(vGap, hGap, vGap, hGap));
        mainPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        add(mainPanel);

        //Player ID label
        if (mostRecentLocalUserID == 0 || mostRecentLocalUserID == -1){
            playerIDLabel = new JLabel("Player ID: unknown");
        }
        else{
            playerIDLabel = new JLabel("Player ID: " + mostRecentLocalUserID);
        }
        playerIDLabel.setHorizontalAlignment(SwingConstants.CENTER);
        mainPanel.add(playerIDLabel);

        // Total Memory Usage estimate label
        int estimatedMemoryUsage = 0;
        for (HeatmapNew heatmap : plugin.heatmaps.values()) {
            estimatedMemoryUsage += HeatmapNew.estimateSize(heatmap);
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

        // Create the panels/buttons for each loaded Heatmap type
        for (HeatmapNew.HeatmapType heatmapType : HeatmapNew.HeatmapType.values()) {
            // Do not create panels for disabled heatmaps
            if (!plugin.isHeatmapEnabled(heatmapType)) {
                continue;
            }
            //Panel
            JPanel heatmapPanel = new JPanel(new GridLayout(0, 1, hGap, vGap));
            heatmapPanel.setBorder(new EmptyBorder(vGap, hGap, vGap, hGap));
            heatmapPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            heatmapPanels.put(heatmapType, heatmapPanel);

            //Label
            JLabel heatmapLabel = new JLabel(heatmapType.toString());
            heatmapPanelLabels.put(heatmapType, heatmapLabel);
            heatmapLabel.setFont(sectionLabelFont);
            heatmapLabel.setForeground(Color.WHITE);
            heatmapLabel.setHorizontalAlignment(SwingConstants.CENTER);
            if (plugin.heatmaps.get(heatmapType) != null){
                heatmapLabel.setToolTipText("Estimated memory usage: " + String.format("%.2f", HeatmapNew.estimateSize(plugin.heatmaps.get(heatmapType)) / 1024. / 1024) + "MB");
            }
            else {
                heatmapLabel.setToolTipText("Estimated memory usage: 0MB");
            }

            heatmapPanel.add(heatmapLabel);

            //'Write Heatmap Image' button
            JButton writeHeatmapImageButton = new JButton("Write Heatmap Image");
            writeHeatmapImageButton.setFont(buttonFont);
            writeHeatmapImageButton.addActionListener(e -> writeHeatmapImage(heatmapType, plugin.config.isWriteFullImageEnabled()));
            writeHeatmapImageButtons.put(heatmapType, writeHeatmapImageButton);
            heatmapPanel.add(writeHeatmapImageButton);

            //'Restart Heatmap' button
            JButton clearHeatmapButton = getClearHeatmapButton(heatmapType, buttonFont, heatmapPanel);
            clearHeatmapButtons.put(heatmapType, clearHeatmapButton);
            heatmapPanel.add(clearHeatmapButton);

            // Total value label
            JLabel heatmapTotalValueLabel = new JLabel("Total value: 0");
            heatmapTotalValueLabel.setHorizontalAlignment(SwingConstants.CENTER);
            heatmapTotalValueLabels.put(heatmapType, heatmapTotalValueLabel);
            heatmapPanel.add(heatmapTotalValueLabel);

            add(heatmapPanel);
        }
    }

    private JButton getClearHeatmapButton(HeatmapNew.HeatmapType heatmapType, Font buttonFont, JPanel heatmapPanel) {
        JButton clearHeatmapButton = new JButton("Restart Heatmap");
        clearHeatmapButton.setFont(buttonFont);
        clearHeatmapButton.addActionListener(e -> {
            final int result = JOptionPane.showOptionDialog(heatmapPanel,
                    "<html>Art thou sure you want to restart your " + heatmapType + " heatmap? The data will be lost.</html>",
                    "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, new String[]{"Yes", "No"}, "No");

            if (result == JOptionPane.YES_OPTION) {
                clearHeatmap(heatmapType);
            }
        });
        return clearHeatmapButton;
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
     * @param buttonFont
     * @return
     */
    private static JButton getGlobalHeatmapButton(Font buttonFont) {
        JButton visitGlobalHeatmapButton = new JButton("Global Heatmap Website");
        visitGlobalHeatmapButton.setFont(buttonFont);
        visitGlobalHeatmapButton.addActionListener(e -> {
            try {
                // Try Desktop.browse first (might not work on Linux)
                String url = "https://www.osrsworldheatmap.com";
                if (Desktop.isDesktopSupported()) {
                    Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE)) {
                        desktop.browse(new URI(url));
                        return;
                    }
                }
                boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux");
                if (isLinux) {
                    // Fallback to Runtime.exec or ProcessBuilder
                    String[] browsers = {"xdg-open", "firefox", "google-chrome"};
                    for (String browser : browsers) {
                        try {
                            new ProcessBuilder(browser, url).start();
                            return; // Stop if we successfully open the URL
                        } catch (IOException ignored) {
                        }
                    }
                }
                log.error("Unable to open browser, please visit {} manually", url);
            } catch (Exception exception) {
                log.error("Error: Exception thrown whilst opening browser: {}", exception.getMessage());
            }
        });
        return visitGlobalHeatmapButton;
    }

    protected void updatePlayerID() {
        this.mostRecentLocalUserID = plugin.mostRecentLocalUserID;
        if (this.mostRecentLocalUserID == -1 || this.mostRecentLocalUserID == 0) {
            playerIDLabel = new JLabel("Player ID: unavailable");
        } else {
            playerIDLabel.setText("Player ID: " + this.mostRecentLocalUserID);
        }
        updateUI();
    }

    protected void updateCounts() {
        for (HeatmapNew.HeatmapType heatmapType : plugin.heatmaps.keySet()) {
            if (heatmapTotalValueLabels.get(heatmapType) != null) {
                heatmapTotalValueLabels.get(heatmapType).setText("Total value: " + plugin.heatmaps.get(heatmapType).getTotalValue());
            }
        }
        updateUI();
    }

	/**
	 * Update the memory usage label and the tooltip of each heatmap panel label
	 */
    protected void updateMemoryUsageLabels() {
		// Only update the memory usage label every minute
		// because it is a relatively expensive operation
		if (System.currentTimeMillis() - timeOfLastMemoryEstimate < 60_000 || timeOfLastMemoryEstimate == -1) {
			timeOfLastMemoryEstimate = System.currentTimeMillis();
			int totalEstimatedMemoryUsage = 0;
			for (HeatmapNew heatmap : plugin.heatmaps.values()) {
				int estimatedMemoryUsage = HeatmapNew.estimateSize(heatmap); // TODO: Make this not a static method
				memoryUsageEstimates.put(heatmap.getHeatmapType(), estimatedMemoryUsage);
				totalEstimatedMemoryUsage += estimatedMemoryUsage;
			}
			totalMemoryUsageLabel.setText("Estimated Memory Usage: " + String.format("%.2f", totalEstimatedMemoryUsage / 1024. / 1024) +  "MB");
		}

		// Update the tooltips of the heatmap panel labels
		for (Map.Entry<HeatmapNew.HeatmapType, JLabel> entry : heatmapPanelLabels.entrySet()) {
			JLabel label = entry.getValue();
			HeatmapNew.HeatmapType heatmapType = entry.getKey();
			HeatmapNew heatmap = plugin.heatmaps.get(heatmapType);
			if (label == null || heatmapType == null) {
				continue;
			}
			if (heatmap == null) {
				label.setToolTipText("Estimated memory usage: 0MB (heatmap not loaded)");
			}
			else {
				int gameTimeSeconds = (int)(heatmap.getGameTimeTicks() * 0.6);
				String gameTimeFormatted = String.format("%02d:%02d:%02d", gameTimeSeconds / 3600, (gameTimeSeconds % 3600) / 60, gameTimeSeconds % 60);
				label.setToolTipText("Estimated memory usage: " + String.format("%.2f", memoryUsageEstimates.get(heatmapType) / 1024. / 1024) + "MB"
					+ "\n"
					+ "Heatmap age: " + gameTimeFormatted);
			}
		}

        updateUI();
    }

    private void writeHeatmapImage(HeatmapNew.HeatmapType heatmapType, boolean isFullMapImage) {
        // Save all heatmap data
        File latestFile = HeatmapFile.getLatestHeatmapFile(mostRecentLocalUserID);
        File newFile = HeatmapFile.getCurrentHeatmapFile(mostRecentLocalUserID);
        File heatmapFile = latestFile != null ? latestFile : newFile;
        File imageFile = HeatmapFile.getCurrentImageFile(mostRecentLocalUserID, heatmapType);
        plugin.worldHeatmapPluginExecutor.execute(() -> HeatmapNew.writeHeatmapsToFile(plugin.getEnabledHeatmaps(), heatmapFile, null));
        // Write the specified heatmap image
        HeatmapNew heatmap = plugin.heatmaps.get(heatmapType);
        plugin.worldHeatmapPluginExecutor.execute(() -> HeatmapImage.writeHeatmapImage(heatmap, imageFile, isFullMapImage, plugin.config.heatmapAlpha(), plugin.config.heatmapSensitivity(), plugin.config.speedMemoryTradeoff(), new WorldHeatmapPlugin.HeatmapProgressListener(plugin, heatmapType)));
    }

    private void clearHeatmap(HeatmapNew.HeatmapType heatmapType) {
        // Replace the heatmap with a new one
        plugin.heatmaps.put(heatmapType, new HeatmapNew(heatmapType, plugin.mostRecentLocalUserID, plugin.mostRecentAccountType));
        List<HeatmapNew> heatmap = List.of(plugin.heatmaps.get(heatmapType));

        // Write new .heatmaps data file, so the current (now old) one can be kept as a backup
        File latestHeatmapFile = HeatmapFile.getLatestHeatmapFile(mostRecentLocalUserID);
        File newHeatmapFile = HeatmapFile.getCurrentHeatmapFile(mostRecentLocalUserID);
        plugin.worldHeatmapPluginExecutor.execute(() -> HeatmapNew.writeHeatmapsToFile(heatmap, newHeatmapFile, latestHeatmapFile));
    }

    private void openHeatmapsFolder() throws IOException {
        if (!plugin.WORLD_HEATMAP_DIR.exists()) {
            if (!plugin.WORLD_HEATMAP_DIR.mkdirs()) {
                log.error("Error: was not able to create worldheatmap folder");
            }
        }
        Desktop.getDesktop().open(plugin.WORLD_HEATMAP_DIR);
    }

    void setEnabledHeatmapButtons(boolean onOff) {
        // Disable write heatmap image buttons
        for (JButton writeButton : writeHeatmapImageButtons.values()) {
            writeButton.setEnabled(onOff);
        }
        // Disable clear heatmap buttons
        for (JButton clearButton : clearHeatmapButtons.values()) {
            clearButton.setEnabled(onOff);
        }
    }

}
