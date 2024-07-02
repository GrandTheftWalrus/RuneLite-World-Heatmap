package com.worldheatmap;

import java.awt.*;
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

    private JLabel memoryUsageLabel;

    Map<HeatmapNew.HeatmapType, JPanel> heatmapPanels = new HashMap<>();
    Map<HeatmapNew.HeatmapType, JLabel> heatmapTotalValueLabels = new HashMap<>();
    Map<HeatmapNew.HeatmapType, JLabel> heatmapPanelLabels = new HashMap<>();
    Map<HeatmapNew.HeatmapType, JButton> writeHeatmapImageButtons = new HashMap<>();
    Map<HeatmapNew.HeatmapType, JButton> clearHeatmapButtons = new HashMap<>();
    protected long mostRecentLocalUserID;

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
        memoryUsageLabel = new JLabel("Estimated Memory Usage: " + String.format("%.2f", estimatedMemoryUsage / 1024. / 1024) +  "MB");
        memoryUsageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        mainPanel.add(memoryUsageLabel);

        //'Open Heatmaps Folder' button
        JButton openHeatmapFolderButton = new JButton("Open Heatmaps Folder");
        openHeatmapFolderButton.setFont(buttonFont);
        openHeatmapFolderButton.addActionListener(e -> {
            try {
                openHeatmapsFolder();
            } catch (IOException exception) {
                log.error("Error: Exception thrown whilst opening worldheatmap folder: " + exception.getMessage());
            }
        });
        mainPanel.add(openHeatmapFolderButton);
        add(mainPanel);

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

    protected void updateMemoryUsages() {
        int estimatedTotalMemoryUsage = 0;
        for (HeatmapNew heatmap : plugin.heatmaps.values()) {
            estimatedTotalMemoryUsage += HeatmapNew.estimateSize(heatmap);
            memoryUsageLabel.setText("Estimated Memory Usage: " + String.format("%.2f", estimatedTotalMemoryUsage / 1024. / 1024) +  "MB");
            if (heatmapPanelLabels.get(heatmap.getHeatmapType()) != null) {
                heatmapPanelLabels.get(heatmap.getHeatmapType()).setToolTipText("Estimated memory usage: " + String.format("%.2f", HeatmapNew.estimateSize(heatmap) / 1024. / 1024) + "MB");
            }
        }
        updateUI();
    }

    private void writeHeatmapImage(HeatmapNew.HeatmapType heatmapType, boolean isFullMapImage) {
        // Save all heatmap data
        File heatmapFile = HeatmapFile.getLatestHeatmapFile(mostRecentLocalUserID);
        File imageFile = HeatmapFile.getCurrentImageFile(mostRecentLocalUserID, heatmapType);
        plugin.worldHeatmapPluginExecutor.execute(() -> HeatmapNew.writeHeatmapsToFile(plugin.getEnabledHeatmaps(), heatmapFile, null));
        // Write the specified heatmap image
        HeatmapNew heatmap = plugin.heatmaps.get(heatmapType);
        plugin.worldHeatmapPluginExecutor.execute(() -> HeatmapImage.writeHeatmapImage(heatmap, imageFile, isFullMapImage, plugin.config.heatmapAlpha(), plugin.config.heatmapSensitivity(), plugin.config.speedMemoryTradeoff(), new WorldHeatmapPlugin.HeatmapProgressListener(plugin, heatmapType)));
    }

    private void clearHeatmap(HeatmapNew.HeatmapType heatmapType) {
        // Replace the heatmap with a new one
        plugin.heatmaps.put(heatmapType, new HeatmapNew(heatmapType, plugin.mostRecentLocalUserID));
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
