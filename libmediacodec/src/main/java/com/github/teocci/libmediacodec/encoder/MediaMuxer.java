package com.github.teocci.libmediacodec.encoder;

import android.media.MediaCodec;
import android.os.Build;
import android.util.Log;

import com.google.common.eventbus.EventBus;

import java.nio.ByteBuffer;

import com.github.teocci.libmediacodec.event.MuxerFinishedEvent;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base MediaMuxer class for interaction with MediaCodec based
 * encoders
 *
 * @hide
 */
public abstract class MediaMuxer
{
    private static final String TAG = MediaMuxer.class.getSimpleName();

    public static enum MediaFormat
    {
        MPEG4, HLS
    }

    private final int expectedNumTracks = 2;           // TODO: Make this configurable?

    protected MediaFormat mediaFormat;
    protected String outputPath;
    protected int numTracks;
    protected int numTracksFinished;
    protected long firstPts;
    protected long lastPts[];

    private EventBus mEventBus;

    protected MediaMuxer(String outputPath, MediaFormat mediaFormat)
    {
        Log.i(TAG, "Created mediaMuxer for output: " + outputPath);
        this.outputPath = checkNotNull(outputPath);
        this.mediaFormat = mediaFormat;
        numTracks = 0;
        numTracksFinished = 0;
        firstPts = 0;
        lastPts = new long[expectedNumTracks];
        for (int i = 0; i < lastPts.length; i++) {
            lastPts[i] = 0;
        }
    }

    public void setEventBus(EventBus eventBus)
    {
        mEventBus = eventBus;
    }

    /**
     * Returns the absolute output path.
     * <p>
     * e.g /sdcard/app/uuid/index.m3u8
     *
     * @return
     */
    public String getOutputPath()
    {
        return outputPath;
    }

    /**
     * Adds the specified track and returns the track index
     *
     * @param trackFormat MediaFormat of the track to add. Gotten from MediaCodec#dequeueOutputBuffer
     *                    when returned status is INFO_OUTPUT_FORMAT_CHANGED
     * @return index of track in output file
     */
    public int addTrack(android.media.MediaFormat trackFormat)
    {
        numTracks++;
        return numTracks - 1;
    }

    /**
     * Called by the hosting Encoder
     * to notify the MediaMuxer that it should no
     * longer assume the Encoder resources are available.
     */
    public void onEncoderReleased(int trackIndex)
    {
    }

    public void release()
    {
        if (mEventBus != null)
            mEventBus.post(new MuxerFinishedEvent());
    }

    public boolean isStarted()
    {
        return false;
    }

    /**
     * Write the MediaCodec output buffer. This method <b>must</b>
     * be overridden by subclasses to release encodedData, transferring
     * ownership back to encoder, by calling encoder.releaseOutputBuffer(bufferIndex, false);
     *
     * @param trackIndex
     * @param encodedData
     * @param bufferInfo
     */
    public void writeSampleData(MediaCodec encoder, int trackIndex, int bufferIndex, ByteBuffer encodedData, MediaCodec.BufferInfo bufferInfo)
    {
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            signalEndOfTrack();
        }
    }

    public abstract void forceStop();

    protected boolean allTracksFinished()
    {
        return (numTracks == numTracksFinished);
    }

    protected boolean allTracksAdded()
    {
        return (numTracks == expectedNumTracks);
    }

    /**
     * MediaMuxer will call this itself if it detects BUFFER_FLAG_END_OF_STREAM
     * in writeSampleData.
     */
    protected void signalEndOfTrack()
    {
        numTracksFinished++;
        Log.d(TAG, "signalEndOfTrack numTracksFinished count : " + numTracksFinished);
    }

    /**
     * Does this MediaMuxer's mediaFormat require AAC ADTS headers?
     * see http://wiki.multimedia.cx/index.php?title=ADTS
     *
     * @return
     */
    protected boolean formatRequiresADTS()
    {
        switch (mediaFormat) {
            case HLS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Does this MediaMuxer's mediaFormat require
     * copying and buffering encoder output buffers.
     * Generally speaking, is the output a Socket or File?
     *
     * @return
     */
    protected boolean formatRequiresBuffering()
    {
        if (Build.VERSION.SDK_INT >= 21) return true;

        switch (mediaFormat) {
            case HLS:
                return false;
            default:
                return false;
        }
    }

    /**
     * Return a relative pts given an absolute pts and trackIndex.
     * <p>
     * This method advances the state of the MediaMuxer, and must only
     * be called once per call to {@link #writeSampleData(MediaCodec, int, int, ByteBuffer, MediaCodec.BufferInfo)}.
     */
    protected long getNextRelativePts(long absPts, int trackIndex)
    {
        if (firstPts == 0) {
            firstPts = absPts;
            return 0;
        }
        return getSafePts(absPts - firstPts, trackIndex);
    }

    /**
     * Sometimes packets with non-increasing pts are dequeued from the MediaCodec output buffer.
     * This method ensures that a crash won't occur due to non monotonically increasing packet timestamp.
     */
    private long getSafePts(long pts, int trackIndex)
    {
        if (lastPts[trackIndex] >= pts) {
            // Enforce a non-zero minimum spacing
            // between pts
            lastPts[trackIndex] += 9643;
            return lastPts[trackIndex];
        }
        lastPts[trackIndex] = pts;
        return pts;
    }
}
