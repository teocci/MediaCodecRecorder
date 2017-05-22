package com.github.teocci.libmediacodec.encoder;

import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import java.io.IOException;

/**
 * @hide
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class AudioEncoderCore extends AndroidEncoder
{
    private static final String TAG = AudioEncoderCore.class.getSimpleName();

    // AAC Low Overhead Audio Transport Multiplex
    protected static final String MIME_TYPE = "audio/mp4a-latm";

    // Configurable options
    protected int mChannelConfig;
    protected int mSampleRate;

    /**
     * Configures encoder and mediaMuxer state, and prepares the input Surface.
     */
    public AudioEncoderCore(int numChannels, int bitRate, int sampleRate, MediaMuxer muxer) throws IOException
    {
        switch (numChannels) {
            case 1:
                mChannelConfig = AudioFormat.CHANNEL_IN_MONO;
                break;
            case 2:
                mChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
                break;
            default:
                throw new IllegalArgumentException("Invalid channel count. Must be 1 or 2");
        }
        mSampleRate = sampleRate;
        this.mediaMuxer = muxer;
        bufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, mSampleRate, mChannelConfig);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, mSampleRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, numChannels);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        // Create a MediaCodec encoder, and configure it with our mediaFormat.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mediaEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mediaEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaEncoder.start();

        trackIndex = -1;
    }

    /**
     * Depending on this method ties AudioEncoderCore
     * to a MediaCodec-based implementation.
     * <p/>
     * However, when reading AudioRecord samples directly
     * to MediaCode's input ByteBuffer we can avoid a memory copy
     * TODO: Measure performance gain and remove if negligible
     *
     * @return
     */
    public MediaCodec getMediaCodec()
    {
        return mediaEncoder;
    }

    @Override
    protected boolean isSurfaceInputEncoder()
    {
        return false;
    }

}
