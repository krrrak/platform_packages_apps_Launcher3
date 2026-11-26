/*
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

import java.util.function.Consumer;

/**
 * ArkUI's SmallWindowSwipePreview right-hand target, adapted to Moment's existing nav spring.
 * Source: ArkUI-Project/android_packages_apps_Launcher3, d37bb71e98876d6ba034dfc1e499a73c84b81ad3,
 * quickstep/src/com/android/quickstep/util/SmallWindowSwipePreview.java (Targets/BlurShape).
 * The split destination and ArkUI-specific task transforms are deliberately not imported.
 */
final class MomentCompactDropTarget extends Drawable {
    private final View mHost;
    private final WindowManager mWindowManager;
    private final Consumer<Boolean> mBlurListener;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private final RectF mCircle = new RectF();
    private final RectF mEnvelope = new RectF();
    private final RectF mTaskBounds = new RectF();
    private final RectF mReleaseBounds = new RectF();
    private final Rect mRoundedBounds = new Rect();
    private final BackgroundBlurDrawable mBlur;
    private final float mDensity;
    private final SpringAnimation mRevealSpring;
    private final SpringAnimation[] mFollowSprings = new SpringAnimation[3];
    private final float[] mMaterial = new float[3];
    private boolean mMaterialInitialized;
    private final int[] mHostLocation = new int[2];
    private float mVisibility;
    private float mReveal;
    private float mProximity;
    private float mRadius;
    private float mIconX;
    private float mIconY;
    private float mIconAlpha;
    private float mReleaseRadius;
    private float mReleaseVisibility;
    private boolean mBlurEnabled;
    private boolean mReleasing;
    private boolean mClosed;
    private boolean mSelected;
    private ValueAnimator mFade;

    MomentCompactDropTarget(View host) {
        mHost = host;
        mDensity = host.getResources().getDisplayMetrics().density;
        mWindowManager = host.getContext().getSystemService(WindowManager.class);
        mBlur = host.getViewRootImpl() == null ? null
                : host.getViewRootImpl().createBackgroundBlurDrawable();
        if (mBlur != null) {
            mBlur.setAlpha(0);
            mBlur.setBlurRadius(0);
            mBlur.setVisible(false, false);
        }
        mRevealSpring = new SpringAnimation(new FloatValueHolder(0f));
        mRevealSpring.setSpring(new SpringForce(0f).setStiffness(700f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        mRevealSpring.setMinimumVisibleChange(.001f);
        mRevealSpring.addUpdateListener((animation, value, velocity) -> {
            if (mClosed || mReleasing) return;
            mReveal = clamp(value);
            update();
        });
        for (int axis = 0; axis < mFollowSprings.length; axis++) {
            final int index = axis;
            final SpringAnimation spring = new SpringAnimation(new FloatValueHolder(0f));
            spring.setSpring(new SpringForce(0f).setStiffness(650f)
                    .setDampingRatio(axis == 2 ? 1f : .95f));
            spring.setMinimumVisibleChange(.5f);
            spring.addUpdateListener((animation, value, velocity) -> {
                if (mClosed || mReleasing) return;
                mMaterial[index] = value;
                update();
            });
            mFollowSprings[axis] = spring;
        }
        mBlurListener = enabled -> {
            if (mClosed) return;
            mBlurEnabled = enabled;
            update();
        };
        setBounds(0, 0, host.getWidth(), host.getHeight());
        host.getOverlay().add(this);
        mWindowManager.addCrossWindowBlurEnabledListener(
                host.getContext().getMainExecutor(), mBlurListener);
    }

    void onMotion(float distance, float proximity, boolean selected) {
        if (mClosed || mReleasing) return;
        mVisibility = smoothstep((distance / Math.max(1, mHost.getHeight()) - .32f) / .10f);
        mProximity = proximity;
        if (mSelected != selected) {
            mSelected = selected;
            mRevealSpring.animateToFinalPosition(selected ? 1f : 0f);
        }
        update();
    }

    void setTaskBounds(RectF bounds) {
        if (mClosed || mReleasing) return;
        mTaskBounds.set(bounds);
        if (!bounds.isEmpty()) {
            final float[] target = {bounds.centerX(), bounds.top, bounds.width()};
            for (int axis = 0; axis < mFollowSprings.length; axis++) {
                if (!mMaterialInitialized) {
                    mMaterial[axis] = target[axis];
                    mFollowSprings[axis].setStartValue(target[axis]);
                }
                mFollowSprings[axis].animateToFinalPosition(target[axis]);
            }
            mMaterialInitialized = true;
        }
        update();
    }

    private void update() {
        if (mClosed) return;
        final float width = mHost.getWidth();
        final float height = mHost.getHeight();
        if (width <= 0 || height <= 0) return;
        if (!mReleasing) {
            final float circleRadius = width * .06f * (1f + .4f * mProximity);
            final float materialX = mMaterialInitialized ? mMaterial[0] : width / 2f;
            final float materialY = mMaterialInitialized ? mMaterial[1] : height;
            mIconX = width * .89f + (materialX - width * .89f)
                    * mVisibility * (.10f + .16f * mProximity);
            mIconY = height * .115f + (materialY - circleRadius * .5f - height * .115f)
                    * mVisibility * .65f;
            mCircle.set(mIconX - circleRadius, mIconY - circleRadius,
                    mIconX + circleRadius, mIconY + circleRadius);
            constrain(mCircle);
            mIconX = mCircle.centerX();
            mIconY = mCircle.centerY();
            final float padding = .025f * width;
            mEnvelope.set(mTaskBounds.isEmpty() ? mCircle : mTaskBounds);
            if (!mTaskBounds.isEmpty() && mMaterialInitialized) {
                final float materialWidth = Math.min(Math.max(1f, mMaterial[2]),
                        mTaskBounds.width());
                mEnvelope.set(materialX - materialWidth / 2f, materialY,
                        materialX + materialWidth / 2f,
                        materialY + materialWidth * mTaskBounds.height() / mTaskBounds.width());
            }
            mEnvelope.inset(-padding, -padding);
            constrain(mEnvelope);
            interpolate(mCircle, mEnvelope, smoothstep(mReveal), mBounds);
            mRadius = circleRadius + (14 * mDensity + padding - circleRadius)
                    * smoothstep(mReveal);
            mIconAlpha = mVisibility * (1f - clamp((mReveal - .15f) / .3f));
        }
        updateBlur();
        invalidateSelf();
    }

    void beginRelease() {
        if (mClosed || mReleasing) return;
        mReleasing = true;
        mRevealSpring.cancel();
        for (SpringAnimation spring : mFollowSprings) spring.cancel();
        mReleaseBounds.set(mBounds);
        mReleaseRadius = mRadius;
        mReleaseVisibility = mVisibility;
        mIconAlpha = 0f;
    }

    void updateRelease(RectF current, float progress) {
        if (mClosed) return;
        beginRelease();
        final float padding = .025f * mHost.getWidth();
        mEnvelope.set(current);
        mEnvelope.inset(-padding, -padding);
        interpolate(mReleaseBounds, mEnvelope, clamp(progress), mBounds);
        mRadius = mReleaseRadius + (24 * mDensity + padding - mReleaseRadius) * clamp(progress);
        mVisibility = mReleaseVisibility * (1f - clamp(progress));
        update();
    }

    void dismiss() {
        if (mClosed || mFade != null) return;
        beginRelease();
        mFade = ValueAnimator.ofFloat(mVisibility, 0f);
        mFade.setDuration(100);
        mFade.addUpdateListener(animation -> {
            mVisibility = (float) animation.getAnimatedValue();
            update();
        });
        mFade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { close(); }
        });
        mFade.start();
    }

    private void constrain(RectF bounds) {
        final float width = mHost.getWidth();
        final float height = mHost.getHeight();
        if (bounds.width() > width) bounds.inset((bounds.width() - width) / 2f, 0f);
        if (bounds.height() > height) bounds.inset(0f, (bounds.height() - height) / 2f);
        bounds.offset(Math.max(0f, -bounds.left) - Math.max(0f, bounds.right - width),
                Math.max(0f, -bounds.top) - Math.max(0f, bounds.bottom - height));
    }

    private void updateBlur() {
        if (mBlur == null) return;
        mHost.getLocationOnScreen(mHostLocation);
        mBlur.setVisible(mVisibility > 0f && mBlurEnabled, false);
        mBlur.setAlpha(Math.round(255 * mVisibility));
        mBlur.setBlurRadius(mBlurEnabled ? Math.round(32 * mDensity) : 0);
        mBlur.setColor((Math.round(32 * mVisibility) << 24) | 0x00FFFFFF);
        mBlur.setCornerRadius(mRadius);
        mBounds.roundOut(mRoundedBounds);
        mRoundedBounds.offset(mHostLocation[0], mHostLocation[1]);
        mBlur.setBounds(mRoundedBounds);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mClosed || mVisibility <= 0f) return;
        if (mBlur != null && mBlurEnabled && canvas.isHardwareAccelerated()) {
            canvas.save();
            canvas.translate(-mHostLocation[0], -mHostLocation[1]);
            mBlur.draw(canvas);
            canvas.restore();
        } else {
            mPaint.setColor(0xFFFFFFFF);
            mPaint.setAlpha(Math.round(44 * mVisibility));
            mPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mPaint);
        }
        mPaint.setColor(0xFFFFFFFF);
        mPaint.setAlpha(Math.round(55 * mVisibility));
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(.65f * mDensity);
        canvas.drawRoundRect(mBounds, mRadius, mRadius, mPaint);
        mPaint.setAlpha(Math.round(255 * mIconAlpha));
        mPaint.setStrokeWidth(1.3f * mDensity);
        final RectF icon = new RectF(mIconX - 5 * mDensity, mIconY - 6.5f * mDensity,
                mIconX + 5 * mDensity, mIconY + 6.5f * mDensity);
        canvas.drawRoundRect(icon, 2 * mDensity, 2 * mDensity, mPaint);
        icon.offset(-3 * mDensity, 3 * mDensity);
        canvas.drawRoundRect(icon, 2 * mDensity, 2 * mDensity, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    void close() {
        if (mClosed) return;
        mClosed = true;
        mRevealSpring.cancel();
        for (SpringAnimation spring : mFollowSprings) spring.cancel();
        if (mFade != null) mFade.cancel();
        mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        if (mBlur != null) {
            mBlur.setAlpha(0);
            mBlur.setBlurRadius(0);
            mBlur.setVisible(false, false);
        }
        mHost.getOverlay().remove(this);
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
    private static float smoothstep(float value) {
        final float p = clamp(value);
        return p * p * (3f - 2f * p);
    }
    private static void interpolate(RectF from, RectF to, float p, RectF out) {
        out.set(from.left + (to.left - from.left) * p,
                from.top + (to.top - from.top) * p,
                from.right + (to.right - from.right) * p,
                from.bottom + (to.bottom - from.bottom) * p);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
