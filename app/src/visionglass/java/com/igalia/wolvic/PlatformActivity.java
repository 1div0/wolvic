/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic;

import android.annotation.SuppressLint;
import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ToggleButton;

import androidx.activity.ComponentActivity;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;

import com.huawei.usblib.DisplayMode;
import com.huawei.usblib.DisplayModeCallback;
import com.huawei.usblib.OnConnectionListener;
import com.huawei.usblib.VisionGlass;
import com.igalia.wolvic.ui.widgets.Windows;
import com.igalia.wolvic.utils.SystemUtils;

import java.util.ArrayList;
import java.util.Arrays;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PlatformActivity extends ComponentActivity implements SensorEventListener, OnConnectionListener {
    static String LOGTAG = SystemUtils.createLogtag(PlatformActivity.class);

    public static final String HUAWEI_USB_PERMISSION = "com.huawei.usblib.USB_PERMISSION";

    private boolean mWasImuStarted;
    private boolean mIsAskingForPermission;
    private DisplayManager mDisplayManager;
    private Display mPresentationDisplay;
    private VisionGlassPresentation mActivePresentation;

    @SuppressWarnings("unused")
    public static boolean filterPermission(final String aPermission) {
        return false;
    }

    public static boolean isNotSpecialKey(KeyEvent event) {
        return true;
    }

    public static boolean isPositionTrackingSupported() {
        // Vision Glass is a 3DoF device.
        return false;
    }

    protected Intent getStoreIntent() {
        // Dummy implementation.
        return null;
    }

    private final ArrayList<Runnable> mPendingEvents = new ArrayList<>();
    private SensorManager mSensorManager;

    final Object mRenderLock = new Object();

    private final Runnable activityDestroyedRunnable = () -> {
        synchronized (mRenderLock) {
            activityDestroyed();
            mRenderLock.notifyAll();
        }
    };

    private final Runnable activityPausedRunnable = () -> {
        synchronized (mRenderLock) {
            activityPaused();
            mRenderLock.notifyAll();
        }
    };
    private final Runnable activityResumedRunnable = this::activityResumed;

    private interface PhoneUIButtonsCallback {
        void onButtonClicked(VRBrowserActivity activity);
    };

    /**
     * Use this method to run callbacks for phone UI buttons. It provides access to the Windows
     * object in VRBrowserActivity. It's a bit ugly but it's the best we can do with the current
     * architecture where there are multiple PlatformActivity's.
    */
    private void runPhoneUICallback(PhoneUIButtonsCallback callback) {
        Context context = getApplicationContext();
        assert context instanceof VRBrowserApplication;
        assert ((VRBrowserApplication)context).getCurrentActivity() instanceof VRBrowserActivity;

        callback.onButtonClicked((VRBrowserActivity)((VRBrowserApplication)context).getCurrentActivity());
    }

    private final BroadcastReceiver mUsbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOGTAG, "USB permission broadcast. IMU started: " + mWasImuStarted +
                    "; waiting for permission: " + mIsAskingForPermission + "; intent: " + intent.toString());

            mIsAskingForPermission = false;
            initVisionGlass();
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(LOGTAG, "PlatformActivity onCreate");
        super.onCreate(savedInstanceState);

        mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        // Alternatively: android.hardware.usb.action.USB_DEVICE_ATTACHED, USB_DEVICE_DETACHED.
        IntentFilter usbPermissionFilter = new IntentFilter();
        usbPermissionFilter.addAction(HUAWEI_USB_PERMISSION);
        registerReceiver(mUsbPermissionReceiver, usbPermissionFilter);

        VisionGlass.getInstance().init(getApplication());
        VisionGlass.getInstance().setOnConnectionListener(this);
        initVisionGlass();
    }

    @Override
    public void onConnectionChange(boolean b) {
        if (VisionGlass.getInstance().isConnected()) {
            Log.d(LOGTAG, "onConnectionChange: Device connected");
            initVisionGlass();
        } else {
            Log.d(LOGTAG, "onConnectionChange: Device disconnected");
            if (mWasImuStarted) {
                Log.d(LOGTAG, "onConnectionChange: Finish activity");
                finish();
            }
        }
    }

    private void initVisionGlass() {
        Log.d(LOGTAG, "initVisionGlass");

        if (mWasImuStarted) {
            Log.d(LOGTAG, "Duplicated call to init the Vision Glass system");
            updateDisplays();
            return;
        }

        if (!VisionGlass.getInstance().isConnected()) {
            Log.d(LOGTAG, "Glasses not connected yet");
            return;
        }

        if (!VisionGlass.getInstance().hasUsbPermission()) {
            if (!mIsAskingForPermission) {
                Log.d(LOGTAG, "Asking for USB permission");
                mIsAskingForPermission = true;
                VisionGlass.getInstance().requestUsbPermission();
            }
            return;
        }

        Log.d(LOGTAG, "Starting IMU");

        mWasImuStarted = true;
        VisionGlass.getInstance().startImu((w, x, y, z) -> queueRunnable(() -> setHead(x, y, z, w)));

        VisionGlass.getInstance().setDisplayMode(DisplayMode.vr3d, new DisplayModeCallback() {
            @Override
            public void onSuccess(DisplayMode displayMode) {
                Log.d(LOGTAG, "Successfully switched to 3D mode");
                updateDisplays();
            }

            @Override
            public void onError(String s, int i) {
                Log.d(LOGTAG, "Error " + i + "; failed to switch to 3D mode: " + s);
                mWasImuStarted = false;
                updateDisplays();
            }
        });

        setContentView(R.layout.visionglass_layout);

        View touchpad = findViewById(R.id.touchpad);
        touchpad.setOnClickListener(v -> {
            // We don't really need the coordinates of the click because we use the position
            // of the aim in the 3D environment.
            queueRunnable(() -> touchEvent(false, 0, 0));
        });

        touchpad.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    // We don't really need the coordinates of the click because we use the position
                    // of the aim in the 3D environment.
                    queueRunnable(() -> touchEvent(true, 0, 0));
                    break;
                case MotionEvent.ACTION_UP:
                    // We'd emit the touchEvent in the onClick listener of the view. This way both
                    // user and system activated clicks (e.g. a11y) will work.
                    view.performClick();
                    break;
                default:
                    return false;
            }
            return true;
        });

        ImageButton backButton = findViewById(R.id.back_button);
        ImageButton homeButton = findViewById(R.id.home_button);

        backButton.setOnClickListener(v -> onBackPressed());
        homeButton.setOnClickListener(v -> runPhoneUICallback((activity) -> {
            Windows windows = activity.getWindows();
            if (windows == null) {
                Log.e(LOGTAG, "Cannot load homepage because Windows object is null");
                return;
            }
            windows.getFocusedWindow().loadHome();
        }));

        ToggleButton headlockButton = findViewById(R.id.headlock_toggle_button);
        headlockButton.setOnClickListener(v -> runPhoneUICallback((activity) -> {
            activity.setHeadLockEnabled(headlockButton.isChecked());
        }));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Show the app
        if (getLifecycle().getCurrentState() == Lifecycle.State.RESUMED) {
            updateDisplays();
        }
    }

    // SensorEventListener overrides
    @Override
    public void onSensorChanged(SensorEvent event) {
        // retrieve the device orientation from sensorevent in the form of quaternion
        if (event.sensor.getType() != Sensor.TYPE_GAME_ROTATION_VECTOR)
            return;

        float[] quaternion = new float[4];
        SensorManager.getQuaternionFromVector(quaternion, event.values);
        // The quaternion is returned in the form [w, x, z, y] but we use it as [x, y, z, w].
        // See https://developer.android.com/reference/android/hardware/Sensor#TYPE_ROTATION_VECTOR
        queueRunnable(() -> setControllerOrientation(quaternion[1], quaternion[3], quaternion[2], quaternion[0]));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent aEvent) {
        if (aEvent.getActionIndex() != 0) {
            Log.e(LOGTAG,"aEvent.getActionIndex()=" + aEvent.getActionIndex());
            return false;
        }

        if (aEvent.getAction() != MotionEvent.ACTION_HOVER_MOVE) {
            return false;
        }

        final float xx = aEvent.getX(0);
        final float yy = aEvent.getY(0);
        queueRunnable(() -> touchEvent(false, xx, yy));
        return true;
    }

    @Override
    protected void onPause() {
        Log.d(LOGTAG, "PlatformActivity onPause");
        super.onPause();

        // This check is needed to prevent a crash when pausing before 3D mode has started.
        if (mWasImuStarted) {
            synchronized (mRenderLock) {
                queueRunnable(activityPausedRunnable);
                try {
                    mRenderLock.wait();
                } catch(InterruptedException e) {
                    Log.e(LOGTAG, "activityPausedRunnable interrupted: " + e);
                }
            }
        }
        if (mActivePresentation != null)
            mActivePresentation.mGLView.onPause();

        // Unregister from the display manager.
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        mSensorManager.unregisterListener(this);
    }

    @Override
    protected void onResume() {
        Log.d(LOGTAG, "PlatformActivity onResume");
        super.onResume();

        // Register to receive events from the display manager.
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_NORMAL);

        if (VisionGlass.getInstance().isConnected() && VisionGlass.getInstance().hasUsbPermission()) {
            updateDisplays();
        }

        if (mActivePresentation != null && mActivePresentation.mGLView != null)
            mActivePresentation.mGLView.onResume();

        queueRunnable(activityResumedRunnable);
        setImmersiveSticky();
    }

    @Override
    protected void onDestroy() {
        Log.d(LOGTAG, "PlatformActivity onDestroy");
        super.onDestroy();
        unregisterReceiver(mUsbPermissionReceiver);
        synchronized (mRenderLock) {
            if (queueRunnable(activityDestroyedRunnable)) {
                try {
                    mRenderLock.wait();
                } catch (InterruptedException e) {
                    Log.e(LOGTAG, "activityDestroyedRunnable interrupted: " + e.toString());
                }
            }
        }
    }

    void setImmersiveSticky() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow()
                    .getDecorView()
                    .setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    boolean queueRunnable(@NonNull Runnable aRunnable) {
        if (mActivePresentation != null) {
            mActivePresentation.mGLView.queueEvent(aRunnable);
            return true;
        } else {
            synchronized (mPendingEvents) {
                mPendingEvents.add(aRunnable);
            }
            if (mActivePresentation != null) {
                notifyPendingEvents();
                return true;
            }
        }
        return false;
    }

    private void notifyPendingEvents() {
        synchronized (mPendingEvents) {
            for (Runnable runnable: mPendingEvents) {
                mActivePresentation.mGLView.queueEvent(runnable);
            }
            mPendingEvents.clear();
        }
    }

    private void updateDisplays() {
        Display[] displays = mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            mPresentationDisplay = null;
            Log.d(LOGTAG, "updateDisplays: could not find a Presentation display");
            return;
        }

        Log.d(LOGTAG, "updateDisplays: found Presentation display");
        if (mPresentationDisplay != displays[0]) {
            if (mActivePresentation != null) {
                mActivePresentation.mGLView.onPause();
            }
            mActivePresentation = null;
        }
        mPresentationDisplay = displays[0];

        runOnUiThread(this::showPresentation);
    }

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    Log.d(LOGTAG, "display listener: onDisplayAdded displayId = " + displayId);
                    updateDisplays();
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    Log.d(LOGTAG, "display listener: onDisplayChanged displayId = " + displayId);
                    updateDisplays();
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    Log.d(LOGTAG, "display listener: onDisplayRemoved displayId = " + displayId);
                    updateDisplays();
                }
            };

    private final DialogInterface.OnDismissListener mOnDismissListener =
            new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    mActivePresentation = null;
                }
            };

    private void showPresentation() {
        if (mActivePresentation != null) {
            return;
        }
        if (mPresentationDisplay == null) {
            Log.e(LOGTAG, "No suitable displays found");
            return;
        }
        VisionGlassPresentation presentation = new VisionGlassPresentation(this, mPresentationDisplay);
        Display.Mode [] modes = mPresentationDisplay.getSupportedModes();
        Log.d(LOGTAG, "showPresentation supported modes: " + Arrays.toString(modes));
        presentation.setPreferredDisplayMode(modes[0].getModeId());
        presentation.show();
        presentation.setOnDismissListener(mOnDismissListener);
        mActivePresentation = presentation;
    }

    private final class VisionGlassPresentation extends Presentation {

        private GLSurfaceView mGLView;

        public VisionGlassPresentation(Context context, Display display) {
            super(context, display);
        }

        /**
         * Sets the preferred display mode id for the presentation.
         */
        public void setPreferredDisplayMode(int modeId) {
            Log.d(LOGTAG, "VisionGlassPresentation setPreferredDisplayMode: " + modeId);
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.preferredDisplayModeId = modeId;
            getWindow().setAttributes(params);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            // Be sure to call the super class.
            super.onCreate(savedInstanceState);
            Log.d(LOGTAG, "VisionGlassPresentation onCreate");

            // Inflate the layout.
            setContentView(R.layout.visionglass_presentation_layout);

            mGLView = findViewById(R.id.gl_presentation_view);
            mGLView.setEGLContextClientVersion(3);
            mGLView.setEGLConfigChooser(8, 8, 8, 0, 16, 0);
            mGLView.setPreserveEGLContextOnPause(true);

            mGLView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                    Log.d(LOGTAG, "VisionGlassPresentation onSurfaceCreated");
                    activityCreated(getAssets());
                    notifyPendingEvents();
                }

                @Override
                public void onSurfaceChanged(GL10 gl, int width, int height) {
                    Log.d(LOGTAG, "VisionGlassPresentation onSurfaceChanged");
                    updateViewport(width, height);
                }

                @Override
                public void onDrawFrame(GL10 gl) {
                    drawGL();
                }
            });
        }
    }

    @Keep
    @SuppressWarnings("unused")
    private void setRenderMode(final int aMode) {
        runOnUiThread(this::setImmersiveSticky);
    }

    private native void activityCreated(Object aAssetManager);
    private native void updateViewport(int width, int height);
    private native void activityPaused();
    private native void activityResumed();
    private native void activityDestroyed();
    private native void drawGL();
    private native void touchEvent(boolean aDown, float aX, float aY);
    private native void setHead(double x, double y, double z, double w);
    private native void setControllerOrientation(double x, double y, double z, double w);
}
