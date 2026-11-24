/*
 * SPDX-FileCopyrightText: 2026 Aura
 * SPDX-FileCopyrightText: 2026 The ArkUI Project
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.android.launcher3.uioverrides.QuickstepLauncher;

import java.util.concurrent.atomic.AtomicBoolean;

/** ArkUI's navigation target UI with Aura's veil handoff, committing to WM-owned Moment Compact. */
final class MomentCompactSwipeController {
    private static final String TAG = "MomentCompactSwipe";
    private final Context mContext;
    private final float mDensity;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mRemoveCover = this::removeCover;
    private int mTaskId = -1;
    private int mDisplayId;
    private boolean mAllowed;
    private boolean mRejected;
    private boolean mCandidate;
    private boolean mCommitted;
    private MomentCompactDropTarget mDropTarget;
    private final RectF mCurrentTaskBounds = new RectF();
    private FrameLayout mCover;
    private FrameLayout mCard;
    private ImageView mCoverIcon;
    private Drawable mVeilIcon;
    private int mVeilColor;
    private final RectF mCoverRect = new RectF();
    private float mCoverProgress = 1f;
    private float mCoverRadius;
    private boolean mCoverRequested;
    private boolean mCoverFading;
    private final java.util.ArrayList<Runnable> mCoverContinuations = new java.util.ArrayList<>();
    private WindowManager mCoverManager;
    private QuickstepLauncher mLauncher;
    private TaskInfo mTask;
    private final RectF mTarget = new RectF();

    MomentCompactSwipeController(Context context) {
        mContext = context;
        mDensity = context.getResources().getDisplayMetrics().density;
    }

    void onMotion(MotionEvent event, float downX, float downY, boolean rejected,
            TaskInfo task, QuickstepLauncher launcher) {
        if (mCommitted) return;
        mRejected |= rejected || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        mLauncher = launcher;
        if (mRejected || !eligible(task) || launcher == null) {
            mCandidate = false;
            dismissHint();
            return;
        }
        if (mTaskId != task.taskId) {
            mTaskId = task.taskId;
            mDisplayId = task.displayId;
            mAllowed = false;
            final int taskId = mTaskId;
            final int displayId = mDisplayId;
            UI_HELPER_EXECUTOR.execute(() -> {
                boolean allowed = false;
                try {
                    allowed = WindowManagerGlobal.getWindowManagerService()
                            .canEnterMomentCompact(taskId, displayId);
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, "Compact availability unavailable", e);
                }
                final boolean result = allowed;
                MAIN_EXECUTOR.execute(() -> {
                    if (!mRejected && mTaskId == taskId) mAllowed = result;
                });
            });
        }
        final View root = launcher.getRootView();
        final int width = root.getWidth(), height = root.getHeight();
        if (width <= 0 || height <= width || downY < height - dp(100)) {
            mCandidate = false;
            dismissHint();
            return;
        }
        final float x = event.getX(), y = event.getY();
        final float up = downY - y;
        // ArkUI's resting entry region stays fixed while the material follows the card.
        final float dx = (x / width - .86f) / .43f;
        final float dy = Math.max(0f, y / height - .14f) / .20f;
        final float distance = (float) Math.hypot(dx, dy);
        final float visibility = Math.max(0f, Math.min(1f, (up / height - .32f) / .10f));
        final boolean wasCandidate = mCandidate;
        mCandidate = mAllowed && x >= 0 && x <= width && y >= 0
                && visibility >= .5f && distance <= (wasCandidate ? 1.12f : 1f);
        if (mCandidate && !wasCandidate) {
            root.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !mCandidate) {
            dismissHint();
        } else if (mAllowed && visibility > 0f) {
            if (mDropTarget == null) {
                mDropTarget = new MomentCompactDropTarget(root);
                mDropTarget.setTaskBounds(mCurrentTaskBounds);
            }
            final float proximity = Math.max(0f, Math.min(1f, (1.5f - distance) / .5f));
            mDropTarget.onMotion(up, proximity * proximity * (3f - 2f * proximity), mCandidate);
        } else if (mDropTarget != null) {
            mDropTarget.onMotion(up, 0f, false);
        }
    }

    private boolean eligible(TaskInfo task) {
        return task != null && task.displayId == 0 && task.userId == UserHandle.myUserId()
                && task.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_STANDARD
                && task.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                && task.isResizeable && task.supportsMultiWindow && task.topActivity != null
                && (task.pictureInPictureParams == null
                        || !task.pictureInPictureParams.isAutoEnterEnabled());
    }

    boolean isCandidate() { return mCandidate && !mRejected; }
    boolean isCommitted() { return mCommitted; }
    RectF getTarget() { return new RectF(mTarget); }
    float getLogicalCornerRadius() { return dp(28); }

    boolean commit(RemoteAnimationTarget target, QuickstepLauncher launcher) {
        if (!isCandidate() || target == null || target.taskId != mTaskId
                || !eligible(target.taskInfo) || launcher == null) return false;
        final View root = launcher.getRootView();
        final Insets bars = root.getRootWindowInsets() == null ? Insets.NONE
                : root.getRootWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        final float width = (root.getWidth() - bars.left - bars.right) * .25f;
        final float height = Math.min(width * 16f / 9f,
                root.getHeight() - bars.top - bars.bottom);
        final float left = root.getWidth() - bars.right - width - dp(8);
        final float top = bars.top + dp(8);
        mTarget.set(left, top, left + width, top + height);
        mCoverRadius = getLogicalCornerRadius() * .25f;
        mTask = target.taskInfo;
        mCommitted = true;
        mCoverRect.set(mTarget);
        prepareVeilAssets(mTask);
        if (mDropTarget != null) mDropTarget.beginRelease();
        return true;
    }

    private int dp(float value) { return Math.round(value * mDensity); }

    private GradientDrawable background(int color) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(mCoverRadius);
        return drawable;
    }

    void updateTaskBounds(RectF bounds) {
        if (mCommitted || mRejected) return;
        mCurrentTaskBounds.set(bounds);
        if (mDropTarget != null) mDropTarget.setTaskBounds(bounds);
    }

    private void dismissHint() {
        if (mDropTarget != null) {
            mDropTarget.dismiss();
            mDropTarget = null;
        }
    }

    private int veilFallbackColor(TaskInfo task) {
        final int color = task != null && task.taskDescription != null
                ? task.taskDescription.getBackgroundColor() : Color.TRANSPARENT;
        if (Color.alpha(color) == 255) return color;
        final int uiMode = task == null ? mContext.getResources().getConfiguration().uiMode
                : task.configuration.uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                ? 0xff202124 : 0xfffafafa;
    }

    private int loadVeilColor(TaskInfo task) {
        final int fallback = veilFallbackColor(task);
        if (task == null || task.topActivityInfo == null) return fallback;
        try {
            final Context app = mContext.createPackageContext(task.topActivityInfo.packageName, 0)
                    .createConfigurationContext(task.configuration);
            app.setTheme(task.topActivityInfo.getThemeResource());
            final TypedArray values = app.obtainStyledAttributes(
                    new int[] {android.R.attr.windowBackground});
            final Drawable background;
            try { background = values.getDrawable(0); }
            finally { values.recycle(); }
            final int color = background instanceof ColorDrawable solid ? solid.getColor()
                    : background instanceof GradientDrawable gradient && gradient.getColor() != null
                            ? gradient.getColor().getDefaultColor() : Color.TRANSPARENT;
            return Color.alpha(color) == 255 ? color : fallback;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) {
            return fallback;
        }
    }

    private void prepareVeilAssets(TaskInfo task) {
        mVeilColor = veilFallbackColor(task);
        final int taskId = mTaskId;
        UI_HELPER_EXECUTOR.execute(() -> {
            Drawable icon = null;
            try {
                if (task != null && task.topActivityInfo != null) {
                    icon = task.topActivityInfo.loadIcon(mContext.getPackageManager());
                }
            } catch (RuntimeException error) { Log.w(TAG, "Veil icon unavailable", error); }
            final Drawable result = icon;
            final int color = loadVeilColor(task);
            MAIN_EXECUTOR.execute(() -> {
                if (!mCommitted || taskId != mTaskId) return;
                mVeilIcon = result;
                mVeilColor = color;
                if (mCard != null) mCard.setBackground(background(color));
                if (mCoverIcon != null && result != null) mCoverIcon.setImageDrawable(result);
            });
        });
    }

    /** Aura's veil follows the committed navigation spring, not a second destination animation. */
    void updateCover(RectF rect, float progress, float radius) {
        if (!mCommitted || mCoverFading) return;
        if (mDropTarget != null) {
            mDropTarget.updateRelease(rect, progress);
            if (progress >= 1f) {
                mDropTarget.close();
                mDropTarget = null;
            }
        }
        mCoverRect.set(rect);
        mCoverRadius = radius;
        mCoverProgress = Math.max(0f, Math.min(1f, (progress - .10f) / .20f));
        if (progress >= .10f && !mCoverRequested && mCover == null) {
            captureCover(null, () -> { });
        }
        applyCoverPose();
    }

    private void applyCoverPose() {
        if (mCard == null) return;
        mCard.setPivotX(0f);
        mCard.setPivotY(0f);
        mCard.setTranslationX(mCoverRect.left);
        mCard.setTranslationY(mCoverRect.top);
        final float sx = mCoverRect.width() / Math.max(1f, mTarget.width());
        final float sy = mCoverRect.height() / Math.max(1f, mTarget.height());
        mCard.setScaleX(sx);
        mCard.setScaleY(sy);
        if (mCard.getBackground() instanceof GradientDrawable background) {
            background.setCornerRadius(mCoverRadius / Math.max(.001f, Math.min(sx, sy)));
        }
        if (mCoverIcon != null) mCoverIcon.setScaleY(sx / Math.max(.001f, sy));
        mCard.setAlpha(mCoverProgress);
    }

    void captureCover(RecentsAnimationController controller, Runnable finish) {
        if (controller != null) {
            mCoverRect.set(mTarget);
            mCoverProgress = 1f;
            applyCoverPose();
        }
        if (mCover != null) {
            afterCoverDraw(finish);
            return;
        }
        mCoverContinuations.add(finish);
        if (mCoverRequested) return;
        mCoverRequested = true;
        mMain.postDelayed(mRemoveCover, 4500);
        createLaunchCover();
    }

    private void createLaunchCover() {
        if (!mCommitted || mLauncher == null || mLauncher.isDestroyed()) {
            finishCoverPreparation();
            return;
        }
        try {
            final Context context = mLauncher.createWindowContext(
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG, null);
            mCoverManager = context.getSystemService(WindowManager.class);
            mCover = new FrameLayout(context);
            mCard = new FrameLayout(context);
            mCard.setBackground(background(mVeilColor));
            mCard.setClipToOutline(true);
            mCover.addView(mCard, new FrameLayout.LayoutParams(
                    Math.round(mTarget.width()), Math.round(mTarget.height())));
            final ImageView icon = new ImageView(context);
            mCoverIcon = icon;
            icon.setImageDrawable(mVeilIcon != null ? mVeilIcon
                    : mContext.getPackageManager().getDefaultActivityIcon());
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mCard.addView(icon, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
            applyCoverPose();
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.setFitInsetsTypes(0);
            lp.setTrustedOverlay();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("Moment Compact handoff " + mTaskId);
            mCoverManager.addView(mCover, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to prepare handoff cover", e);
            removeCover();
        }
        finishCoverPreparation();
    }

    private void finishCoverPreparation() {
        final var callbacks = new java.util.ArrayList<>(mCoverContinuations);
        mCoverContinuations.clear();
        for (Runnable callback : callbacks) afterCoverDraw(callback);
    }

    private void afterCoverDraw(Runnable finish) {
        if (mCover == null) {
            finish.run();
            return;
        }
        final FrameLayout cover = mCover;
        final AtomicBoolean resumed = new AtomicBoolean();
        final Runnable proceed = () -> {
            if (resumed.compareAndSet(false, true)) finish.run();
        };
        mMain.postDelayed(proceed, 160);
        cover.postOnAnimation(() -> cover.postOnAnimation(() -> {
            mMain.removeCallbacks(proceed);
            proceed.run();
        }));
    }

    void launch() {
        if (!mCommitted || mRejected) {
            removeCover();
            return;
        }
        final Rect bounds = new Rect();
        mTarget.round(bounds);
        UI_HELPER_EXECUTOR.execute(() -> {
            boolean accepted = false;
            try {
                accepted = WindowManagerGlobal.getWindowManagerService().enterMomentCompact(
                        mTaskId, mDisplayId, bounds, new IRemoteCallback.Stub() {
                            @Override
                            public void sendResult(Bundle result) {
                                MAIN_EXECUTOR.execute(() -> {
                                    if (mCard != null) {
                                        final Rect ready = result.getParcelable("bounds", Rect.class);
                                        if (ready != null) {
                                            mCoverRect.set(ready);
                                            mCoverProgress = 1f;
                                            applyCoverPose();
                                        }
                                        fadeCover();
                                    }
                                });
                            }
                        });
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Compact handoff failed", e);
            }
            if (!accepted) MAIN_EXECUTOR.execute(this::cancel);
        });
    }

    void cancel() {
        mRejected = true;
        mCandidate = false;
        mCommitted = false;
        if (mDropTarget != null) {
            mDropTarget.close();
            mDropTarget = null;
        }
        removeCover();
    }

    private void fadeCover() {
        if (mCover == null || mCoverFading) return;
        mCoverFading = true;
        mMain.removeCallbacks(mRemoveCover);
        final FrameLayout cover = mCover;
        cover.postOnAnimation(() -> {
            if (mCover != cover) return;
            cover.animate().alpha(0f).setDuration(160).setStartDelay(0)
                    .setInterpolator(new PathInterpolator(.4f, 0f, .2f, 1f))
                    .withEndAction(() -> { if (mCover == cover) removeCover(); }).start();
        });
    }

    private void removeCover() {
        mMain.removeCallbacks(mRemoveCover);
        if (mCover != null) {
            mCover.animate().cancel();
            if (mCard != null) mCard.animate().cancel();
            try {
                mCoverManager.removeViewImmediate(mCover);
            } catch (RuntimeException e) {
                Log.d(TAG, "Handoff cover already detached", e);
            }
        }
        mCover = null;
        mCard = null;
        mCoverIcon = null;
        mCoverManager = null;
        mCoverRequested = false;
        mCoverFading = false;
    }
}
