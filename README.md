# RuneLite World Heatmap plugin
This plugin logs each tile that the local player steps on to create a visualization of where they most often travel, gain xp, get loot, die, get damaged, deal damage, teleport to/from, see bob the cat, etc. Data can be exported as CSVs for analysis in other programs.

![image](https://github.com/GrandTheftWalrus/RuneLite-World-Heatmap/assets/70998757/60b840ad-f359-4ec8-98d0-a4d7948f5115)
![image](https://github.com/GrandTheftWalrus/RuneLite-World-Heatmap/assets/70998757/9d3675a8-5716-461b-ba18-2d152dbb46a0)
![image](https://github.com/GrandTheftWalrus/RuneLite-World-Heatmap/assets/70998757/ab05c3b4-ec2a-44d1-8afd-604db9eb5ee7)
![image](https://github.com/GrandTheftWalrus/RuneLite-World-Heatmap/assets/70998757/dac931e6-1d53-4536-916d-4464354ee046)


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

