package com.github.teocci.libmediacodec.encoder;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.nio.ByteBuffer;

import static com.github.teocci.libmediacodec.utils.CameraUtils.isKitKat;


/**
 * @hide
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public abstract class AndroidEncoder
{
    private final static String TAG = AndroidEncoder.class.getSimpleName();
    private final static boolean VERBOSE = false;

    protected MediaMuxer mediaMuxer;
    protected MediaCodec mediaEncoder;
    protected MediaCodec.BufferInfo bufferInfo;
    protected int trackIndex;
    protected volatile boolean forceEOS = false;

    protected int eosSpinCount = 0;
    protected final int MAX_EOS_SPINS = 10;

    /**
     * This method should be called before the last input packet is queued
     * Some devices don't honor MediaCodec#signalEndOfInputStream
     * e.g: Google Glass
     */
    public void signalEndOfStream()
    {
        forceEOS = true;
    }

    public void release()
    {
        if (mediaMuxer != null)
            mediaMuxer.onEncoderReleased(trackIndex);
        if (mediaEncoder != null) {
            mediaEncoder.stop();
            mediaEncoder.release();
            mediaEncoder = null;
            if (VERBOSE) Log.i(TAG, "Released encoder");
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public void adjustBitrate(int targetBitrate)
    {
        if (isKitKat() && mediaEncoder != null) {
            Bundle bitrate = new Bundle();
            bitrate.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, targetBitrate);
            mediaEncoder.setParameters(bitrate);
        } else if (!isKitKat()) {
            Log.w(TAG, "Ignoring adjustVideoBitrate call. This functionality is only available on Android API 19+");
        }
    }

    public void drainEncoder(boolean endOfStream)
    {
        if (endOfStream && VERBOSE) {
            if (isSurfaceInputEncoder()) {
                Log.i(TAG, "final video drain");
            } else {
                Log.i(TAG, "final audio drain");
            }
        }
        synchronized (mediaMuxer) {
            final int TIMEOUT_USEC = 1000;
            if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ") track: " + trackIndex);

            if (endOfStream) {
                if (VERBOSE) Log.d(TAG, "sending EOS to encoder for track " + trackIndex);
//                When all target devices honor MediaCodec#signalEndOfInputStream, return to this method
//                if(isSurfaceInputEncoder()){
//                    if (VERBOSE) Log.i(TAG, "signalEndOfInputStream for track " + trackIndex);
//                    mediaEncoder.signalEndOfInputStream();
//                    // Note: This method isn't honored on certain devices including Google Glass
//                }
            }

            ByteBuffer[] encoderOutputBuffers = mediaEncoder.getOutputBuffers();
            while (true) {
                int encoderStatus = mediaEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (!endOfStream) {
                        break;      // out of while
                    } else {
                        eosSpinCount++;
                        if (eosSpinCount > MAX_EOS_SPINS) {
                            if (VERBOSE) Log.i(TAG, "Force shutting down MediaMuxer");
                            mediaMuxer.forceStop();
                            break;
                        }
                        if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                    }
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    encoderOutputBuffers = mediaEncoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // should happen before receiving buffers, and should only happen once
                    MediaFormat newFormat = mediaEncoder.getOutputFormat();
                    if (VERBOSE) Log.d(TAG, "encoder output mediaFormat changed: " + newFormat);

                    // now that we have the Magic Goodies, start the mediaMuxer
                    trackIndex = mediaMuxer.addTrack(newFormat);
                    // MediaMuxer is responsible for starting/stopping itself
                    // based on knowledge of expected # tracks
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                            encoderStatus);
                    // let's ignore it
                } else {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null");
                    }

                    if (bufferInfo.size >= 0) {    // Allow zero length buffer for purpose of sending 0 size video EOS Flag
                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        if (forceEOS) {
                            bufferInfo.flags = bufferInfo.flags | MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                            Log.i(TAG, "Forcing EOS");
                        }
                        // It is the mediaMuxer's responsibility to release encodedData
                        mediaMuxer.writeSampleData(mediaEncoder, trackIndex, encoderStatus, encodedData, bufferInfo);
                        if (VERBOSE) {
                            Log.d(TAG, "sent " + bufferInfo.size + " bytes to mediaMuxer, \t ts=" +
                                    bufferInfo.presentationTimeUs + "track " + trackIndex);
                        }
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (!endOfStream) {
                            Log.w(TAG, "reached end of stream unexpectedly");
                        } else {
                            if (VERBOSE) Log.d(TAG, "end of stream reached for track " + trackIndex);
                        }
                        break;      // out of while
                    }
                }
            }
            if (endOfStream && VERBOSE) {
                if (isSurfaceInputEncoder()) {
                    Log.i(TAG, "final video drain complete");
                } else {
                    Log.i(TAG, "final audio drain complete");
                }
            }
        }
    }

    protected abstract boolean isSurfaceInputEncoder();
}
