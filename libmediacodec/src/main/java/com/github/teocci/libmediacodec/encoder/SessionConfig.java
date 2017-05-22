package com.github.teocci.libmediacodec.encoder;

import java.io.File;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Configuration information for a Broadcasting or Recording session.
 * Includes meta data, video + audio encoding
 * and muxing parameters
 */

public class SessionConfig
{
    public static final String TAG = SessionConfig.class.getSimpleName();

    public static final int FRAME_RATE = 30;               // 30fps
    public static final float BPP = 0.10f;

    private final VideoEncoderConfig videoConfig;
    private final AudioEncoderConfig audioConfig;
    private File outputDirectory;
    private MediaMuxer mediaMuxer;
    private boolean attachLocation;

    public static int defaultWidth = 720;
    public static int defaultHeight = 1080;

    public static String sessionFolderTemp = "session_temp";
    public static String sessionFolder = "session";


    public SessionConfig(MediaMuxer muxer, VideoEncoderConfig videoConfig, AudioEncoderConfig audioConfig)
    {
        this.videoConfig = checkNotNull(videoConfig);
        this.audioConfig = checkNotNull(audioConfig);

        mediaMuxer = checkNotNull(muxer);
    }

    public MediaMuxer getMuxer()
    {
        return mediaMuxer;
    }

    public void setOutputDirectory(File outputDirectory)
    {
        this.outputDirectory = outputDirectory;
    }

    public File getOutputDirectory()
    {
        return outputDirectory;
    }

    public String getOutputPath()
    {
        return mediaMuxer.getOutputPath();
    }

    public int getTotalBitrate()
    {
        return videoConfig.getBitRate() + audioConfig.getBitrate();
    }

    public int getVideoResolutionWidth()
    {
        return videoConfig.getResolutionWidth();
    }

    public int getVideoResolutionHeight()
    {
        return videoConfig.getResolutionHeight();
    }

    public int getVideoWidth()
    {
        return videoConfig.getWidth();
    }

    public int getVideoHeight()
    {
        return videoConfig.getHeight();
    }

    public int getVideoBitrate()
    {
        return videoConfig.getBitRate();
    }

    public VideoEncoderConfig getVideoConfig()
    {
        return videoConfig;
    }

    public int getNumAudioChannels()
    {
        return audioConfig.getNumChannels();
    }

    public int getAudioBitrate()
    {
        return audioConfig.getBitrate();
    }

    public int getAudioSamplerate()
    {
        return audioConfig.getSampleRate();
    }

    public boolean shouldAttachLocation()
    {
        return attachLocation;
    }

    public void setAttachLocation(boolean mAttachLocation)
    {
        this.attachLocation = mAttachLocation;
    }

    public static class Builder
    {
        private int width;
        private int height;
        private int videoBitrate;
        private int videoFramerate;

        private int audioSamplerate;
        private int audioBitrate;
        private int audioChannels;

        private MediaMuxer mediaMuxer;

        private File outputDirectory;
        private String title;
        private String description;
        private boolean isPrivate;
        private boolean isAttachLocation;

        /**
         * Configure a SessionConfig quickly with intelligent path interpretation.
         * Valid inputs are "/path/to/name.m3u8", "/path/to/name.mp4"
         * <p/>
         * For file-based outputs (.m3u8, .mp4) the file structure is managed
         * by a recording UUID.
         * <p/>
         * Given an absolute file-based outputLocation like:
         * <p/>
         * /sdcard/test.m3u8
         * <p/>
         * the output will be available in:
         * <p/>
         * /sdcard/<UUID>/test.m3u8
         * /sdcard/<UUID>/test0.ts
         * /sdcard/<UUID>/test1.ts
         * ...
         * <p/>
         * You can query the final outputLocation after building with
         * SessionConfig.getOutputPath()
         *
         * @param outputLocation desired output location. For file based recording,
         *                       recordings will be stored at <outputLocationParent>/<UUID>/<outputLocationFileName>
         */
        public Builder(String outputLocation)
        {
            setAVDefaults();
            setMetaDefaults();

            if (outputLocation.contains(".mp4")) {
                mediaMuxer = AndroidMuxer.create(createRecordingPath(outputLocation), MediaMuxer.MediaFormat.MPEG4);
            } else
                throw new RuntimeException("Unexpected mediaMuxer output. Expected a .mp4, Got: " + outputLocation);

        }


        /**
         * @param outputPath a desired storage location like /path/filename.ext
         * @return a File pointing to /path/filename.ext
         */
        private String createRecordingPath(String outputPath)
        {
            File desiredFile = new File(outputPath);
            String desiredFilename = desiredFile.getName();
            File outputDir = new File(desiredFile.getParent(), sessionFolderTemp);
            outputDirectory = outputDir;
            outputDir.mkdirs();
            return new File(outputDir, desiredFilename).getAbsolutePath();
        }

        private void setAVDefaults()
        {
            width = SessionConfig.defaultWidth;
            height = SessionConfig.defaultHeight;
            videoBitrate = (int) (BPP * FRAME_RATE * width * height);
            videoFramerate = FRAME_RATE;

            audioSamplerate = 44100;
            audioBitrate = 96 * 1000;
            audioChannels = 1;
        }

        private void setMetaDefaults()
        {
            isPrivate = false;
            isAttachLocation = true;
        }

        public Builder withMuxer(MediaMuxer muxer)
        {
            mediaMuxer = checkNotNull(muxer);
            return this;
        }

        public Builder withTitle(String title)
        {
            this.title = title;
            return this;
        }

        public Builder withDescription(String description)
        {
            this.description = description;
            return this;
        }

        public Builder withPrivateVisibility(boolean isPrivate)
        {
            this.isPrivate = isPrivate;
            return this;
        }

        public Builder withLocation(boolean attachLocation)
        {
            isAttachLocation = attachLocation;
            return this;
        }

        public Builder withVideoResolution(int width, int height)
        {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder withVideoBitrate(int bitrate)
        {
            videoBitrate = bitrate;
            return this;
        }

        public Builder withVideoFramerate(int framerate)
        {
            videoFramerate = framerate;
            return this;
        }

        public Builder withAudioSamplerate(int samplerate)
        {
            audioSamplerate = samplerate;
            return this;
        }

        public Builder withAudioBitrate(int bitrate)
        {
            audioBitrate = bitrate;
            return this;
        }

        public Builder withAudioChannels(int numChannels)
        {
            checkArgument(numChannels == 0 || numChannels == 1);
            audioChannels = numChannels;
            return this;
        }


        public SessionConfig build()
        {
            SessionConfig session = new SessionConfig(mediaMuxer,
                    new VideoEncoderConfig(width, height, videoBitrate, videoFramerate),
                    new AudioEncoderConfig(audioChannels, audioSamplerate, audioBitrate));

            session.setAttachLocation(isAttachLocation);
            session.setOutputDirectory(outputDirectory);

            return session;
        }
    }
}
