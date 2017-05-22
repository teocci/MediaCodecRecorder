package com.github.teocci.libmediacodec.encoder;

import android.support.v4.util.Pair;

import java.util.ArrayList;

/**
 * @hide
 */
public class VideoEncoderConfig
{
    protected final int width;
    protected final int height;
    protected final int bitRate;
    protected final int fameRate;
    protected ArrayList<Pair<Integer, Integer>> supportedResolution = new ArrayList<>();

    public VideoEncoderConfig(int width, int height, int bitRate, int fameRate)
    {
        this.width = width;
        this.height = height;
        this.bitRate = bitRate;
        this.fameRate = fameRate;
    }

    public int getWidth()
    {
        return width;
    }

    public int getHeight()
    {
        return height;
    }

    public int getResolutionWidth()
    {
        return width > height ? width : height;
    }

    public int getResolutionHeight()
    {
        return width > height ? height : width;
    }

    public int getBitRate()
    {
        return bitRate;
    }

    public int getFameRate()
    {
        return fameRate;
    }

    @Override
    public String toString()
    {
        return "VideoEncoderConfig: " + width + "x" + height + " @" + bitRate + " bps | fps: " + fameRate;
    }

    public void addSupportedResolution(Pair<Integer, Integer> pair)
    {
        supportedResolution.add(pair);
    }
}