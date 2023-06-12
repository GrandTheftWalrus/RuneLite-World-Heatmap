package com.worldheatmap;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.ImageUtil;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Map;

@Slf4j
public class WorldHeatmapPanel extends PluginPanel
{

	private final WorldHeatmapPlugin plugin;
	private JLabel typeACountLabel, typeBCountLabel, playerIDLabel;
	protected JButton writeTypeAHeatmapImageButton, writeTypeBHeatmapImageButton, clearTypeAHeatmapButton, clearTypeBHeatmapButton, writeTypeBcsvButton, writeTypeAcsvButton, combinerToolButton;
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
			catch (IOException heatmapsIOError)
			{
				heatmapsIOError.printStackTrace();
			}
		});
		mainPanel.add(openHeatmapFolderButton);
		add(mainPanel);

		//Type A Panel
		JPanel typeAPanel = new JPanel(new GridLayout(0, 1, hGap, vGap));
		typeAPanel.setBorder(new EmptyBorder(vGap, hGap, vGap, hGap));
		typeAPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		//Type A label
		JLabel typeALabel = new JLabel("Heatmap Type A");
		typeALabel.setFont(sectionLabelFont);
		typeALabel.setForeground(Color.WHITE);
		typeALabel.setHorizontalAlignment(SwingConstants.CENTER);
		typeAPanel.add(typeALabel);

		//'Write Heatmap Image' button for Type A
		writeTypeAHeatmapImageButton = new JButton("Write Heatmap Image");
		writeTypeAHeatmapImageButton.setFont(buttonFont);
		writeTypeAHeatmapImageButton.addActionListener(e -> writeTypeAHeatmapImage());
		typeAPanel.add(writeTypeAHeatmapImageButton);

		//'Restart Heatmap' button for Type A
		clearTypeAHeatmapButton = new JButton("Restart Heatmap");
		clearTypeAHeatmapButton.setFont(buttonFont);
		clearTypeAHeatmapButton.addActionListener(e -> {
			final int result = JOptionPane.showOptionDialog(typeAPanel,
				"<html>Art thou sure you want to restart your Type A heatmap? Both the file and .PNG image will be restarted.</html>",
				"Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
				null, new String[]{"Yes", "No"}, "No");

			if (result == JOptionPane.YES_OPTION)
			{
				clearTypeAHeatmap();
			}
		});
		typeAPanel.add(clearTypeAHeatmapButton);
		typeACountLabel = new JLabel("Step count: 0");
		typeACountLabel.setHorizontalAlignment(SwingConstants.CENTER);
		typeAPanel.add(typeACountLabel);

		//The "Write CSV" button for Type A
		writeTypeAcsvButton = new JButton("Write CSV File");
		writeTypeAcsvButton.setFont(buttonFont);
		writeTypeAcsvButton.addActionListener(e -> showCSVSavingDialog(typeAPanel, plugin.heatmapTypeA));
		typeAPanel.add(writeTypeAcsvButton);
		add(typeAPanel);

		//Type B Panel
		JPanel typeBPanel = new JPanel(new GridLayout(0, 1, hGap, vGap));
		typeBPanel.setBorder(new EmptyBorder(vGap, hGap, vGap, hGap));
		typeBPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		//Type B label
		JLabel typeBLabel = new JLabel("Heatmap Type B");
		typeBLabel.setForeground(Color.WHITE);
		typeBLabel.setFont(sectionLabelFont);
		typeBLabel.setHorizontalAlignment(SwingConstants.CENTER);
		typeBPanel.add(typeBLabel);

		//'Write Heatmap Image' button for Type B
		writeTypeBHeatmapImageButton = new JButton("Write Heatmap Image");
		writeTypeBHeatmapImageButton.setFont(buttonFont);
		writeTypeBHeatmapImageButton.addActionListener(e -> writeTypeBHeatmapImage());
		typeBPanel.add(writeTypeBHeatmapImageButton);

		//'Restart Heatmap' button for Type B
		clearTypeBHeatmapButton = new JButton("Restart Heatmap");
		clearTypeBHeatmapButton.setFont(buttonFont);
		clearTypeBHeatmapButton.addActionListener(e -> {
			final int result = JOptionPane.showOptionDialog(typeBPanel,
				"<html>Art thou sure you want to restart your Type B heatmap? Both the file and .PNG image will be restarted.</html>",
				"Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
				null, new String[]{"Yes", "No"}, "No");

			if (result == JOptionPane.YES_OPTION)
			{
				clearTypeBHeatmap();
			}
		});
		typeBPanel.add(clearTypeBHeatmapButton);
		typeBCountLabel = new JLabel("Step count: 0");
		typeBCountLabel.setHorizontalAlignment(SwingConstants.CENTER);
		typeBPanel.add(typeBCountLabel);
		add(typeBPanel);

		//The "Write CSV" button for Type B
		writeTypeBcsvButton = new JButton("Write CSV File");
		writeTypeBcsvButton.setFont(buttonFont);
		writeTypeBcsvButton.addActionListener(e -> showCSVSavingDialog(typeBPanel, plugin.heatmapTypeB));
		typeBPanel.add(writeTypeBcsvButton);

		//Heatmap combiner panel
		JPanel combinerToolPanel = new JPanel(new GridLayout(0, 1, hGap, vGap));
		combinerToolPanel.setBorder(new EmptyBorder(vGap, hGap, vGap, hGap));
		combinerToolPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		//Heatmap combiner label
		JLabel combinerToolLabel = new JLabel("Heatmap Combiner Tool");
		combinerToolLabel.setFont(sectionLabelFont);
		combinerToolLabel.setForeground(Color.WHITE);
		combinerToolLabel.setHorizontalAlignment(SwingConstants.CENTER);
		combinerToolPanel.add(combinerToolLabel);

		//Heatmap combiner button
		JDialog combinerJDialog = createHeatmapCombinerDialog();
		combinerToolButton = new JButton("Heatmap Combiner");
		combinerToolButton.setFont(buttonFont);
		combinerToolButton.addActionListener(e -> combinerJDialog.setVisible(true));
		combinerToolPanel.add(combinerToolButton);

		add(combinerToolPanel);
	}

	public JDialog createHeatmapCombinerDialog()
	{
		//Heatmap combiner JDialog
		Window parentWindow = SwingUtilities.getWindowAncestor(this);
		JDialog combinerJDialog = new JDialog(parentWindow, "Heatmap Combiner", Dialog.ModalityType.APPLICATION_MODAL);
		combinerJDialog.setIconImage(ImageUtil.loadImageResource(getClass(), "/WorldHeatmap.png"));
		JPanel combinerJPanel = new JPanel();
		combinerJPanel.setLayout(new BoxLayout(combinerJPanel, BoxLayout.Y_AXIS));
		combinerJPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		//Inputs file chooser
		JPanel inputsPanel = new JPanel();
		JFileChooser inputsJFC = new JFileChooser();
		inputsJFC.addChoosableFileFilter(new FileNameExtensionFilter("Heatmap files", "heatmap"));
		inputsJFC.setAcceptAllFileFilterUsed(false);
		inputsJFC.setMultiSelectionEnabled(true);
		inputsJFC.setSelectedFile(new File(plugin.HEATMAP_FILES_DIR, "ballsack.heatmap"));
		inputsJFC.setDialogTitle("Choose the .heatmap files whomst you want to combine");

		//Inputs label
		JLabel inputsJFCLabel = new JLabel("Input heatmap files");
		JTextField inputsJFCTextfield = new JTextField("", 30);
		inputsJFCTextfield.setEnabled(false);
		Button inputsJFCButton = new Button("Choose file");
		inputsPanel.add(inputsJFCTextfield);
		inputsPanel.add(inputsJFCButton);
		inputsJFCButton.addActionListener(e -> {
			int retVal = inputsJFC.showDialog(this, "Select");
			if (retVal == JFileChooser.APPROVE_OPTION)
			{
				StringBuilder text = new StringBuilder();
				text.append('"');
				for (File f : inputsJFC.getSelectedFiles())
				{
					text.append(f.getAbsolutePath());
					text.append("\" \"");
				}
				text.delete(text.length() - 2, text.length());
				inputsJFCTextfield.setText(text.toString());
			}
		});
		combinerJPanel.add(inputsJFCLabel);
		combinerJPanel.add(inputsPanel);

		//Output file chooser
		JPanel outputPanel = new JPanel();
		JFileChooser outputJFC = new JFileChooser();
		outputJFC.addChoosableFileFilter(new FileNameExtensionFilter("Heatmap files", "heatmap"));
		outputJFC.setAcceptAllFileFilterUsed(false);
		outputJFC.setSelectedFile(new File(plugin.HEATMAP_FILES_DIR, "output.heatmap"));
		outputJFC.setDialogTitle("Enter a name for the combined .heatmap");

		JLabel outputJFCLabel = new JLabel("Output heatmap file");
		JTextField outputJFCTextfield = new JTextField("", 30);
		Button outputsJFCButton = new Button("Choose file");
		outputPanel.add(outputJFCTextfield);
		outputPanel.add(outputsJFCButton);
		outputsJFCButton.addActionListener(e -> {
			int retVal = outputJFC.showDialog(this, "Select");
			if (retVal == JFileChooser.APPROVE_OPTION)
			{
				outputJFCTextfield.setText(outputJFC.getSelectedFile().toString());
			}
		});
		combinerJPanel.add(outputJFCLabel);
		combinerJPanel.add(outputPanel);

		//Output image chooser
		JPanel outputImagePanel = new JPanel();
		JFileChooser outputImageJFC = new JFileChooser();
		outputImageJFC.addChoosableFileFilter(new FileNameExtensionFilter("PNG files", "png"));
		outputImageJFC.setAcceptAllFileFilterUsed(false);
		outputImageJFC.setSelectedFile(new File(plugin.HEATMAP_IMAGE_DIR, "output.png"));
		outputImageJFC.setDialogTitle("Enter a name for the combined heatmap .PNG");

		//Output image label
		JLabel outputImageJFCLabel = new JLabel("Output image file (optional)");
		JTextField outputImageJFCTextfield = new JTextField("", 30);
		Button outputImageJFCButton = new Button("Choose file");
		outputImagePanel.add(outputImageJFCTextfield);
		outputImagePanel.add(outputImageJFCButton);
		outputImageJFCButton.addActionListener(e -> {
			int retVal = outputImageJFC.showDialog(this, "Select");
			if (retVal == JFileChooser.APPROVE_OPTION)
			{
				outputImageJFCTextfield.setText(outputImageJFC.getSelectedFile().toString());
			}
		});
		combinerJPanel.add(outputImageJFCLabel);
		combinerJPanel.add(outputImagePanel);

		//Combiner Submit Button
		JButton combinerSubmitButton = new JButton("Combine Heatmaps");
		combinerSubmitButton.addActionListener(e -> {
			File[] filesToOpen = inputsJFC.getSelectedFiles();
			File fileToSave = new File(outputJFCTextfield.getText());
			File imageFileOut;
			if (outputImageJFCTextfield.getText().isEmpty())
			{
				imageFileOut = null;
			}
			else
			{
				imageFileOut = new File(outputImageJFCTextfield.getText());
			}

			if (filesToOpen.length == 0 || fileToSave.getName().isEmpty())
			{
				JOptionPane.showMessageDialog(combinerJDialog, "You either didn't select an output file or any input files", "Le error", JOptionPane.ERROR_MESSAGE);
				return;
			}
			if (!fileToSave.getName().endsWith(".heatmap"))
			{
				fileToSave = new File(fileToSave.getAbsolutePath() + ".heatmap");
			}

			File finalFileToSave = fileToSave;
			SwingWorker<Boolean, Object> swingWorker = new SwingWorker<>()
			{
				@Override
				protected Boolean doInBackground()
				{
					combinerJDialog.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
					combinerSubmitButton.setText("Loading...");
					combinerSubmitButton.setEnabled(false);
					return combineHeatmaps(finalFileToSave, imageFileOut, filesToOpen);
				}

				@Override
				protected void done()
				{
					combinerSubmitButton.setText("Submit");
					combinerSubmitButton.setEnabled(true);
					combinerJDialog.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
					try
					{
						if (get())
						{
							JOptionPane.showMessageDialog(combinerJDialog, "The heatmaps have been combined and are wherever you stashed them", "Success", JOptionPane.PLAIN_MESSAGE);
						}
						else
						{
							JOptionPane.showMessageDialog(combinerJDialog, "There was an error combining the heatmaps for some reason", "Error", JOptionPane.ERROR_MESSAGE);
						}
					}
					catch (Exception e)
					{
						//ignore
					}
				}
			};
			swingWorker.execute();
		});
		combinerJPanel.add(combinerSubmitButton);

		combinerJDialog.add(combinerJPanel);
		combinerJDialog.pack();
		combinerJDialog.setLocationRelativeTo(null);

		return combinerJDialog;
	}

	protected void showCSVSavingDialog(Component typeXPanel, HeatmapNew heatmap)
	{
		JFileChooser jfc = new JFileChooser();
		jfc.addChoosableFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
		jfc.setAcceptAllFileFilterUsed(false);
		jfc.setSelectedFile(new File(jfc.getCurrentDirectory().getAbsolutePath(), "ballsack.csv"));
		jfc.setDialogTitle("Specify where to save CSV");
		int userSelection = jfc.showSaveDialog(typeXPanel);
		if (userSelection == JFileChooser.APPROVE_OPTION)
		{
			File fileToSave = jfc.getSelectedFile();
			fileToSave = (fileToSave.getName().endsWith(".csv") ? fileToSave : new File(fileToSave.getAbsolutePath() + ".csv"));
			log.debug("File to save as CSV: " + fileToSave);
			try
			{
				writeCSVFile(fileToSave, heatmap);
			}
			catch (IOException ex)
			{
				ex.printStackTrace();
			}
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

		if (plugin.heatmapTypeA != null)
		{
			typeACountLabel.setText("Step count: " + plugin.heatmapTypeA.getStepCount());
		}
		else
		{
			typeACountLabel.setText("");
		}

		if (plugin.heatmapTypeB != null)
		{
			typeBCountLabel.setText("Step count: " + plugin.heatmapTypeB.getStepCount());
		}
		else
		{
			typeBCountLabel.setText("");
		}
		updateUI();
	}

	private void writeTypeAHeatmapImage()
	{
		plugin.executor.execute(() -> plugin.writeHeatmapFile(plugin.heatmapTypeA, new File(plugin.mostRecentLocalUserID + "_TypeA.heatmap")));
		plugin.executor.execute(() -> plugin.writeHeatmapImage(plugin.heatmapTypeA, new File(plugin.mostRecentLocalUserID + "_TypeA.png")));
	}

	private void clearTypeAHeatmap()
	{
		plugin.heatmapTypeA = new HeatmapNew();
		String filepathA = Paths.get(plugin.HEATMAP_FILES_DIR.toString(), plugin.mostRecentLocalUserID + "_TypeA.heatmap").toString();
		plugin.executor.execute(() -> plugin.writeHeatmapFile(plugin.heatmapTypeA, new File(filepathA)));
		plugin.executor.execute(() -> plugin.writeHeatmapImage(plugin.heatmapTypeA, new File(plugin.mostRecentLocalUserID + "_TypeA.png")));
	}

	private void writeTypeBHeatmapImage()
	{
		plugin.executor.execute(() -> plugin.writeHeatmapFile(plugin.heatmapTypeB, new File(plugin.mostRecentLocalUserID + "_TypeB.heatmap")));
		plugin.executor.execute(() -> plugin.writeHeatmapImage(plugin.heatmapTypeB, new File(plugin.mostRecentLocalUserID + "_TypeB.png")));
	}

	private void clearTypeBHeatmap()
	{
		plugin.heatmapTypeB = new HeatmapNew();
		String filepathB = Paths.get(plugin.HEATMAP_FILES_DIR.toString(), plugin.mostRecentLocalUserID + "_TypeB.heatmap").toString();
		plugin.executor.execute(() -> plugin.writeHeatmapFile(plugin.heatmapTypeB, new File(filepathB)));
		plugin.executor.execute(() -> plugin.writeHeatmapImage(plugin.heatmapTypeB, new File(plugin.mostRecentLocalUserID + "_TypeB.png")));
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

	private void writeCSVFile(File csvURI, HeatmapNew heatmap) throws IOException
	{
		PrintWriter pw = new PrintWriter(csvURI);
		for (Map.Entry<Point, Integer> e : heatmap.getEntrySet())
		{
			int x = e.getKey().x;
			int y = e.getKey().y;
			int stepVal = e.getValue();
			pw.write("" + x + ", " + y + ", " + stepVal + "\n");
		}
		pw.close();
	}

	private boolean combineHeatmaps(File fileToSave, File imageFileOut, File[] filesToOpen)
	{
		log.info("Combining " + filesToOpen.length + " heatmap files ...");
		long startTime = System.nanoTime();
		boolean isSuccessful = plugin.combineHeatmaps(fileToSave, imageFileOut, filesToOpen);
		if (isSuccessful)
		{
			long endTime = System.nanoTime();
			log.info("Finished combining " + filesToOpen.length + " heatmap files in " + (endTime - startTime) / 1_000_000 + " ms");
		}
		return isSuccessful;
	}
}
