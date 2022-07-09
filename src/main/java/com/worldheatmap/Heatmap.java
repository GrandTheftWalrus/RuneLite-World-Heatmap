package com.worldheatmap;

import java.io.Serializable;

public class Heatmap implements Serializable {

    private final int WIDTH, HEIGHT, HEATMAP_OFFSET_X, HEATMAP_OFFSET_Y;
    private int[][] heatmap;
    private int stepCount;

    public Heatmap(int WIDTH, int HEIGHT, int HEATMAP_OFFSET_X, int HEATMAP_OFFSET_Y){
        this.WIDTH = WIDTH;
        this.HEIGHT = HEIGHT;
        this.HEATMAP_OFFSET_Y = HEATMAP_OFFSET_Y;
        this.HEATMAP_OFFSET_X = HEATMAP_OFFSET_X;
        stepCount = 0;
        createHeatmap();
    }

    private void createHeatmap(){
        heatmap = new int[WIDTH][HEIGHT];
    }

    protected boolean isInBounds(int x, int y){
        return (x + HEATMAP_OFFSET_X < WIDTH && y + HEATMAP_OFFSET_Y < HEIGHT);
    }

    /**
     * Sets the heatmap's value at the given location to the given value
     * @param val New value
     * @param x Original RuneScape x-coord
     * @param y Original RuneScape y-coord
     */
    protected void set(int val, int x, int y){
        int convertedX = x + HEATMAP_OFFSET_X;	//These offsets are to reconcile the matrix 'heatmap' being much smaller than the game coordinate domain (which is much larger than the explorable area)
        int convertedY = y + HEATMAP_OFFSET_Y;
        heatmap[convertedX][convertedY] = val;
    }

    /**
     * Returns the heatmap's value at the given location
     * @param x Original RuneScape x-coord
     * @param y Original RuneScape y-coord
     */
    protected int get(int x, int y){
        int convertedX = x + HEATMAP_OFFSET_X;	//These offsets are to reconcile the matrix 'heatmap' being much smaller than the game coordinate domain (which is much larger than the explorable area)
        int convertedY = y + HEATMAP_OFFSET_Y;
        return heatmap[convertedX][convertedY];
    }

    /**
     * Returns the heatmap's value at the given location according to the internal coordinate style (it's offset from the RuneScape coordinates)
     * @param x Heatmap-style x-coord
     * @param y Heatmap-style y-coord
     */
    protected int heatmapCoordsGet(int x, int y){
        return heatmap[x][y];
    }

    /**
     * Increments the heatmap's value at the given location by 1
     * @param x Original RuneScape x-coord
     * @param y Original RuneScape y-coord
     */
    protected void increment(int x, int y) {
        set(get(x, y) + 1, x, y);
        stepCount++;
    }

    protected int getStepCount(){
        return stepCount;
    }

    /**
     * @return int array holding {maxVal, maxX, maxY} where the latter two are the coordinate at which the max value exists
     */
    protected int[] getMaxVal(){
        int maxVal = 0, maxX = 0, maxY = 0;
        for (int y = 0; y < HEIGHT; y++)
            for (int x = 0; x < WIDTH; x++)
                if (heatmapCoordsGet(x, y) > maxVal) {
                    maxVal = heatmapCoordsGet(x, y);
                    maxX = x;
                    maxY = y;
                }
        return new int[]{maxVal, maxX, maxY};
    }

    /**
     * @return int array holding {minVal, minX, minY} where the latter two are the coordinate at which the minimum NON-ZERO value exists
     */
    protected int[] getMinVal(){
        int minVal = Integer.MAX_VALUE, minX = 0, minY = 0;
        for (int y = 0; y < HEIGHT; y++)
            for (int x = 0; x < WIDTH; x++)
                if (heatmapCoordsGet(x, y) != 0 && heatmapCoordsGet(x, y) < minVal) {
                    minVal = heatmapCoordsGet(x, y);
                    minX = x;
                    minY = y;
                }
        if (minVal > Integer.MAX_VALUE - 100)
            return new int[]{0, 0, 0};
        else
            return new int[]{minVal, minX, minY};
    }
}
