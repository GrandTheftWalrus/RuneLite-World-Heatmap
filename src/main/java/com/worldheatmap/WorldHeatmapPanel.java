package com.worldheatmap;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;

public class WorldHeatmapPanel extends PluginPanel{

    private final WorldHeatmapPlugin plugin;
    private JLabel typeACountLabel, typeBCountLabel;
    protected JButton writeTypeAHeatmapImageButton, writeTypeBHeatmapImageButton, clearTypeAHeatmapButton, clearTypeBHeatmapButton;

    public WorldHeatmapPanel(WorldHeatmapPlugin plugin) {
        this.plugin = plugin;
        rebuild();
    }

    protected void rebuild(){
        removeAll();
        JPanel mainPanel = new JPanel();
        mainPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        mainPanel.setBorder(new EmptyBorder(8, 0, 10, 0));

        //'Open Heatmaps Folder' button
        JButton openHeatmapFolderButton = new JButton("Open Heatmaps Folder");
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
        add(mainPanel);

        //Panel for Type A heatmap
        JPanel typeAPanel = new JPanel();
        typeAPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        typeAPanel.setBorder(new EmptyBorder(8, 0, 76, 0));
        typeAPanel.add(new JLabel("Heatmap Type A"));

        //'Write Heatmap Image' button for Type A
        writeTypeAHeatmapImageButton = new JButton("Write Heatmap Image");
        writeTypeAHeatmapImageButton.setFont(new Font(writeTypeAHeatmapImageButton.getFont().getName(), Font.BOLD, 18));
        writeTypeAHeatmapImageButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                writeTypeAHeatmapImage();
            }
        });
        typeAPanel.add(writeTypeAHeatmapImageButton);

        //'Restart Heatmap' button for Type A
        clearTypeAHeatmapButton = new JButton("Restart Heatmap");
        clearTypeAHeatmapButton.setFont(new Font(openHeatmapFolderButton.getFont().getName(), Font.BOLD, 18));
        clearTypeAHeatmapButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                clearTypeAHeatmap();
            }
        });
        typeAPanel.add(clearTypeAHeatmapButton);
        typeACountLabel = new JLabel();
        typeAPanel.add(typeACountLabel);
        add(typeAPanel);

        //Panel for Type B heatmaps
        JPanel typeBPanel = new JPanel();
        typeBPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        typeBPanel.setBorder(new EmptyBorder(8, 0, 76, 0));
        typeBPanel.add(new JLabel("Heatmap Type B"));

        //'Write Heatmap Image' button for Type B
        writeTypeBHeatmapImageButton = new JButton("Write Heatmap Image");
        writeTypeBHeatmapImageButton.setFont(new Font(writeTypeBHeatmapImageButton.getFont().getName(), Font.BOLD, 18));
        writeTypeBHeatmapImageButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                writeTypeBHeatmapImage();
            }
        });
        typeBPanel.add(writeTypeBHeatmapImageButton);

        //'Restart Heatmap' button for Type B
        clearTypeBHeatmapButton = new JButton("Restart Heatmap");
        clearTypeBHeatmapButton.setFont(new Font(openHeatmapFolderButton.getFont().getName(), Font.BOLD, 18));
        clearTypeBHeatmapButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                super.mouseClicked(e);
                clearTypeBHeatmap();
            }
        });
        typeBPanel.add(clearTypeBHeatmapButton);
        typeBCountLabel = new JLabel();
        typeBPanel.add(typeBCountLabel);
        add(typeBPanel);
    }

    protected void updateCounts(){

        if (plugin.heatmapTypeA != null)
            typeACountLabel.setText("Step count: " + plugin.heatmapTypeA.getStepCount());
        else
            typeACountLabel.setText("");

        if (plugin.heatmapTypeB != null)
            typeBCountLabel.setText("Step count: " + plugin.heatmapTypeB.getStepCount());
        else
            typeBCountLabel.setText("");
        updateUI();
    }

    private void writeTypeAHeatmapImage(){
        plugin.executor.execute(plugin.WRITE_TYPE_A_IMAGE);
    }

    private void clearTypeAHeatmap() {
        plugin.executor.execute(plugin.CLEAR_TYPE_A_HEATMAP);
    }

    private void writeTypeBHeatmapImage(){
        plugin.executor.execute(plugin.WRITE_TYPE_B_IMAGE);
    }

    private void clearTypeBHeatmap() {
        plugin.executor.execute(plugin.CLEAR_TYPE_B_HEATMAP);
    }

    private void openHeatmapsFolder() throws IOException {
        if (!plugin.WORLDHEATMAP_DIR.exists())
            plugin.WORLDHEATMAP_DIR.mkdirs();
        Desktop.getDesktop().open(plugin.WORLDHEATMAP_DIR);
    }
}
