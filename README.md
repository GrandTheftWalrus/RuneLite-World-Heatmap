# RuneLite World Heatmap plugin
This plugin logs each tile that the local player steps on to create a visualization of where they most often travel.  There is a "Type A" heatmap, where the value of the tile that the local player is standing on is incremented *each new time it is stepped on*. There is also a "Type B" heatmap, which does the same as Type A, but it also increments the current tile every game tick, even if it's the same tile stood on last game tick, meaning that standing in the same place for a long time will continuously increase that tile's value. "Type A" will create a heatmap better suited for visualizing the paths taken, whilst "Type B" will help visualize where the most time is spent. Data of step values on each tile is able to be exported as a .CSV file.

![heatmap after](https://user-images.githubusercontent.com/70998757/193519766-971c6ae8-0f44-42cd-8013-84ec34e2c122.png)
![image](https://user-images.githubusercontent.com/70998757/193519598-e1c4f87d-c29a-4813-afd1-7d1c77e482eb.png)
![image](https://user-images.githubusercontent.com/70998757/193519625-1e6a5246-865a-4ec1-a804-e8279c3481f5.png)

With the .csv export option, the data can be analyzed more fancily with other tools such as R, or Quicken 2003 (not included):
![image](https://user-images.githubusercontent.com/70998757/193536404-1aad969d-e2fb-4ab1-af27-3c38be4ac90d.png)

