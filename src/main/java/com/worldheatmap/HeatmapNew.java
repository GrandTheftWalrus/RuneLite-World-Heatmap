package com.worldheatmap;

import java.awt.*;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;

public class HeatmapNew implements Serializable{

    protected HashMap<Point, Integer> heatmapHashMap;
    protected static final long serialVersionUID = 100L;
    protected int stepCount;
    private int tilesVisited;
    protected int[] maxVal = {1, 0, 0}, minVal = {1, 0, 0}; // {val, x, y}
    protected static final int
            HEATMAP_WIDTH = 2752,       //never change these
            HEATMAP_HEIGHT = 1664,      //never change these
            HEATMAP_OFFSET_X = -1152,   //never change these
            HEATMAP_OFFSET_Y = -2496;   //never change these (for backwards compatibility)

    public HeatmapNew(){
        stepCount = 0;
        tilesVisited = 0;
        heatmapHashMap = new HashMap<Point, Integer>();
    }

    // The following horse shit is for backwards compatibility with the old, retarded method of storing heatmap data
    public static HeatmapNew convertOldHeatmapToNew(Heatmap oldStyle){
        HeatmapNew newStyle = new HeatmapNew();
        for (int x = 0; x < HEATMAP_WIDTH; x++)
            for (int y = 0; y < HEATMAP_HEIGHT; y++)
                if (oldStyle.heatmapCoordsGet(x, y) != 0)
                    newStyle.set(oldStyle.heatmapCoordsGet(x, y), x - HEATMAP_OFFSET_X , y - HEATMAP_OFFSET_Y);
        return newStyle;
    }

    protected Set<Entry<Point, Integer>> getEntrySet(){
        return heatmapHashMap.entrySet();
    }

    /**
     * Increments the heatmap's value at the given location by 1
     * @param x Original RuneScape x-coord
     * @param y Original RuneScape y-coord
     */
    protected void increment(int x, int y) {
        Integer oldValue = heatmapHashMap.putIfAbsent(new Point(x, y), 0);
        if (oldValue == null)
            tilesVisited++;
        int newValue =  heatmapHashMap.get(new Point(x, y)) + 1;
        heatmapHashMap.put(new Point(x, y), newValue);
        stepCount++;
        //Update maxval
        if (newValue > maxVal[0])
            maxVal = new int[]{newValue, x, y};
    }

    protected HashMap<Point, Double> constructRanks() {
        HashMap<Point, Double> ranks = new HashMap<>();
        ArrayList<Integer> list = new ArrayList<>();
        for (HashMap.Entry<Point, Integer> e: heatmapHashMap.entrySet())
            list.add(e.getValue());
        Collections.sort(list, (x, y) -> x-y);

        for (Point p : heatmapHashMap.keySet()) {
            if (list.indexOf(heatmapHashMap.get(p)) == 0)
                ranks.put(p, 0.0);
            else if (list.lastIndexOf(heatmapHashMap.get(p)) == list.size() - 1)
                ranks.put(p, 1.0);
            else
                ranks.put(p, (list.indexOf(heatmapHashMap.get(p)) + list.lastIndexOf(heatmapHashMap.get(p))) / 2.0 / list.size());
        }
        return ranks;
    }

    /**
     * Sets the heatmap's value at the given location to the given value
     * @param newValue New value
     * @param x Original RuneScape x-coord
     * @param y Original RuneScape y-coord
     */
    protected void set(int newValue, int x, int y){
        //We don't keep track of unstepped-on tiles
        if (newValue <= 0)
            return;

        //Set it & retrieve previous value
        Integer oldValue = heatmapHashMap.put(new Point(x, y), newValue);

        //Update step count
        if (oldValue == null)
            stepCount += newValue;
        else
            stepCount += (newValue - oldValue);

        //Update min/max vals
        if (newValue > maxVal[0])
            maxVal = new int[]{newValue, x, y};
        if (newValue <= minVal[0])
            minVal = new int[]{newValue, x, y};

        //Update tilesVisited
        if (oldValue == null)
            tilesVisited++;
    }

    /**
     * Returns the heatmap's value at the given location according to the internal coordinate style (it's offset from the RuneScape coordinates)
     * @param x Heatmap-style x-coord
     * @param y Heatmap-style y-coord
     */
    protected int get(int x, int y){
        return heatmapHashMap.get(new Point(x, y));
    }

    protected int getTilesVisited(){
        return tilesVisited;
    }

    protected int getStepCount(){
        return stepCount;
    }

    protected int getTileCount(){
        return heatmapHashMap.size();
    }

    /**
     * @return int array holding {maxVal, maxX, maxY} where the latter two are the coordinate at which the max value exists
     */
    protected int[] getMaxVal(){
        return maxVal;
    }

    /**
     * @return int array holding {minVal, minX, minY} where the latter two are the coordinate at which the minimum NON-ZERO value exists
     */
    protected int[] getMinVal(){
        return minVal;
    }
}
