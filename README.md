# RuneLite World Heatmap plugin
This plugin logs each tile that the local player steps on to create a visualization of where they most often travel.  There is a "Type A" heatmap, where the value of the tile that the local player is standing on is incremented *each new time it is stepped on*. There is also a "Type B" heatmap, which does the same as Type A, but it also increments the current tile every game tick, even if it's the same tile stood on last game tick, meaning that standing in the same place for a long time will continuously increase that tile's value. "Type A" will create a heatmap better suited for visualizing the paths taken, whilst "Type B" will help visualize where the most time is spent. Also, the "Type B" heatmap's values are displayed logarithmically in its output image.

![image](https://user-images.githubusercontent.com/70998757/171306329-e0aeb8d3-af8a-4b72-82b6-94b2e158d37b.png)
![image](https://user-images.githubusercontent.com/70998757/178887016-1e6b089b-ceac-4cf0-8b2e-8f8bcce57cc9.png)
![image](https://user-images.githubusercontent.com/70998757/178887786-3c171f03-d0d2-4b8c-a731-7407ff9ace51.png)
![image](https://user-images.githubusercontent.com/70998757/178887820-395214e4-79bd-4176-861d-5519ae1fcc93.png)
