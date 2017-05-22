package com.github.teocci.libmediacodec.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import static com.github.teocci.libmediacodec.utils.CameraUtils.isKitKat;


/**
 * @hide
 */
public abstract class ImmersiveActivity extends Activity
{
    public static final String TAG = ImmersiveActivity.class.getSimpleName();
    private boolean isImmersiveMode = true;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
//        //Remove notification bar
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
//        View decorView = getWindow().getDecorView();
//        decorView.setSystemUiVisibility(0);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        hideSystemUi();
    }

    /**
     * If true, use Android's Immersive Mode. If false
     * use the "Lean Back" experience.
     *
     * @param useIt
     * @see <a href="https://developer.android.com/design/patterns/fullscreen.html">Android Full Screen docs</a>
     */
    public void setImmersiveMode(boolean useIt)
    {
        isImmersiveMode = useIt;
    }

    private void hideSystemUi()
    {
        if (!isKitKat() || !isImmersiveMode) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else if (isImmersiveMode) {
            setKitKatWindowFlags();
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void onWindowFocusChanged(boolean hasFocus)
    {
        super.onWindowFocusChanged(hasFocus);
        if (isKitKat() && hasFocus && isImmersiveMode) {
            setKitKatWindowFlags();
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void setKitKatWindowFlags()
    {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }
}
