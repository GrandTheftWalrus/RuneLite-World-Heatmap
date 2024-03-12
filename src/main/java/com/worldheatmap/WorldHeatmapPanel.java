package com.worldheatmap;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;

@Slf4j
public class WorldHeatmapPanel extends PluginPanel
{

	private final WorldHeatmapPlugin plugin;
	private JLabel playerIDLabel;
	Map<HeatmapNew.HeatmapType, JLabel> heatmapTotalValueLabels = new HashMap<>();
	Map<HeatmapNew.HeatmapType, JLabel> heatmapMemoryUsageLabels = new HashMap<>();
	Map<HeatmapNew.HeatmapType, JButton> writeHeatmapImageButtons = new HashMap<>();
	Map<HeatmapNew.HeatmapType, JButton> clearHeatmapButtons = new HashMap<>();
	protected long mostRecentLocalUserID;

	public WorldHeatmapPanel(WorldHeatmapPlugin plugin)
	{
		this.plugin = plugin;
		rebuild();
	}

	protected void rebuild()
	{
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
		playerIDLabel = new JLabel("Player ID: unavailable");
		playerIDLabel.setHorizontalAlignment(SwingConstants.CENTER);
		mainPanel.add(playerIDLabel);

		//'Open Heatmaps Folder' button
		JButton openHeatmapFolderButton = new JButton("Open Heatmaps Folder");
		openHeatmapFolderButton.setFont(buttonFont);
		openHeatmapFolderButton.addActionListener(e -> {
			try
			{
				openHeatmapsFolder();
			}
			catch (IOException exception)
			{
				log.error("Error: Exception thrown whilst opening worldheatmap folder: " + exception.getMessage());
			}
		});
		mainPanel.add(openHeatmapFolderButton);
		add(mainPanel);

		// Create the panels/buttons for each loaded Heatmap type
		for (HeatmapNew.HeatmapType heatmapType : HeatmapNew.HeatmapType.values()){
			// Do not create panels for disabled heatmaps
			if (!plugin.isHeatmapEnabled(heatmapType)){
				continue;
			}
			//Panel
			JPanel heatmapPanel = new JPanel(new GridLayout(0, 1, hGap, vGap));
			heatmapPanel.setBorder(new EmptyBorder(vGap, hGap, vGap, hGap));
			heatmapPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			//Label
			JLabel heatmapLabel = new JLabel(heatmapType.toString());
			heatmapLabel.setFont(sectionLabelFont);
			heatmapLabel.setForeground(Color.WHITE);
			heatmapLabel.setHorizontalAlignment(SwingConstants.CENTER);
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

				if (result == JOptionPane.YES_OPTION)
				{
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

			// Estimated memory usage label
			JLabel heatmapMemoryUsageLabel = new JLabel("Mem: 0");
			heatmapMemoryUsageLabel.setToolTipText("Estimated memory usage of the heatmap");
			heatmapMemoryUsageLabel.setHorizontalAlignment(SwingConstants.CENTER);
			heatmapMemoryUsageLabels.put(heatmapType, heatmapMemoryUsageLabel);
			heatmapPanel.add(heatmapMemoryUsageLabel);

			add(heatmapPanel);
		}
	}

	protected void updatePlayerID()
	{
		this.mostRecentLocalUserID = plugin.mostRecentLocalUserID;
		if (this.mostRecentLocalUserID == -1 || this.mostRecentLocalUserID == 0)
		{
			playerIDLabel = new JLabel("Player ID: unavailable");
		}
		else
		{
			playerIDLabel.setText("Player ID: " + this.mostRecentLocalUserID);
		}
		updateUI();
	}

	protected void updateCounts()
	{
		for (HeatmapNew.HeatmapType heatmapType : plugin.heatmaps.keySet())
		{
			if (heatmapTotalValueLabels.get(heatmapType) != null){
				heatmapTotalValueLabels.get(heatmapType).setText("Total value: " + plugin.heatmaps.get(heatmapType).getTotalValue());
			}
		}
		updateUI();
	}

	protected void updateMemoryUsages()
	{
		for (HeatmapNew.HeatmapType heatmapType : plugin.heatmaps.keySet())
		{
			if (heatmapMemoryUsageLabels.get(heatmapType) != null){
				heatmapMemoryUsageLabels.get(heatmapType).setText(String.format("Mem: %.2fMB", (float) HeatmapNew.estimateSize(plugin.heatmaps.get(heatmapType)) / 1024 / 1024));
			}
		}
		updateUI();
	}

	private void writeHeatmapImage(HeatmapNew.HeatmapType heatmapType, boolean isFullMapImage)
	{
		// Save all heatmap data
		plugin.worldHeatmapPluginExecutor.execute(() -> plugin.writeHeatmapsToFile(plugin.heatmaps.values(), new File(plugin.mostRecentLocalUserID + ".heatmaps")));
		// Write the specified heatmap image
		plugin.worldHeatmapPluginExecutor.execute(() -> plugin.writeHeatmapImage(plugin.heatmaps.get(heatmapType), new File(plugin.mostRecentLocalUserID + "_" + heatmapType.toString() + ".tif"), isFullMapImage));
	}

	private void clearHeatmap(HeatmapNew.HeatmapType heatmapType)
	{
		// Replace the heatmap with a new one
		plugin.heatmaps.put(heatmapType, new HeatmapNew(heatmapType, plugin.mostRecentLocalUserID));
		// Rewrite the heatmap data file
		plugin.worldHeatmapPluginExecutor.execute(() -> plugin.writeHeatmapsToFile(plugin.heatmaps.values(), new File(plugin.mostRecentLocalUserID + ".heatmaps")));
	}

	private void openHeatmapsFolder() throws IOException
	{
		if (!plugin.WORLDHEATMAP_DIR.exists())
		{
			if (!plugin.WORLDHEATMAP_DIR.mkdirs())
			{
				log.error("Error: was not able to create worldheatmap folder");
			}
		}
		Desktop.getDesktop().open(plugin.WORLDHEATMAP_DIR);
	}

	void setEnabledHeatmapButtons(boolean onOff){
		// Disable write heatmap image buttons
		for (JButton writeButton : writeHeatmapImageButtons.values())
		{
			writeButton.setEnabled(onOff);
		}
		// Disable clear heatmap buttons
		for (JButton clearButton : clearHeatmapButtons.values())
		{
			clearButton.setEnabled(onOff);
		}
	}

}
