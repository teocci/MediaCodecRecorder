package com.github.teocci.libmediacodec.encoder;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @hide
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class AndroidMuxer extends MediaMuxer
{
    private static final String TAG = AndroidMuxer.class.getSimpleName();
    private static final boolean VERBOSE = false;

    private android.media.MediaMuxer muxer;
    private boolean isStarted;


    private AndroidMuxer(String outputFile, MediaFormat format)
    {
        super(outputFile, format);
        try {
            switch (format) {
                case MPEG4:
                    muxer = new android.media.MediaMuxer(outputFile, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    break;
                default:
                    throw new IllegalArgumentException("Unrecognized mediaFormat!");
            }
        } catch (IOException e) {
            throw new RuntimeException("MediaMuxer creation failed", e);
        }
        isStarted = false;
    }

    public static AndroidMuxer create(String outputFile, MediaFormat format)
    {
        return new AndroidMuxer(outputFile, format);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    @Override
    public int addTrack(android.media.MediaFormat trackFormat)
    {
        super.addTrack(trackFormat);
        if (isStarted)
            throw new RuntimeException("mediaFormat changed twice");
        int track = muxer.addTrack(trackFormat);

        if (allTracksAdded()) {
            start();
        }
        return track;
    }

    protected void start()
    {
        muxer.start();
        isStarted = true;
        Log.d(TAG, "Android mediaMuxer start");
    }

    protected void stop()
    {
        muxer.stop();
        isStarted = false;
        Log.d(TAG, "Android mediaMuxer stop");
        release();
        ;
    }

    @Override
    public void release()
    {
        if (muxer != null) {
            super.release();
            muxer.release();
            muxer = null;
            Log.d(TAG, "Android mediaMuxer Release");
        } else {
            Log.d(TAG, "Android mediaMuxer Release called twice");
        }

    }

    @Override
    public boolean isStarted()
    {
        return isStarted;
    }

    @Override
    public void writeSampleData(MediaCodec encoder, int trackIndex, int bufferIndex, ByteBuffer encodedData, MediaCodec.BufferInfo bufferInfo)
    {
        super.writeSampleData(encoder, trackIndex, bufferIndex, encodedData, bufferInfo);
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // MediaMuxer gets the codec config info via the addTrack command
            if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
            encoder.releaseOutputBuffer(bufferIndex, false);
            return;
        }

        if (bufferInfo.size == 0) {
            if (VERBOSE) Log.d(TAG, "ignoring zero size buffer");
            encoder.releaseOutputBuffer(bufferIndex, false);
            return;
        }

        if (!isStarted) {
            Log.e(TAG, "writeSampleData called before mediaMuxer isStarted. Ignoring packet. Track index: " + trackIndex + " tracks added: " + numTracks);
            encoder.releaseOutputBuffer(bufferIndex, false);
            return;
        }

        bufferInfo.presentationTimeUs = getNextRelativePts(bufferInfo.presentationTimeUs, trackIndex);

        muxer.writeSampleData(trackIndex, encodedData, bufferInfo);

        encoder.releaseOutputBuffer(bufferIndex, false);

        if (allTracksFinished()) {
            stop();
        }
    }

    @Override
    public void forceStop()
    {
        stop();
        Log.d(TAG, "forceStop");
    }
}
