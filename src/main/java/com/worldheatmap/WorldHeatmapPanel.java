package com.worldheatmap;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;

public class WorldHeatmapPanel extends PluginPanel{

    private final WorldHeatmapPlugin plugin;

    public WorldHeatmapPanel(WorldHeatmapPlugin plugin) {
        this.plugin = plugin;
        rebuild();
    }

    protected void rebuild(){
        removeAll();
        JPanel mainPanel = new JPanel();
        mainPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        mainPanel.setBorder(new EmptyBorder(8, 0, 72, 0));

        JButton writeHeatmapImageButton = new JButton("Write Heatmap Image");
        writeHeatmapImageButton.setFont(new Font(writeHeatmapImageButton.getFont().getName(), Font.BOLD, 18));
        writeHeatmapImageButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                writeHeatmapImage();
            }
        });
        mainPanel.add(writeHeatmapImageButton);

        JButton openHeatmapFolderButton = new JButton("Open Heatmap Folder");
        openHeatmapFolderButton.setFont(new Font(openHeatmapFolderButton.getFont().getName(), Font.BOLD, 18));
        openHeatmapFolderButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                try{
                    openHeatmapsFolder();
                }
                catch(IOException heatmapsIOError){
                    heatmapsIOError.printStackTrace();
                }
            }
        });
        mainPanel.add(openHeatmapFolderButton);

        JButton clearHeatmapButton = new JButton("Restart Heatmap");
        clearHeatmapButton.setFont(new Font(openHeatmapFolderButton.getFont().getName(), Font.BOLD, 18));
        clearHeatmapButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                clearHeatmap();
            }
        });
        mainPanel.add(clearHeatmapButton);

        add(mainPanel);
    }

    private void writeHeatmapImage(){
        plugin.executor.execute(plugin.WRITE_IMAGE_FILE);
    }

    private void openHeatmapsFolder() throws IOException {
        Desktop.getDesktop().open(new File(plugin.HEATMAP_IMAGE_PATH));
    }

    private void clearHeatmap() {
        plugin.executor.execute(plugin.CLEAR_HEATMAP);
    }
}
