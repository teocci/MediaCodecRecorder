package com.github.teocci.libmediacodec.encoder;

import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Trace;
import android.util.Log;

import junit.framework.Assert;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by davidbrodsky on 1/23/14.
 *
 * @hide
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class MicrophoneEncoder implements Runnable
{
    private static final String TAG = MicrophoneEncoder.class.getSimpleName();

    private static final boolean TRACE = true;
    private static final boolean VERBOSE = true;

    // AAC frame size. Audio encoder input size is a multiple of this
    protected static final int SAMPLES_PER_FRAME = 1024;
    protected static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final Object readyFence = new Object();    // Synchronize audio thread readiness
    private boolean threadReady;                       // Is audio thread ready
    private boolean threadRunning;                     // Is audio thread running
    private final Object waitForRecordingFence = new Object();
    private final Object recordingFence = new Object();

    private AudioRecord audioRecord;
    private AudioEncoderCore encoderCore;

    private boolean recordingRequested;

    public MicrophoneEncoder(SessionConfig config) throws IOException
    {
        init(config);
    }

    private void init(SessionConfig config) throws IOException
    {
        encoderCore = new AudioEncoderCore(config.getNumAudioChannels(),
                config.getAudioBitrate(),
                config.getAudioSamplerate(),
                config.getMuxer());
        mMediaCodec = null;
        threadReady = false;
        threadRunning = false;
        recordingRequested = false;
        startThread();
        if (VERBOSE) Log.i(TAG, "Finished init. encoder : " + encoderCore.mediaEncoder);
        Assert.assertNotNull(encoderCore.getMediaCodec());
    }

    private void setupAudioRecord()
    {
        int minBufferSize = AudioRecord.getMinBufferSize(encoderCore.mSampleRate,
                encoderCore.mChannelConfig, AUDIO_FORMAT);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, // source
                encoderCore.mSampleRate,            // sample rate, hz
                encoderCore.mChannelConfig,         // channels
                AUDIO_FORMAT,                        // audio mediaFormat
                minBufferSize * 4);                  // buffer size (bytes)
    }


    public void startRecording()
    {
        if (VERBOSE) Log.i(TAG, "startRecording");
        synchronized (waitForRecordingFence) {
            totalSamplesNum = 0;
            startPTS = 0;
            recordingRequested = true;
            waitForRecordingFence.notify();
        }
    }

    public void stopRecording()
    {
        Log.i(TAG, "stopRecording");
        synchronized (waitForRecordingFence) {
            recordingRequested = false;
        }
    }

    public void reset(SessionConfig config) throws IOException
    {
        if (VERBOSE) Log.i(TAG, "reset");
        if (threadRunning) {
            Log.e(TAG, "reset called before stop completed");
        }

        synchronized (recordingFence) {
            Log.e(TAG, "init called in reset");
            init(config);
        }
    }

    public boolean isRecording()
    {
        return recordingRequested;
    }


    private void startThread()
    {
        synchronized (readyFence) {
            if (threadRunning) {
                Log.w(TAG, "Audio thread running when start requested");
                return;
            }
            Thread audioThread = new Thread(this, "MicrophoneEncoder");
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
            while (!threadReady) {
                try {
                    readyFence.wait();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public void run()
    {
        setupAudioRecord();
        audioRecord.startRecording();
        synchronized (readyFence) {
            threadReady = true;
            readyFence.notify();
        }

        synchronized (waitForRecordingFence) {
            while (!recordingRequested) {
                try {
                    waitForRecordingFence.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        Assert.assertNotNull(encoderCore.getMediaCodec());
        if (VERBOSE) Log.i(TAG, "Begin Audio transmission to encoder. encoder : " + encoderCore.mediaEncoder);

        synchronized (recordingFence) {
            while (recordingRequested) {

                if (TRACE) Trace.beginSection("drainAudio");
                encoderCore.drainEncoder(false);
                if (TRACE) Trace.endSection();

                if (TRACE) Trace.beginSection("sendAudio");
                sendAudioToEncoder(false);
                if (TRACE) Trace.endSection();

            }

            threadReady = false;
             /*if (VERBOSE) */
            Log.i(TAG, "Exiting audio encode loop. Draining Audio Encoder");
            if (TRACE) Trace.beginSection("sendAudio");
            sendAudioToEncoder(true);
            if (TRACE) Trace.endSection();
            audioRecord.stop();
            if (TRACE) Trace.beginSection("drainAudioFinal");
            encoderCore.signalEndOfStream();
            encoderCore.drainEncoder(true);
            if (TRACE) Trace.endSection();
            encoderCore.release();
            threadRunning = false;
            recordingFence.notify();
        }

    }

    // Variables recycled between calls to sendAudioToEncoder
    MediaCodec mMediaCodec;
    int audioInputBufferIndex;
    int audioInputLength;
    long audioAbsolutePtsUs;

    private void sendAudioToEncoder(boolean endOfStream)
    {
        if (mMediaCodec == null)
            mMediaCodec = encoderCore.getMediaCodec();
        // send current frame data to encoder
        try {
            ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();
            audioInputBufferIndex = mMediaCodec.dequeueInputBuffer(-1);
            if (audioInputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[audioInputBufferIndex];
                inputBuffer.clear();
                audioInputLength = audioRecord.read(inputBuffer, SAMPLES_PER_FRAME * 2);
                audioAbsolutePtsUs = (System.nanoTime()) / 1000L;
                // We divide audioInputLength by 2 because audio samples are
                // 16bit.
                audioAbsolutePtsUs = getJitterFreePTS(audioAbsolutePtsUs, audioInputLength / 2);

                if (audioInputLength == AudioRecord.ERROR_INVALID_OPERATION)
                    Log.e(TAG, "Audio read error: invalid operation");
                if (audioInputLength == AudioRecord.ERROR_BAD_VALUE)
                    Log.e(TAG, "Audio read error: bad value");
//                if (VERBOSE)
//                    Log.i(TAG, "queueing " + audioInputLength + " audio bytes with pts " + audioAbsolutePtsUs);
                if (endOfStream) {
                    if (VERBOSE) Log.i(TAG, "EOS received in sendAudioToEncoder");
                    mMediaCodec.queueInputBuffer(audioInputBufferIndex, 0, audioInputLength, audioAbsolutePtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    mMediaCodec.queueInputBuffer(audioInputBufferIndex, 0, audioInputLength, audioAbsolutePtsUs, 0);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "_offerAudioEncoder exception");
            t.printStackTrace();
        }
    }

    long startPTS = 0;
    long totalSamplesNum = 0;

    /**
     * Ensures that each audio pts differs by a constant amount from the previous one.
     *
     * @param bufferPts        presentation timestamp in us
     * @param bufferSamplesNum the number of samples of the buffer's frame
     * @return
     */
    private long getJitterFreePTS(long bufferPts, long bufferSamplesNum)
    {
        long correctedPts = 0;
        long bufferDuration = (1000000 * bufferSamplesNum) / (encoderCore.mSampleRate);
        bufferPts -= bufferDuration; // accounts for the delay of acquiring the audio buffer
        if (totalSamplesNum == 0) {
            // reset
            startPTS = bufferPts;
            totalSamplesNum = 0;
        }
        correctedPts = startPTS + (1000000 * totalSamplesNum) / (encoderCore.mSampleRate);
        if (bufferPts - correctedPts >= 2 * bufferDuration) {
            // reset
            startPTS = bufferPts;
            totalSamplesNum = 0;
            correctedPts = startPTS;
        }
        totalSamplesNum += bufferSamplesNum;
        return correctedPts;
    }
}
