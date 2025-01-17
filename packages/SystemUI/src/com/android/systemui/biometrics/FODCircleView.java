/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Spline;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;

public class FODCircleView extends ImageView {
    private static final String SCREEN_BRIGHTNESS = Settings.System.SCREEN_BRIGHTNESS;
    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final boolean mShouldBoostBrightness;
    private final boolean mTargetUsesInKernelDimming;
    private final Paint mPaintFingerprintBackground = new Paint();
    private final Paint mPaintFingerprint = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager.LayoutParams mPressedParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;
    private final Context mContext;

    private int mCurrentBrightness;
    private int mDreamingOffsetY;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mIsCircleShowing;
    private boolean mIsScreenTurnedOn;
    private boolean mIsAnimating = false;
    private boolean mIsAssistantVisible = false;

    private final Handler mHandler;
    private final ImageView mPressedView;
    private final LockPatternUtils mLockPatternUtils;

    private final Spline mFODiconBrightnessToDimAmountSpline;

    private final IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            mHandler.post(() -> showCircle());
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
        }
    };

    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onTimeChanged() {
            if (!mIsScreenTurnedOn || !mIsDreaming) return;
            if (getVisibility() != View.VISIBLE) return;
            final long now = System.currentTimeMillis() / 60000;
            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            mDreamingOffsetY -= mDreamingMaxOffset;
            updatePosition();
        }

        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateIconDim(false);

            if (!shouldShowOnDoze()) {
                Drawable icon = null;
                if (!dreaming) icon = getResources().getDrawable(
                        R.drawable.fod_icon_default, null);
                setImageDrawable(icon);
                invalidate();
            }
            if (!dreaming) updatePosition();
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                if (isPinOrPattern(KeyguardUpdateMonitor.getCurrentUser()) || !isBouncer) {
                    mIsAssistantVisible = false;
                    show();
                } else {
                    hide();
                }
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (!showing && !mIsDreaming) hide();
        }
    };

    private final ScreenLifecycle mScreenMonitor;
    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            mIsScreenTurnedOn = true;
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
        }

        @Override
        public void onScreenTurningOff() {
            hide();
        }

        @Override
        public void onScreenTurnedOff() {
            mIsScreenTurnedOn = false;
        }
    };

    private final WakefulnessLifecycle mWakefulnessMonitor;
    private final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onStartedWakingUp() {
            if (!mIsScreenTurnedOn &&
                    mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
        }

        @Override
        public void onFinishedGoingToSleep() {
            updateIconDim(true);
        }
    };

    private final TaskStackChangeListener
            mTaskStackChangeListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChangedBackground() {
            try {
                StackInfo stackInfo = ActivityTaskManager.getService().getStackInfo(
                        WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_ASSISTANT);
                if (stackInfo == null && mIsAssistantVisible) {
                        mIsAssistantVisible = false;
                        if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                            mHandler.post(() -> show());
                    }
                    return;
                }
                if (stackInfo != null) mIsAssistantVisible = stackInfo.visible;
                if (mIsAssistantVisible) {
                    mHandler.post(() -> hide());
                }
            } catch (RemoteException ignored) { }
        }
    };

    private class CustomSettingsObserver extends ContentObserver {
        CustomSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    SCREEN_BRIGHTNESS), false, this, UserHandle.USER_ALL);
        }

        void unobserve() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            // if (uri.equals(Settings.System.getUriFor(SCREEN_BRIGHTNESS))) {
            update();
            // }
        }

        void update() {
            int brightness = Settings.System.getInt(
                    mContext.getContentResolver(), SCREEN_BRIGHTNESS, 100);
            if (mCurrentBrightness != brightness) {
                mCurrentBrightness = brightness;
                updateIconDim(false);
            }
        }
    }

    private final CustomSettingsObserver mCustomSettingsObserver;

    @SuppressLint("RtlHardcoded")
    public FODCircleView(Context context) {
        super(context);
        mContext = context;

        setScaleType(ScaleType.CENTER);

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = context.getResources();
        mPaintFingerprint.setColor(res.getColor(R.color.config_fodColor, null));
        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprintBackground.setColor(res.getColor(R.color.config_fodColorBackground, null));
        mPaintFingerprintBackground.setAntiAlias(true);

        float[] icon_dim_amount =
                getFloatArray(res.obtainTypedArray(R.array.config_FODiconDimAmount));
        float[] display_brightness =
                getFloatArray(res.obtainTypedArray(R.array.config_FODiconDisplayBrightness));
        mFODiconBrightnessToDimAmountSpline =
                Spline.createSpline(display_brightness, icon_dim_amount);

        mTargetUsesInKernelDimming = res.getBoolean(com.android.internal.R.bool.config_targetUsesInKernelDimming);

        mWindowManager = context.getSystemService(WindowManager.class);

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mCustomSettingsObserver = new CustomSettingsObserver(mHandler);
        mCustomSettingsObserver.update();

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mPressedParams.copyFrom(mParams);
        mPressedParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;

        mParams.setTitle("Fingerprint on display");
        mPressedParams.setTitle("Fingerprint on display.touched");

        mPressedView = new ImageView(context)  {
            @Override
            protected void onDraw(Canvas canvas) {
                if (mIsCircleShowing) {
                    float half = mSize / 2.0f;
                    canvas.drawCircle(half, half, half, mPaintFingerprint);
                }
                super.onDraw(canvas);
            }
        };
        mPressedView.setImageResource(R.drawable.fod_icon_pressed);

        mWindowManager.addView(this, mParams);

        updatePosition();
        hide();

        mLockPatternUtils = new LockPatternUtils(mContext);

        mWakefulnessMonitor = Dependency.get(WakefulnessLifecycle.class);
        mScreenMonitor = Dependency.get(ScreenLifecycle.class);
        mUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
    }


    private int getDimAlpha() {
        return Math.round(mFODiconBrightnessToDimAmountSpline.interpolate(mCurrentBrightness));
    }

    public void updateIconDim(boolean animate) {
        if (!mIsCircleShowing && mTargetUsesInKernelDimming) {
            if (animate && !mIsAnimating) {
                ValueAnimator anim = new ValueAnimator();
                anim.setIntValues(0, getDimAlpha());
                anim.addUpdateListener(valueAnimator -> {
                    int progress = (Integer) valueAnimator.getAnimatedValue();
                    setColorFilter(Color.argb(progress, 0, 0, 0),
                            PorterDuff.Mode.SRC_ATOP);
                });
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mIsAnimating = false;
                    }
                });
                anim.setDuration(250);
                mIsAnimating = true;
                mHandler.post(anim::start);
            } else if (!mIsAnimating) {
                mHandler.post(() ->
                        setColorFilter(Color.argb(getDimAlpha(), 0, 0, 0),
                        PorterDuff.Mode.SRC_ATOP));
            }
        } else {
            mHandler.post(() -> setColorFilter(Color.argb(0, 0, 0, 0),
                    PorterDuff.Mode.SRC_ATOP));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        mWakefulnessMonitor.addObserver(mWakefulnessObserver);
        mScreenMonitor.addObserver(mScreenObserver);
        mUpdateMonitor.registerCallback(mMonitorCallback);
        ActivityManagerWrapper.getInstance().registerTaskStackListener(
                mTaskStackChangeListener);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        mWakefulnessMonitor.removeObserver(mWakefulnessObserver);
        mScreenMonitor.removeObserver(mScreenObserver);
        mUpdateMonitor.removeCallback(mMonitorCallback);
        ActivityManagerWrapper.getInstance().unregisterTaskStackListener(
                mTaskStackChangeListener);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mIsCircleShowing) {
            float half = mSize / 2.0f;
            canvas.drawCircle(half, half, half, mPaintFingerprintBackground);
        }
        super.onDraw(canvas);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            return true;
        } else return event.getAction() == MotionEvent.ACTION_MOVE;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> mFingerprintInscreenDaemon = null, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void dispatchPress() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        mIsCircleShowing = true;

        setKeepScreenOn(true);

        setDim(true);
        ThreadUtils.postOnBackgroundThread(this::dispatchPress);
        setImageDrawable(null);
        updateIconDim(false);
        updatePosition();
        invalidate();
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        if (mIsDreaming && !shouldShowOnDoze()) {
            setImageDrawable(null);
        } else {
            setImageResource(R.drawable.fod_icon_default);
        }
        invalidate();

        ThreadUtils.postOnBackgroundThread(this::dispatchRelease);
        setDim(false);

        setKeepScreenOn(false);
    }

    public void show() {
        if (!mUpdateMonitor.isScreenOn()) {
            // Keyguard is shown just after screen turning off
            return;
        }

        if (mIsBouncer && !isPinOrPattern(KeyguardUpdateMonitor.getCurrentUser())) {
            // Ignore show calls when Keyguard password screen is being shown
            return;
        }

        if (mIsAssistantVisible) {
            // Don't show when assistant UI is visible
            return;
        }

        updatePosition();
        mCustomSettingsObserver.observe();
        mCustomSettingsObserver.update();

        ThreadUtils.postOnBackgroundThread(this::dispatchShow);
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        setVisibility(View.GONE);
        mCustomSettingsObserver.unobserve();
        hideCircle();
        ThreadUtils.postOnBackgroundThread(this::dispatchHide);
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private void updatePosition() {
        Display defaultDisplay = mContext.getDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        int x, y;
        switch (rotation) {
            case Surface.ROTATION_0:
                x = mPositionX;
                y = mPositionY;
                break;
            case Surface.ROTATION_90:
                x = mPositionY;
                y = mPositionX;
                break;
            case Surface.ROTATION_180:
                x = mPositionX;
                y = size.y - mPositionY - mSize;
                break;
            case Surface.ROTATION_270:
                x = size.x - mPositionY - mSize - mNavigationBarSize;
                y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        mPressedParams.x = mParams.x = x;
        mPressedParams.y = mParams.y = y;

        if (mIsDreaming && !mIsCircleShowing) {
            mParams.y += mDreamingOffsetY;
        }

        mWindowManager.updateViewLayout(this, mParams);

        if (mPressedView.getParent() != null) {
            mWindowManager.updateViewLayout(mPressedView, mPressedParams);
        }
    }

    private void setDim(boolean dim) {
        if (dim) {
            int curBrightness = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 100);
            int dimAmount = 0;

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(curBrightness);
            } catch (RemoteException e) {
                // do nothing
            }

            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 1.0f;
            }

            mPressedParams.dimAmount = dimAmount / 255.0f;
            if (mPressedView.getParent() == null) {
                mWindowManager.addView(mPressedView, mPressedParams);
            } else {
                mWindowManager.updateViewLayout(mPressedView, mPressedParams);
            }
        } else {
            if (mShouldBoostBrightness) {
                mPressedParams.screenBrightness = 0.0f;
            }
            mPressedParams.dimAmount = 0.0f;
            if (mPressedView.getParent() != null) {
                mWindowManager.removeView(mPressedView);
            }
            updateIconDim(true);
        }
    }

    private boolean isPinOrPattern(int userId) {
        int passwordQuality = mLockPatternUtils.getActivePasswordQuality(userId);
        switch (passwordQuality) {
            // PIN
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
            // Pattern
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                return true;
        }

        return false;
    }

    private boolean shouldShowOnDoze() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.FOD_ON_DOZE, 1) == 1;
    }

    private static float[] getFloatArray(TypedArray array) {
        int length = array.length();
        float[] floatArray = new float[length];
        for (int i = 0; i < length; i++) {
            floatArray[i] = array.getFloat(i, Float.NaN);
        }
        array.recycle();
        return floatArray;
    }
}
