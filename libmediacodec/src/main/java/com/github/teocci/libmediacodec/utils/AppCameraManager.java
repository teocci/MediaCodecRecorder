package com.github.teocci.libmediacodec.utils;

import android.content.Context;
import android.hardware.Camera;
import android.media.MediaActionSound;
import android.util.Log;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import java.io.IOException;
import java.util.List;

import com.github.teocci.libmediacodec.encoder.SessionConfig;
import com.github.teocci.libmediacodec.event.MuxerFinishedEvent;

/**
 * Created by rajnish on 9/11/15.
 */
public class AppCameraManager
{
    public static final String TAG = AppCameraManager.class.getSimpleName();
    private long recordingStartTime;
    private long recordingStopTime;
    private long elapsedTime;
    private MediaActionSound sound;
    private boolean isRecording;
    private EventBus eventBus;
    private SessionConfig lastSessionConfig;
    private SessionConfig sessionConfig;
    private Context context;
    private Camera camera;

    private String currentFlashMode;
    private String desiredFlashMode;

    private int currentCameraId;
    private int desiredCameraId;
    private int cameraPreviewWidth, cameraPreviewHeight;

    public AppCameraManager(Context context, SessionConfig config)
    {
        eventBus = new EventBus("CameraManager");
        eventBus.register(this);
        config.getMuxer().setEventBus(eventBus);
        sessionConfig = lastSessionConfig = config;
        this.context = context;
        loadMediaActionSoundPlayer();


        currentFlashMode = Camera.Parameters.FLASH_MODE_OFF;
        desiredFlashMode = null;

        currentCameraId = -1;
        desiredCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;

    }

    public void loadMediaActionSoundPlayer()
    {
        sound = new MediaActionSound();
        sound.load(MediaActionSound.START_VIDEO_RECORDING);
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        sound.load(MediaActionSound.FOCUS_COMPLETE);
    }


    public void startRecording()
    {
        isRecording = true;
        recordingStartTime = System.currentTimeMillis();
        sound.play(MediaActionSound.START_VIDEO_RECORDING);
    }

    public void stopRecording()
    {
        isRecording = false;
        recordingStopTime = System.currentTimeMillis();
        elapsedTime += (recordingStopTime - recordingStartTime);
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
    }

    public long getRecordingTime()
    {
        long currentTime = System.currentTimeMillis();
        return elapsedTime + (currentTime - recordingStartTime);
    }

    public void resetRecordingTime()
    {
        elapsedTime = 0;
        recordingStartTime = 0;
        recordingStopTime = 0;
    }

    public boolean isRecording()
    {
        return isRecording;
    }

    public void toggleFlash()
    {
//        mCamEncoder.toggleFlashMode();
    }

    public void changeRecordingState(boolean isRecording)
    {
        this.isRecording = isRecording;
        if (this.isRecording) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    public EventBus getEventBus()
    {
        return eventBus;
    }

    public void reset(SessionConfig config) throws IOException
    {
        Log.d(TAG, "reset");
        lastSessionConfig = sessionConfig;
        sessionConfig = config;
        if (eventBus != null) {
            config.getMuxer().setEventBus(eventBus);
        }
    }

    @Subscribe
    public void onDeadEvent(DeadEvent e)
    {
        Log.i(TAG, "DeadEvent ");
    }


    @Subscribe
    public void onMuxerFinished(MuxerFinishedEvent e)
    {
        Log.d(TAG, "onMuxerFinished");
        CameraUtils.moveVideoChunk(context, lastSessionConfig);
    }

    public int getCameraPreviewWidth()
    {
        return cameraPreviewWidth;
    }

    public int getCameraPreviewHeight()
    {
        return cameraPreviewHeight;
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets cameraPreviewWidth and cameraPreviewHeight to the actual width/height of the preview.
     */
    public String openCamera(int desiredWidth, int desiredHeight)
    {
        if (camera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        int targetCameraType = desiredCameraId;
        boolean triedAllCameras = false;
        cameraLoop:
        while (!triedAllCameras) {
            for (int i = 0; i < numCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == targetCameraType) {
                    camera = Camera.open(i);
                    currentCameraId = targetCameraType;
                    break cameraLoop;
                }
            }
            if (camera == null) {
                if (targetCameraType == desiredCameraId)
                    targetCameraType = (desiredCameraId == Camera.CameraInfo.CAMERA_FACING_BACK
                            ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK);
                else
                    triedAllCameras = true;
            }

        }

        if (camera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            currentCameraId = -1;
            camera = Camera.open();    // opens first back-facing camera
        }
        if (camera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = camera.getParameters();

        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);
//
        // leave the frame rate set to default
        camera.setParameters(parms);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        parms.getPreviewFpsRange(fpsRange);
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }

        cameraPreviewWidth = mCameraPreviewSize.width;
        cameraPreviewHeight = mCameraPreviewSize.height;
        return previewFacts;
    }


    /**
     * Request the device camera not currently selected
     * be made active. This will take effect immediately
     * or as soon as the camera preview becomes active.
     * <p/>
     * Called from UI thread
     */
    public void requestOtherCamera()
    {
        int otherCamera = 0;
        if (currentCameraId == 0)
            otherCamera = 1;
        requestCamera(otherCamera);
    }

    /**
     * Request a Camera by cameraId. This will take effect immediately
     * or as soon as the camera preview becomes active.
     * <p/>
     * Called from UI thread
     *
     * @param camera
     */
    public void requestCamera(int camera)
    {
        if (Camera.getNumberOfCameras() == 1) {
            Log.w(TAG, "Ignoring requestCamera: only one device camera available.");
            return;
        }
        desiredCameraId = camera;
    }

    private void onPauseCameraSetup()
    {
        releaseCamera();
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    public void releaseCamera()
    {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    public void toggleFlashMode()
    {
        String otherFlashMode = "";
        if (currentFlashMode.equals(Camera.Parameters.FLASH_MODE_TORCH)) {
            otherFlashMode = Camera.Parameters.FLASH_MODE_OFF;
        } else {
            otherFlashMode = Camera.Parameters.FLASH_MODE_TORCH;
        }
        requestFlash(otherFlashMode);
    }

    /**
     * Sets the requested flash mode and restarts the
     * camera preview. This will take effect immediately
     * or as soon as the camera preview becomes active.
     * <p/>
     * <p/>
     * Called from UI thread
     */
    public void requestFlash(String desiredFlash)
    {
        desiredFlashMode = desiredFlash;
        /* If camera for some reason is null now flash mode will be applied
         * next time the camera opens through desiredFlashMode. */
        if (camera == null) {
            Log.w(TAG, "Ignoring requestFlash: Camera isn't available now.");
            return;
        }
        Camera.Parameters params = camera.getParameters();
        List<String> flashModes = params.getSupportedFlashModes();
        /* If the device doesn't have a camera flash or
         * doesn't support our desired flash modes return */

        Log.i(TAG, "Trying to set flash to: " + desiredFlashMode + " modes available: " + flashModes);


        if (isValidFlashMode(flashModes, desiredFlashMode) && desiredFlashMode != currentFlashMode) {
            currentFlashMode = desiredFlashMode;
            desiredFlashMode = null;
            try {
                params.setFlashMode(currentFlashMode);
                camera.setParameters(params);
                Log.i(TAG, "Changed flash successfully!");
            } catch (RuntimeException e) {
                Log.d(TAG, "Unable to set flash" + e);
            }
        }
    }

    /**
     * @param flashModes
     * @param flashMode
     * @return returns true if flashModes aren't null AND they contain the flashMode,
     * else returns false
     */
    private boolean isValidFlashMode(List<String> flashModes, String flashMode)
    {
        if (flashModes != null && flashModes.contains(flashMode)) {
            return true;
        }
        return false;
    }

    public Camera getCamera()
    {
        return camera;
    }


}
