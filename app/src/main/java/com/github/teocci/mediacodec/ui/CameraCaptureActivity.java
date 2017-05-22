/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.teocci.mediacodec.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.view.MotionEventCompat;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Timer;
import java.util.TimerTask;

import com.github.teocci.libmediacodec.encoder.MicrophoneEncoder;
import com.github.teocci.libmediacodec.encoder.SessionConfig;
import com.github.teocci.libmediacodec.encoder.TextureMovieEncoder;
import com.github.teocci.libmediacodec.ui.AspectFrameLayout;
import com.github.teocci.mediacodec.views.DonutProgress;
import com.github.teocci.libmediacodec.ui.ImmersiveActivity;
import com.github.teocci.libmediacodec.utils.AppCameraManager;
import com.github.teocci.libmediacodec.utils.CameraUtils;
import com.github.teocci.mediacodec.CameraSurfaceRenderer;
import com.github.teocci.mediacodec.R;

/**
 * Shows the camera preview on screen while simultaneously recording it to a .mp4 file.
 * <p>
 * Every time we receive a frame from the camera, we need to:
 * <ul>
 * <li>Render the frame to the SurfaceView, on GLSurfaceView's renderer thread.
 * <li>Render the frame to the mediacodec's input surface, on the encoder thread, if
 * recording is enabled.
 * </ul>
 * <p>
 * At any given time there are four things in motion:
 * <ol>
 * <li>The UI thread, embodied by this Activity.  We must respect -- or work around -- the
 * app lifecycle changes.  In particular, we need to release and reacquire the Camera
 * so that, if the user switches away from us, we're not preventing another app from
 * using the camera.
 * <li>The Camera, which will busily generate preview frames once we hand it a
 * SurfaceTexture.  We'll get notifications on the main UI thread unless we define a
 * Looper on the thread where the SurfaceTexture is created (the GLSurfaceView renderer
 * thread).
 * <li>The video encoder thread, embodied by TextureMovieEncoder.  This needs to share
 * the Camera preview external texture with the GLSurfaceView renderer, which means the
 * EGLContext in this thread must be created with a reference to the renderer thread's
 * context in hand.
 * <li>The GLSurfaceView renderer thread, embodied by CameraSurfaceRenderer.  The thread
 * is created for us by GLSurfaceView.  We don't get callbacks for pause/resume or
 * thread startup/shutdown, though we could generate messages from the Activity for most
 * of these things.  The EGLContext created on this thread must be shared with the
 * video encoder, and must be used to create a SurfaceTexture that is used by the
 * Camera.  As the creator of the SurfaceTexture, it must also be the one to call
 * updateTexImage().  The renderer thread is thus at the center of a multi-thread nexus,
 * which is a bit awkward since it's the thread we have the least control over.
 * </ol>
 * <p>
 * GLSurfaceView is fairly painful here.  Ideally we'd create the video encoder, create
 * an EGLContext for it, and pass that into GLSurfaceView to share.  The API doesn't allow
 * this, so we have to do it the other way around.  When GLSurfaceView gets torn down
 * (say, because we rotated the device), the EGLContext gets tossed, which means that when
 * it comes back we have to re-create the EGLContext used by the video encoder.  (And, no,
 * the "preserve EGLContext on pause" feature doesn't help.)
 * <p>
 * We could simplify this quite a bit by using TextureView instead of GLSurfaceView, but that
 * comes with a performance hit.  We could also have the renderer thread drive the video
 * encoder directly, allowing them to work from a single EGLContext, but it's useful to
 * decouple the operations, and it's generally unwise to perform disk I/O on the thread that
 * renders your UI.
 * <p>
 * We want to access Camera from the UI thread (setup, teardown) and the renderer thread
 * (configure SurfaceTexture, start preview), but the API says you can only access the object
 * from a single thread.  So we need to pick one thread to own it, and the other thread has to
 * access it remotely.  Some things are simpler if we let the renderer thread manage it,
 * but we'd really like to be sure that Camera is released before we leave onPause(), which
 * means we need to make a synchronous call from the UI thread into the renderer thread, which
 * we don't really have full control over.  It's less scary to have the UI thread own Camera
 * and have the renderer call back into the UI thread through the standard Handler mechanism.
 * <p>
 * (The <a href="http://developer.android.com/training/camera/cameradirect.html#TaskOpenCamera">
 * camera docs</a> recommend accessing the camera from a non-UI thread to avoid bogging the
 * UI thread down.  Since the GLSurfaceView-managed renderer thread isn't a great choice,
 * we might want to create a dedicated camera thread.  Not doing that here.)
 * <p>
 * With three threads working simultaneously (plus Camera causing periodic events as frames
 * arrive) we have to be very careful when communicating state changes.  In general we want
 * to send a message to the thread, rather than directly accessing state in the object.
 * <p>
 * &nbsp;
 * <p>
 * To exercise the API a bit, the video encoder is required to survive Activity restarts.  In the
 * current implementation it stops recording but doesn't stop time from advancing, so you'll
 * see a pause in the video.  (We could adjust the timer to make it seamless, or output a
 * "paused" message and hold on that in the recording, or leave the Camera running so it
 * continues to generate preview frames while the Activity is paused.)  The video encoder object
 * is managed as a static property of the Activity.
 */
public class CameraCaptureActivity extends ImmersiveActivity
        implements SurfaceTexture.OnFrameAvailableListener, OnItemSelectedListener
{
    private static final String TAG = CameraCaptureActivity.class.getSimpleName();
    private static final boolean VERBOSE = false;

    // Camera filters; must match up with cameraFilterNames in strings.xml
    public static final int FILTER_NONE = 0;
    public static final int FILTER_BLACK_WHITE = 1;
    public static final int FILTER_BLUR = 2;
    public static final int FILTER_SHARPEN = 3;
    public static final int FILTER_EDGE_DETECT = 4;
    public static final int FILTER_EMBOSS = 5;

    private GLSurfaceView glSurfaceView;
    private CameraSurfaceRenderer surfaceRenderer;
    private CameraHandler cameraHandler;
    private MicrophoneEncoder micEncoder;

    // controls button state
    private boolean isRecording;

    // this is static so it survives activity restarts
    private TextureMovieEncoder videoEncoder;
    SessionConfig sessionConfig;
    private double currentAspectRatio;

    private Button doneButton;
    private Button recordButton;
    private DonutProgress donutProgress;
    private Timer timer;
    private RelativeLayout touchInterceptor;
    private RelativeLayout blockerSpinner;
    private ImageView touchIndicator;
    private ImageView moreOptions;
    private ImageButton flashButton;
    private ImageView cancelButton;
    private LinearLayout extrasContainer;

    private static final int cancelMsgDelay = 400; // in ms
    private static final int progressLoopWindow = 15000; // in ms
    private static AppCameraManager cameraManager;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.activity_camera_capture);

        CameraUtils.clearSessionConfig();
        CameraUtils.clearSessionFolders(this, true, true);

        Spinner spinner = (Spinner) findViewById(R.id.filterSpinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.cameraFilterNames, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        // Define a handler that receives camera-control messages from other threads.  All calls
        // to Camera must be made on the same thread.  Note we create this before the renderer
        // thread, so we know the fully-constructed object will be visible.
        sessionConfig = CameraUtils.getSessionConfig(this);
        CameraUtils.clearSessionConfig();

        cameraHandler = new CameraHandler(this);
        videoEncoder = new TextureMovieEncoder();
        isRecording = videoEncoder.isRecording();


        try {
            micEncoder = new MicrophoneEncoder(sessionConfig);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        glSurfaceView = (GLSurfaceView) findViewById(R.id.cameraPreview_surfaceView);
        glSurfaceView.setEGLContextClientVersion(2);     // select GLES 2.0
        surfaceRenderer = new CameraSurfaceRenderer(cameraHandler, sessionConfig, videoEncoder);
        glSurfaceView.setRenderer(surfaceRenderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        cameraManager = new AppCameraManager(this, sessionConfig);
        setUpUi();
        Log.d(TAG, "onCreate complete: " + this);
    }

    public void setUpUi()
    {
        blockerSpinner = (RelativeLayout) findViewById(R.id.blocker);
        blockerSpinner.setVisibility(View.GONE);

        touchIndicator = (ImageView) findViewById(R.id.touchIndicator);
        touchInterceptor = (RelativeLayout) findViewById(R.id.touch_interceptor);
        touchInterceptor.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                touchIndicator.setImageResource(R.drawable.white_circle);
                touchIndicator.setVisibility(View.VISIBLE);
                final int X = (int) event.getRawX();
                final int Y = (int) event.getRawY();

                RelativeLayout.LayoutParams lParams = (RelativeLayout.LayoutParams)
                        touchIndicator.getLayoutParams();
                lParams.leftMargin = X - touchIndicator.getWidth() / 2;
                lParams.topMargin = Y - touchIndicator.getHeight() / 2;
                touchIndicator.setLayoutParams(lParams);
                touchIndicator.invalidate();

                ScaleAnimation scaleUpAnimation = new ScaleAnimation(
                        0, 1, 0, 1, Animation.RELATIVE_TO_SELF, (float) 0.5,
                        Animation.RELATIVE_TO_SELF, (float) 0.5);

                scaleUpAnimation.setDuration(350);
                scaleUpAnimation.setAnimationListener(new Animation.AnimationListener()
                {
                    @Override
                    public void onAnimationStart(Animation animation)
                    {

                    }

                    @Override
                    public void onAnimationEnd(Animation animation)
                    {
                        touchIndicator.postDelayed(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                touchIndicator.setVisibility(View.GONE);
                            }
                        }, 100);

                    }

                    @Override
                    public void onAnimationRepeat(Animation animation)
                    {

                    }
                });
                touchIndicator.startAnimation(scaleUpAnimation);
                return false;
            }
        });

//            touchInterceptor.setVisibility(View.GONE);

        recordButton = (Button) findViewById(R.id.recordButton);

        extrasContainer = (LinearLayout) findViewById(R.id.settings_container);
        moreOptions = (ImageView) findViewById(R.id.icon_more);
        moreOptions.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                moreOptions.setSelected(!moreOptions.isSelected());
                if (moreOptions.isSelected()) {
                    extrasContainer.setVisibility(View.VISIBLE);
                } else {
                    extrasContainer.setVisibility(View.GONE);
                }

            }
        });

//            recordButton .setOnClickListener(mRecordButtonClickListener);
        setUpTouchInterceptor();

        setUpHeaders();
        setUpFlashButton();
        setUpProgressIndicator();

//        setupFilterSpinner();
        setupCameraFlipper();
    }

    private void setupCameraFlipper()
    {
        View flipper = findViewById(R.id.cameraFlipper);
        if (Camera.getNumberOfCameras() == 1) {
            flipper.setVisibility(View.GONE);
        } else {
            flipper.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    cameraManager.requestOtherCamera();
                    onPauseCameraSetup();
                    onResumeCameraSetup();
                }
            });
        }
    }

    private void setUpTouchInterceptor()
    {
        recordButton.setOnTouchListener(new View.OnTouchListener()
        {
            private long lastRecordingRequestedTime = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                int action = MotionEventCompat.getActionMasked(event);
                boolean retVal;
                switch (action) {
                    case (MotionEvent.ACTION_DOWN):
                        Log.d(TAG, "Action was DOWN");
                        lastRecordingRequestedTime = System.currentTimeMillis();
                        startRecording();
                        retVal = true;
                        break;
                    case (MotionEvent.ACTION_UP):
                        Log.d(TAG, "Action was UP");
                        if (System.currentTimeMillis() - lastRecordingRequestedTime > cancelMsgDelay) {
                            stopRecording();
                        }
                        retVal = true;
                        break;
                    default:
                        retVal = false;
                }
                return retVal;
            }
        });
    }

    public void startRecording()
    {
        Log.d(TAG, "Action was DOWN");
        micEncoder.startRecording();
        recordButton.setBackgroundResource(R.drawable.red_dot_stop);
        isRecording = true;
        cameraManager.changeRecordingState(isRecording);
        glSurfaceView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                // notify the renderer that we want to change the encoder's state
                surfaceRenderer.changeRecordingState(isRecording);
            }
        });
        recordButton.setBackgroundResource(R.drawable.red_dot_stop);
    }

    public void stopRecording()
    {
        isRecording = false;
        micEncoder.stopRecording();
        handleStopRecording();
        resetConfig();
        try {
            micEncoder.reset(sessionConfig);
            surfaceRenderer.resetSessionConfig(sessionConfig);
        } catch (IOException e) {
            e.printStackTrace();
        }

        cameraManager.changeRecordingState(isRecording);
        glSurfaceView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                // notify the renderer that we want to change the encoder's state
                surfaceRenderer.changeRecordingState(isRecording);
            }
        });
    }

    public void handleStopRecording()
    {
        recordButton.setBackgroundResource(R.drawable.red_dot);
        doneButton.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
        cancelButton.setImageResource(R.drawable.ic_delete);
        cancelButton.setColorFilter(getResources().getColor(R.color.color_white));
    }

    private void setUpProgressIndicator()
    {
        donutProgress = (DonutProgress) findViewById(R.id.donut_progress);
        donutProgress.setText(CameraUtils.millisecondToTimeString(0));
        timer = new Timer();
        timer.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (cameraManager != null && cameraManager.isRecording()) {
                            donutProgress.setVisibility(View.VISIBLE);
                            donutProgress.setText(CameraUtils.millisecondToTimeString(
                                    cameraManager.getRecordingTime()));
                            float timeInMlSec = cameraManager.getRecordingTime();
                            float progress = ((timeInMlSec % progressLoopWindow) * 1.0f /
                                    progressLoopWindow) * 100;
                            donutProgress.setProgress(progress);
                        }
                    }
                });
            }
        }, 100, 200);
    }

    private void setUpFlashButton()
    {
        flashButton = (ImageButton) findViewById(R.id.flashButton);
        flashButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (cameraManager != null) {
                    v.setSelected(!v.isSelected());
                    if (v.isSelected()) {
                        flashButton.setImageResource(R.drawable.flash_on);
                    } else {
                        flashButton.setImageResource(R.drawable.flash_off);
                    }

                    cameraManager.toggleFlashMode();
                }
            }
        });
    }

    private void setUpHeaders()
    {
        cancelButton = (ImageView) findViewById(R.id.cancle_button);
        cancelButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (doneButton.getVisibility() == View.VISIBLE) {
                    showCancleAlert();
                } else {
                    finish();
                }
            }
        });

        doneButton = (Button) findViewById(R.id.doneButton);
        doneButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                AsyncStitcherTask stitcherTask = new AsyncStitcherTask(CameraCaptureActivity.this);
                stitcherTask.execute("AsyncStitcherTask Task");
                doneButton.setVisibility(View.GONE);

                if (cameraManager != null && cameraManager.isRecording()) {
                    handleStopRecording();
                }

                blockerSpinner.setVisibility(View.VISIBLE);
                donutProgress.setProgress(0);
                donutProgress.setText(CameraUtils.millisecondToTimeString(0));
                if (cameraManager != null) {
                    cameraManager.resetRecordingTime();
                }

                cancelButton.setVisibility(View.INVISIBLE);

            }
        });
    }


    private class AsyncStitcherTask extends AsyncTask<String, Integer, Boolean>
    {

        WeakReference<CameraCaptureActivity> weakActivity;
        Context mContext;

        AsyncStitcherTask(CameraCaptureActivity activity)
        {
            weakActivity = new WeakReference<>(activity);
            mContext = activity.getApplicationContext();

        }

        @Override
        protected Boolean doInBackground(String... params)
        {
            final File inputDir = new File(mContext.getExternalFilesDir(null),
                    SessionConfig.sessionFolder);
            final File outDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM);

            CameraUtils.stichVideos(
                    mContext, inputDir.getPath(), outDir.getPath());
            return true;
        }

        @Override
        protected void onPostExecute(Boolean aBoolean)
        {
            super.onPostExecute(aBoolean);
            CameraCaptureActivity activity = weakActivity.get();
            if (activity != null) {
                blockerSpinner.setVisibility(View.GONE);
            }
        }
    }

    private void showCancleAlert()
    {
        new AlertDialog.Builder(this)
                .setTitle("Delete video ...")
                .setMessage("Are you sure you want to delete video ?")
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface dialog, int which)
                    {
                        // continue with delete
                        donutProgress.setProgress(0);
                        donutProgress.setText(CameraUtils.millisecondToTimeString(0));
                        if (cameraManager != null) {
                            if (cameraManager.isRecording()) {
                                cameraManager.stopRecording();
                            }
                            cameraManager.resetRecordingTime();
                        }
                        cancelButton.setVisibility(View.INVISIBLE);
                        doneButton.setVisibility(View.GONE);
                        CameraUtils.clearSessionFolders(donutProgress.getContext(), true, false);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener()
                {
                    public void onClick(DialogInterface dialog, int which)
                    {
                        dialog.dismiss();
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    @Override
    protected void onResume()
    {
        Log.d(TAG, "onResume -- acquiring camera");
        super.onResume();
        onResumeCameraSetup();
    }


    void resetCameraSetup()
    {
//        onPauseCameraSetup();
//        onResumeCameraSetup();
    }

    private void onResumeCameraSetup()
    {
        String previewFacts = cameraManager.openCamera(sessionConfig.getVideoResolutionWidth(),
                sessionConfig.getVideoResolutionHeight());      // updates mCameraPreviewWidth/Height


        TextView text = (TextView) findViewById(R.id.cameraParams_text);
        text.setText(previewFacts);

        // Set the preview aspect ratio.
        AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.cameraPreview_afl);
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            cameraManager.getCamera().setDisplayOrientation(90);
            currentAspectRatio = (double) cameraManager.getCameraPreviewHeight() /
                    cameraManager.getCameraPreviewWidth();
            layout.setAspectRatio(currentAspectRatio);
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            cameraManager.getCamera().setDisplayOrientation(0);
            currentAspectRatio = (double) cameraManager.getCameraPreviewWidth() /
                    cameraManager.getCameraPreviewHeight();
            layout.setAspectRatio(currentAspectRatio);
        }

        glSurfaceView.onResume();
        glSurfaceView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                surfaceRenderer.setCameraPreviewSize(cameraManager.getCameraPreviewWidth(),
                        cameraManager.getCameraPreviewHeight());
            }
        });
        Log.d(TAG, "onResume complete: " + this);
    }

    private void onPauseCameraSetup()
    {
        cameraManager.releaseCamera();
        glSurfaceView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                // Tell the renderer that it's about to be paused so it can clean up.
                surfaceRenderer.notifyPausing();
            }
        });
        glSurfaceView.onPause();
        Log.d(TAG, "onPause complete");
    }

    @Override
    protected void onPause()
    {
        Log.d(TAG, "onPause -- releasing camera");
        super.onPause();
        onPauseCameraSetup();
    }

    @Override
    protected void onDestroy()
    {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        cameraHandler.invalidateHandler();     // paranoia
        CameraUtils.clearSessionFolders(this, true, true);
    }

    @Override
    public void finish()
    {
        setResult();
        super.finish();

    }

    void setResult()
    {
        setResult(Activity.RESULT_OK, new Intent());
    }

    // spinner selected
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id)
    {
        Spinner spinner = (Spinner) parent;
        final int filterNum = spinner.getSelectedItemPosition();

        // Hide text
        ((TextView) view).setText(null);

        Log.d(TAG, "onItemSelected: " + filterNum);
        glSurfaceView.queueEvent(new Runnable()
        {
            @Override
            public void run()
            {
                // notify the renderer that we want to change the encoder's state
                surfaceRenderer.changeFilterMode(filterNum);
            }
        });
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {}

    private void resetConfig()
    {
        sessionConfig = CameraUtils.getSessionConfig(this);
        CameraUtils.clearSessionConfig();
        try {
            cameraManager.reset(sessionConfig);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     */
    private void handleSetSurfaceTexture(SurfaceTexture st)
    {
        st.setOnFrameAvailableListener(this);
        try {
            cameraManager.getCamera().setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        cameraManager.getCamera().startPreview();
    }

    private void handleSurfaceChanged(double aspectRatio)
    {
        if (currentAspectRatio != aspectRatio) {
            resetCameraSetup();
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st)
    {
        // The SurfaceTexture uses this to signal the availability of a new frame.  The
        // thread that "owns" the external texture associated with the SurfaceTexture (which,
        // by virtue of the context being shared, *should* be either one) needs to call
        // updateTexImage() to latch the buffer.
        //
        // Once the buffer is latched, the GLSurfaceView thread can signal the encoder thread.
        // This feels backward -- we want recording to be prioritized over rendering -- but
        // since recording is only enabled some of the time it's easier to do it this way.
        //
        // Since GLSurfaceView doesn't establish a Looper, this will *probably* execute on
        // the main UI thread.  Fortunately, requestRender() can be called from any thread,
        // so it doesn't really matter.
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        glSurfaceView.requestRender();
    }

    /**
     * Handles camera operation requests from other threads.  Necessary because the Camera
     * must only be accessed from one thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.  Messages are
     * sent from other threads, using sendMessage().
     */
    public static class CameraHandler extends Handler
    {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;
        public static final int MSG_SURFACE_CHANGED = 1;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<CameraCaptureActivity> mWeakActivity;

        public CameraHandler(CameraCaptureActivity activity)
        {
            mWeakActivity = new WeakReference<CameraCaptureActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        public void invalidateHandler()
        {
            mWeakActivity.clear();
        }

        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage)
        {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            CameraCaptureActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                case MSG_SURFACE_CHANGED:
                    activity.handleSurfaceChanged((double) (inputMessage.obj));
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}
