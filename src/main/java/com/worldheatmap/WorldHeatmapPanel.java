package com.worldheatmap;

import net.runelite.api.Actor;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Map;

public class WorldHeatmapPanel extends PluginPanel{

    private final WorldHeatmapPlugin plugin;
    private JLabel typeACountLabel, typeBCountLabel;
    protected JButton writeTypeAHeatmapImageButton, writeTypeBHeatmapImageButton, clearTypeAHeatmapButton, clearTypeBHeatmapButton, writeTypeBcsvButton, writeTypeAcsvButton;

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
        openHeatmapFolderButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
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
        typeAPanel.setBorder(new EmptyBorder(8, 0, 108, 0));
        typeAPanel.add(new JLabel("Heatmap Type A"));

        //'Write Heatmap Image' button for Type A
        writeTypeAHeatmapImageButton = new JButton("Write Heatmap Image");
        writeTypeAHeatmapImageButton.setFont(new Font(writeTypeAHeatmapImageButton.getFont().getName(), Font.BOLD, 18));
        writeTypeAHeatmapImageButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                writeTypeAHeatmapImage();
            }
        });
        typeAPanel.add(writeTypeAHeatmapImageButton);

        //'Restart Heatmap' button for Type A
        clearTypeAHeatmapButton = new JButton("Restart Heatmap");
        clearTypeAHeatmapButton.setFont(new Font(openHeatmapFolderButton.getFont().getName(), Font.BOLD, 18));
        clearTypeAHeatmapButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                final int result = JOptionPane.showOptionDialog(typeAPanel,
                        "<html>Art thou sure you want to restart your Type A heatmap? Both the file and .PNG image will be restarted.</html>",
                        "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, new String[]{"Yes", "No"}, "No");

                if (result == JOptionPane.YES_OPTION)
                    clearTypeAHeatmap();
            }
        });
        typeAPanel.add(clearTypeAHeatmapButton);
        typeACountLabel = new JLabel();
        typeAPanel.add(typeACountLabel);
        add(typeAPanel);

        //The "Write CSV" button for Type A
        writeTypeAcsvButton = new JButton("Write CSV File");
        writeTypeAcsvButton.setFont(new Font(openHeatmapFolderButton.getFont().getName(), Font.BOLD, 18));
        writeTypeAcsvButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser jfs = new JFileChooser();
                jfs.setDialogTitle("Specify where to save CSV");
                int userSelection = jfs.showSaveDialog(typeAPanel);
                if (userSelection == JFileChooser.APPROVE_OPTION) {
                    File fileToSave = jfs.getSelectedFile();
                    try {
                        writeCSVFile(fileToSave,  plugin.heatmapTypeB);
                    }
                    catch(IOException ex){
                        ex.printStackTrace();
                    }
                }
            }
        });
        typeAPanel.add(writeTypeAcsvButton);

        //Panel for Type B heatmaps
        JPanel typeBPanel = new JPanel();
        typeBPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        typeBPanel.setBorder(new EmptyBorder(8, 0, 108, 0));
        typeBPanel.add(new JLabel("Heatmap Type B"));

        //'Write Heatmap Image' button for Type B
        writeTypeBHeatmapImageButton = new JButton("Write Heatmap Image");
        writeTypeBHeatmapImageButton.setFont(new Font(writeTypeBHeatmapImageButton.getFont().getName(), Font.BOLD, 18));
        writeTypeBHeatmapImageButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                writeTypeBHeatmapImage();
            }
        });
        typeBPanel.add(writeTypeBHeatmapImageButton);

        //'Restart Heatmap' button for Type B
        clearTypeBHeatmapButton = new JButton("Restart Heatmap");
        clearTypeBHeatmapButton.setFont(new Font(openHeatmapFolderButton.getFont().getName(), Font.BOLD, 18));
        clearTypeBHeatmapButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                final int result = JOptionPane.showOptionDialog(typeBPanel,
                        "<html>Art thou sure you want to restart your Type B heatmap? Both the file and .PNG image will be restarted.</html>",
                        "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, new String[]{"Yes", "No"}, "No");

                if (result == JOptionPane.YES_OPTION)
                    clearTypeBHeatmap();
            }
        });
        typeBPanel.add(clearTypeBHeatmapButton);
        typeBCountLabel = new JLabel();
        typeBPanel.add(typeBCountLabel);
        add(typeBPanel);

        //The "Write CSV" button for Type B
        writeTypeBcsvButton = new JButton("Write CSV File");
        writeTypeBcsvButton.setFont(new Font(openHeatmapFolderButton.getFont().getName(), Font.BOLD, 18));
        writeTypeBcsvButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser jfs = new JFileChooser();
                jfs.setDialogTitle("Specify where to save CSV");
                int userSelection = jfs.showSaveDialog(typeBPanel);
                if (userSelection == JFileChooser.APPROVE_OPTION) {
                    File fileToSave = jfs.getSelectedFile();
                    try {
                        writeCSVFile(fileToSave,  plugin.heatmapTypeB);
                    }
                    catch(IOException ex){
                        ex.printStackTrace();
                    }
                }
            }
        });
        typeBPanel.add(writeTypeBcsvButton);
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
        plugin.executor.execute(() -> plugin.writeHeatmapFile(plugin.heatmapTypeA, plugin.mostRecentLocalUserName + "_TypeA.heatmap"));
        plugin.executor.execute(() -> plugin.writeHeatmapImage(plugin.heatmapTypeA, plugin.mostRecentLocalUserName + "_TypeA.png"));
    }

    private void clearTypeAHeatmap() {
        plugin.heatmapTypeA = new HeatmapNew();
        String filepathA = Paths.get(plugin.HEATMAP_FILES_DIR, plugin.mostRecentLocalUserName + "_TypeA.heatmap").toString();
        plugin.executor.execute(() -> plugin.writeHeatmapFile(plugin.heatmapTypeA, filepathA));
        plugin.executor.execute(() -> plugin.writeHeatmapImage(plugin.heatmapTypeA, plugin.mostRecentLocalUserName + "_TypeA.png"));
    }

    private void writeTypeBHeatmapImage(){
        plugin.executor.execute(() -> plugin.writeHeatmapFile(plugin.heatmapTypeB, plugin.mostRecentLocalUserName + "_TypeB.heatmap"));
        plugin.executor.execute(() -> plugin.writeHeatmapImage(plugin.heatmapTypeB, plugin.mostRecentLocalUserName + "_TypeB.png"));
    }

    private void clearTypeBHeatmap() {
        plugin.heatmapTypeB = new HeatmapNew();
        String filepathB = Paths.get(plugin.HEATMAP_FILES_DIR, plugin.mostRecentLocalUserName + "_TypeB.heatmap").toString();
        plugin.executor.execute(() -> plugin.writeHeatmapFile(plugin.heatmapTypeB, filepathB));
        plugin.executor.execute(() -> plugin.writeHeatmapImage(plugin.heatmapTypeB, plugin.mostRecentLocalUserName + "_TypeB.png"));
    }

    private void openHeatmapsFolder() throws IOException {
        if (!plugin.WORLDHEATMAP_DIR.exists())
            plugin.WORLDHEATMAP_DIR.mkdirs();
        Desktop.getDesktop().open(plugin.WORLDHEATMAP_DIR);
    }

    private void writeCSVFile(File csvURI, HeatmapNew heatmap) throws IOException{
        PrintWriter pw = new PrintWriter(csvURI);
        for (Map.Entry<Point, Integer> e : heatmap.getEntrySet()){
            int x = e.getKey().x;
            int y = e.getKey().y;
            int stepVal = e.getValue();
            pw.write("" + x + ", " + y + ", " + stepVal + "\n");
        }
        pw.close();
    }
}
