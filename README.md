# RuneLite World Heatmap plugin
This plugin logs each tile that the local player steps on to create a visualization of where they most often travel, gain xp, get loot, die, take damage, deal damage, teleport to/from, speak, see bob the cat, etc. Users can volunteer to have their anonymous heatmap data uploaded to https://osrsworldheatmap.com/ to help produce a global heatmap of data, so that perhaps we can see which parts of the wilderness are the most dangerous, or where the most secret smoke spots are.

Data can be exported as CSVs for analysis in other programs.

![TYPE_A](https://github.com/user-attachments/assets/7c234858-00ad-4973-8885-8781e5759509)
![TELEPORT_PATHS](https://github.com/user-attachments/assets/80fa3310-fa59-4a48-a20e-e866fd5b97b3)
![NPC_DEATHS](https://github.com/user-attachments/assets/5e1a8fc4-5f93-40f6-b5b9-3a2af11e719a)
![Heatmap Config](https://github.com/user-attachments/assets/50ee358e-08f5-46ea-89b8-939f06d9e048)

Types of trackable data:

- TYPE_A (increments a tile when you walk over it)
- TYPE_B (increments a tile for each tick you're standing on it)
- XP_GAINED
- TELEPORT_PATHS
- TELEPORTED_TO
- TELEPORTED_FROM
- LOOT_VALUE
- PLACES_SPOKEN_AT
- RANDOM_EVENT_SPAWNS
- DEATHS
- NPC_DEATHS
- BOB_THE_CAT_SIGHTING
- DAMAGE_TAKEN
- DAMAGE_GIVEN

The data can be analyzed more fancily with other tools such as R, or Quicken 2003 (not included) by extracting the data as CSV files:
(to do this, copy and rename the .heatmaps file to a .zip and look inside it)
![image](https://user-images.githubusercontent.com/70998757/193536404-1aad969d-e2fb-4ab1-af27-3c38be4ac90d.png)

## How to export as CSV

1. The .heatmap files are actually just zip files containing CSVs. You might need to rename the file extension to .zip for them to behave with zip programs.

## Important note

The memory constraints of RuneLite plugins make it difficult to handle such large PNG images as this plugin does, so they are read, modified, and written one piece at a time. If the plugin is crashing for you when you write an image, try lowering the "speed-memory tradeoff" config setting until it works.
