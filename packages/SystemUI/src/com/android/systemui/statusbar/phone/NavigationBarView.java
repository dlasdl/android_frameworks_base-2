/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SEARCH_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.isGesturalMode;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.util.Utils.isGesturalModeOnDefaultDisplay;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.DrawableRes;
import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.assist.AssistHandleViewController;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsOnboarding;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.function.Consumer;

public class NavigationBarView extends FrameLayout implements
        NavigationModeController.ModeChangedListener {
    final static boolean DEBUG = false;
    final static String TAG = "StatusBar/NavBarView";

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED = true;

    final static boolean ALTERNATE_CAR_MODE_UI = false;
    private final RegionSamplingHelper mRegionSamplingHelper;
    private final int mNavColorSampleMargin;
    private final SysUiState mSysUiFlagContainer;
    private final PluginManager mPluginManager;

    View mCurrentView = null;
    private View mVertical;
    private View mHorizontal;

    /** Indicates that navigation bar is vertical. */
    private boolean mIsVertical;
    private int mCurrentRotation = -1;

    boolean mLongClickableAccessibilityButton;
    int mDisabledFlags = 0;
    int mNavigationIconHints = 0;
    private int mNavBarMode;

    private final Region mTmpRegion = new Region();
    private final int[] mTmpPosition = new int[2];
    private Rect mTmpBounds = new Rect();

    private KeyButtonDrawable mBackIcon;
    private KeyButtonDrawable mHomeDefaultIcon;
    private KeyButtonDrawable mRecentIcon;
    private KeyButtonDrawable mDockedIcon;
    private KeyButtonDrawable mArrowLeftIcon;
    private KeyButtonDrawable mArrowRightIcon;

    private EdgeBackGestureHandler mEdgeBackGestureHandler;
    private final DeadZone mDeadZone;
    private boolean mDeadZoneConsuming = false;
    private final NavigationBarTransitions mBarTransitions;
    private final OverviewProxyService mOverviewProxyService;
    private boolean mBlockedGesturalNavigation;

    private AutoHideController mAutoHideController;

    // performs manual animation in sync with layout transitions
    private final NavTransitionListener mTransitionListener = new NavTransitionListener();

    private OnVerticalChangedListener mOnVerticalChangedListener;
    private boolean mLayoutTransitionsEnabled = true;
    private boolean mWakeAndUnlocking;
    private boolean mUseCarModeUi = false;
    private boolean mInCarMode = false;
    private boolean mDockedStackExists;
    private boolean mImeVisible;
    private boolean mScreenOn = true;

    private final SparseArray<ButtonDispatcher> mButtonDispatchers = new SparseArray<>();
    private final ContextualButtonGroup mContextualButtonGroup;
    private Configuration mConfiguration;
    private Configuration mTmpLastConfiguration;

    private NavigationBarInflaterView mNavigationInflaterView;
    private RecentsOnboarding mRecentsOnboarding;
    private NotificationPanelViewController mPanelView;
    private FloatingRotationButton mFloatingRotationButton;
    private RotationButtonController mRotationButtonController;

    private boolean mHomeHandleForceHidden;

    /**
     * Helper that is responsible for showing the right toast when a disallowed activity operation
     * occurred. In pinned mode, we show instructions on how to break out of this mode, whilst in
     * fully locked mode we only show that unlocking is blocked.
     */
    private ScreenPinningNotify mScreenPinningNotify;
    private Rect mSamplingBounds = new Rect();
    /**
     * When quickswitching between apps of different orientations, we draw a secondary home handle
     * in the position of the first app's orientation. This rect represents the region of that
     * home handle so we can apply the correct light/dark luma on that.
     * @see {@link NavigationBarFragment#mOrientationHandle}
     */
    @Nullable
    private Rect mOrientedHandleSamplingRegion;

    private class NavTransitionListener implements TransitionListener {
        private boolean mBackTransitioning;
        private boolean mHomeAppearing;
        private long mStartDelay;
        private long mDuration;
        private TimeInterpolator mInterpolator;

        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = true;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = true;
                mStartDelay = transition.getStartDelay(transitionType);
                mDuration = transition.getDuration(transitionType);
                mInterpolator = transition.getInterpolator(transitionType);
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (view.getId() == R.id.back) {
                mBackTransitioning = false;
            } else if (view.getId() == R.id.home && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = false;
            }
        }

        public void onBackAltCleared() {
            ButtonDispatcher backButton = getBackButton();

            // When dismissing ime during unlock, force the back button to run the same appearance
            // animation as home (if we catch this condition early enough).
            if (!mBackTransitioning && backButton.getVisibility() == VISIBLE
                    && mHomeAppearing && getHomeButton().getAlpha() == 0) {
                getBackButton().setAlpha(0);
                ValueAnimator a = ObjectAnimator.ofFloat(backButton, "alpha", 0, 1);
                a.setStartDelay(mStartDelay);
                a.setDuration(mDuration);
                a.setInterpolator(mInterpolator);
                a.start();
            }
        }
    }

    private final OnClickListener mImeSwitcherClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            mContext.getSystemService(InputMethodManager.class).showInputMethodPickerFromSystem(
                    true /* showAuxiliarySubtypes */, getContext().getDisplayId());
        }
    };

    private final AccessibilityDelegate mQuickStepAccessibilityDelegate =
            new AccessibilityDelegate() {
                private AccessibilityAction mToggleOverviewAction;

                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                        AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    if (mToggleOverviewAction == null) {
                        mToggleOverviewAction = new AccessibilityAction(
                                R.id.action_toggle_overview, getContext().getString(
                                R.string.quick_step_accessibility_toggle_overview));
                    }
                    info.addAction(mToggleOverviewAction);
                }

                @Override
                public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == R.id.action_toggle_overview) {
                        Dependency.get(Recents.class).toggleRecentApps();
                    } else {
                        return super.performAccessibilityAction(host, action, args);
                    }
                    return true;
                }
            };

    private final OnComputeInternalInsetsListener mOnComputeInternalInsetsListener = info -> {
        // When the nav bar is in 2-button or 3-button mode, or when IME is visible in fully
        // gestural mode, the entire nav bar should be touchable.
        if (!mEdgeBackGestureHandler.isHandlingGestures()) {
            info.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
            return;
        }

        info.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        info.touchableRegion.set(getButtonLocations(false /* includeFloatingRotationButton */,
                false /* inScreen */));
    };

    private final Consumer<Boolean> mRotationButtonListener = (visible) -> {
        if (visible) {
            // If the button will actually become visible and the navbar is about to hide,
            // tell the statusbar to keep it around for longer
            mAutoHideController.touchAutoHide();
        }
        notifyActiveTouchRegions();
    };

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mIsVertical = false;
        mLongClickableAccessibilityButton = false;
        mNavBarMode = Dependency.get(NavigationModeController.class).addListener(this);
        boolean isGesturalMode = isGesturalMode(mNavBarMode);

        mSysUiFlagContainer = Dependency.get(SysUiState.class);
        mPluginManager = Dependency.get(PluginManager.class);
        // Set up the context group of buttons
        mContextualButtonGroup = new ContextualButtonGroup(R.id.menu_container);
        final ContextualButton imeSwitcherButton = new ContextualButton(R.id.ime_switcher,
                R.drawable.ic_ime_switcher_default);
        final RotationContextButton rotateSuggestionButton = new RotationContextButton(
                R.id.rotate_suggestion, R.drawable.ic_sysbar_rotate_button);
        final ContextualButton accessibilityButton =
                new ContextualButton(R.id.accessibility_button,
                        R.drawable.ic_sysbar_accessibility_button);
        mContextualButtonGroup.addButton(imeSwitcherButton);
        if (!isGesturalMode) {
            mContextualButtonGroup.addButton(rotateSuggestionButton);
        }
        mContextualButtonGroup.addButton(accessibilityButton);

        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        mRecentsOnboarding = new RecentsOnboarding(context, mOverviewProxyService);
        mFloatingRotationButton = new FloatingRotationButton(context);
        mRotationButtonController = new RotationButtonController(context,
                R.style.RotateButtonCCWStart90,
                isGesturalMode ? mFloatingRotationButton : rotateSuggestionButton,
                mRotationButtonListener);

        mConfiguration = new Configuration();
        mTmpLastConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());

        mScreenPinningNotify = new ScreenPinningNotify(mContext);
        mBarTransitions = new NavigationBarTransitions(this, Dependency.get(CommandQueue.class));

        mButtonDispatchers.put(R.id.back, new ButtonDispatcher(R.id.back));
        mButtonDispatchers.put(R.id.home, new ButtonDispatcher(R.id.home));
        mButtonDispatchers.put(R.id.home_handle, new ButtonDispatcher(R.id.home_handle));
        mButtonDispatchers.put(R.id.recent_apps, new ButtonDispatcher(R.id.recent_apps));
        mButtonDispatchers.put(R.id.ime_switcher, imeSwitcherButton);
        mButtonDispatchers.put(R.id.accessibility_button, accessibilityButton);
        mButtonDispatchers.put(R.id.rotate_suggestion, rotateSuggestionButton);
        mButtonDispatchers.put(R.id.menu_container, mContextualButtonGroup);
        mDeadZone = new DeadZone(this);

        mNavColorSampleMargin = getResources()
                        .getDimensionPixelSize(R.dimen.navigation_handle_sample_horizontal_margin);
        mEdgeBackGestureHandler = new EdgeBackGestureHandler(context, mOverviewProxyService,
                mSysUiFlagContainer, mPluginManager, this::updateStates);
        mRegionSamplingHelper = new RegionSamplingHelper(this,
                new RegionSamplingHelper.SamplingCallback() {
                    @Override
                    public void onRegionDarknessChanged(boolean isRegionDark) {
                        getLightTransitionsController().setIconsDark(!isRegionDark ,
                                true /* animate */);
                    }

                    @Override
                    public Rect getSampledRegion(View sampledView) {
                        if (mOrientedHandleSamplingRegion != null) {
                            return mOrientedHandleSamplingRegion;
                        }

                        updateSamplingRect();
                        return mSamplingBounds;
                    }

                    @Override
                    public boolean isSamplingEnabled() {
                        return isGesturalModeOnDefaultDisplay(getContext(), mNavBarMode);
                    }
                });
    }

    public void setAutoHideController(AutoHideController autoHideController) {
        mAutoHideController = autoHideController;
    }

    public NavigationBarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public LightBarTransitionsController getLightTransitionsController() {
        return mBarTransitions.getLightTransitionsController();
    }

    public void setComponents(NotificationPanelViewController panel) {
        mPanelView = panel;
        updatePanelSystemUiStateFlags();
    }

    public void setOnVerticalChangedListener(OnVerticalChangedListener onVerticalChangedListener) {
        mOnVerticalChangedListener = onVerticalChangedListener;
        notifyVerticalChangedListener(mIsVertical);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (isGesturalMode(mNavBarMode) && mImeVisible
                && event.getAction() == MotionEvent.ACTION_DOWN) {
            SysUiStatsLog.write(SysUiStatsLog.IME_TOUCH_REPORTED,
                    (int) event.getX(), (int) event.getY());
        }
        return shouldDeadZoneConsumeTouchEvents(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        shouldDeadZoneConsumeTouchEvents(event);
        return super.onTouchEvent(event);
    }

    void onTransientStateChanged(boolean isTransient) {
        mEdgeBackGestureHandler.onNavBarTransientStateChanged(isTransient);
    }

    void onBarTransition(int newMode) {
        if (newMode == MODE_OPAQUE) {
            // If the nav bar background is opaque, stop auto tinting since we know the icons are
            // showing over a dark background
            mRegionSamplingHelper.stop();
            getLightTransitionsController().setIconsDark(false /* dark */, true /* animate */);
        } else {
            mRegionSamplingHelper.start(mSamplingBounds);
        }
    }

    private boolean shouldDeadZoneConsumeTouchEvents(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDeadZoneConsuming = false;
        }
        if (mDeadZone.onTouchEvent(event) || mDeadZoneConsuming) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    // Allow gestures starting in the deadzone to be slippery
                    setSlippery(true);
                    mDeadZoneConsuming = true;
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    // When a gesture started in the deadzone is finished, restore slippery state
                    updateSlippery();
                    mDeadZoneConsuming = false;
                    break;
            }
            return true;
        }
        return false;
    }

    public void abortCurrentGesture() {
        getHomeButton().abortCurrentGesture();
    }

    public View getCurrentView() {
        return mCurrentView;
    }

    public RotationButtonController getRotationButtonController() {
        return mRotationButtonController;
    }

    public FloatingRotationButton getFloatingRotationButton() {
        return mFloatingRotationButton;
    }

    public ButtonDispatcher getRecentsButton() {
        return mButtonDispatchers.get(R.id.recent_apps);
    }

    public ButtonDispatcher getBackButton() {
        return mButtonDispatchers.get(R.id.back);
    }

    public ButtonDispatcher getHomeButton() {
        return mButtonDispatchers.get(R.id.home);
    }

    public ButtonDispatcher getImeSwitchButton() {
        return mButtonDispatchers.get(R.id.ime_switcher);
    }

    public ButtonDispatcher getAccessibilityButton() {
        return mButtonDispatchers.get(R.id.accessibility_button);
    }

    public RotationContextButton getRotateSuggestionButton() {
        return (RotationContextButton) mButtonDispatchers.get(R.id.rotate_suggestion);
    }

    public ButtonDispatcher getHomeHandle() {
        return mButtonDispatchers.get(R.id.home_handle);
    }

    public SparseArray<ButtonDispatcher> getButtonDispatchers() {
        return mButtonDispatchers;
    }

    public boolean isRecentsButtonVisible() {
        return getRecentsButton().getVisibility() == View.VISIBLE;
    }

    public boolean isOverviewEnabled() {
        return (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) == 0;
    }

    public boolean isQuickStepSwipeUpEnabled() {
        return mOverviewProxyService.shouldShowSwipeUpUI() && isOverviewEnabled();
    }

    public KeyButtonView getKeyButtonViewById(int id) {
          return (KeyButtonView) getCurrentView().findViewById(id);
    }

    private void reloadNavIcons() {
        updateIcons(Configuration.EMPTY);
    }

    private void updateIcons(Configuration oldConfig) {
        final boolean orientationChange = oldConfig.orientation != mConfiguration.orientation;
        final boolean densityChange = oldConfig.densityDpi != mConfiguration.densityDpi;
        final boolean dirChange = oldConfig.getLayoutDirection() != mConfiguration.getLayoutDirection();

        if (orientationChange || densityChange) {
            mDockedIcon = getDrawable(R.drawable.ic_sysbar_docked);
            mHomeDefaultIcon = getHomeDrawable();
        }
        if (densityChange || dirChange) {
            mRecentIcon = getDrawable(R.drawable.ic_sysbar_recent);
            mContextualButtonGroup.updateIcons();
        }
        if (orientationChange || densityChange || dirChange) {
            mBackIcon = getBackDrawable();
        }
        mArrowLeftIcon = getDrawable(R.drawable.ic_navbar_chevron_left);
        mArrowRightIcon = getDrawable(R.drawable.ic_navbar_chevron_right);
    }

    public KeyButtonDrawable getBackDrawable() {
        KeyButtonDrawable drawable = getDrawable(getBackDrawableRes());
        orientBackButton(drawable);
        return drawable;
    }

    public @DrawableRes int getBackDrawableRes() {
        return chooseNavigationIconDrawableRes(R.drawable.ic_sysbar_back,
                R.drawable.ic_sysbar_back_quick_step);
    }

    public KeyButtonDrawable getHomeDrawable() {
        final boolean quickStepEnabled = mOverviewProxyService.shouldShowSwipeUpUI();
        KeyButtonDrawable drawable = quickStepEnabled
                ? getDrawable(R.drawable.ic_sysbar_home_quick_step)
                : getDrawable(R.drawable.ic_sysbar_home);
        orientHomeButton(drawable);
        return drawable;
    }

    private void orientBackButton(KeyButtonDrawable drawable) {
        final boolean useAltBack =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        final boolean isRtl = mConfiguration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float degrees = useAltBack ? (isRtl ? 90 : -90) : 0;
        if (drawable.getRotation() == degrees) {
            return;
        }

        if (isGesturalMode(mNavBarMode)) {
            drawable.setRotation(degrees);
            return;
        }

        // Animate the back button's rotation to the new degrees and only in portrait move up the
        // back button to line up with the other buttons
        float targetY = !mOverviewProxyService.shouldShowSwipeUpUI() && !mIsVertical && useAltBack
                ? - getResources().getDimension(R.dimen.navbar_back_button_ime_offset)
                : 0;
        ObjectAnimator navBarAnimator = ObjectAnimator.ofPropertyValuesHolder(drawable,
                PropertyValuesHolder.ofFloat(KeyButtonDrawable.KEY_DRAWABLE_ROTATE, degrees),
                PropertyValuesHolder.ofFloat(KeyButtonDrawable.KEY_DRAWABLE_TRANSLATE_Y, targetY));
        navBarAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        navBarAnimator.setDuration(200);
        navBarAnimator.start();
    }

    private void orientHomeButton(KeyButtonDrawable drawable) {
        drawable.setRotation(mIsVertical ? 90 : 0);
    }

    private KeyButtonDrawable chooseNavigationIconDrawable(@DrawableRes int icon,
            @DrawableRes int quickStepIcon) {
        return getDrawable(chooseNavigationIconDrawableRes(icon, quickStepIcon));
    }

    private @DrawableRes int chooseNavigationIconDrawableRes(@DrawableRes int icon,
            @DrawableRes int quickStepIcon) {
        final boolean quickStepEnabled = mOverviewProxyService.shouldShowSwipeUpUI();
        return quickStepEnabled ? quickStepIcon : icon;
    }

    private KeyButtonDrawable getDrawable(@DrawableRes int icon) {
        return KeyButtonDrawable.create(mContext, icon, true /* hasShadow */);
    }

    private KeyButtonDrawable getDrawable(@DrawableRes int icon, boolean hasShadow) {
        return KeyButtonDrawable.create(mContext, icon, hasShadow);
    }

    /** To be called when screen lock/unlock state changes */
    public void onScreenStateChanged(boolean isScreenOn) {
        mScreenOn = isScreenOn;
        if (isScreenOn) {
            if (isGesturalModeOnDefaultDisplay(getContext(), mNavBarMode)) {
                mRegionSamplingHelper.start(mSamplingBounds);
            }
        } else {
            mRegionSamplingHelper.stop();
        }
    }

    public void setWindowVisible(boolean visible) {
        mRegionSamplingHelper.setWindowVisible(visible);
        mRotationButtonController.onNavigationBarWindowVisibilityChange(visible);
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        reloadNavIcons();

        super.setLayoutDirection(layoutDirection);
    }

    public void setNavigationIconHints(int hints) {
        if (hints == mNavigationIconHints) return;
        final boolean newBackAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        final boolean oldBackAlt =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if (newBackAlt != oldBackAlt) {
            onImeVisibilityChanged(newBackAlt);
        }

        if (DEBUG) {
            android.widget.Toast.makeText(getContext(),
                "Navigation icon hints = " + hints,
                500).show();
        }
        mNavigationIconHints = hints;
        updateNavButtonIcons();
    }

    private void onImeVisibilityChanged(boolean visible) {
        if (!visible) {
            mTransitionListener.onBackAltCleared();
        }
        mImeVisible = visible;
        mEdgeBackGestureHandler.setImeVisible(mImeVisible);
        mRotationButtonController.getRotationButton().setCanShowRotationButton(!mImeVisible);
        mFloatingRotationButton.setCanShowRotationButton(!mImeVisible);
    }

    public void setDisabledFlags(int disabledFlags) {
        if (mDisabledFlags == disabledFlags) return;

        final boolean overviewEnabledBefore = isOverviewEnabled();
        mDisabledFlags = disabledFlags;

        // Update icons if overview was just enabled to ensure the correct icons are present
        if (!overviewEnabledBefore && isOverviewEnabled()) {
            reloadNavIcons();
        }

        updateNavButtonIcons();
        updateSlippery();
        setUpSwipeUpOnboarding(isQuickStepSwipeUpEnabled());
        updateDisabledSystemUiStateFlags();
    }

    public void updateNavButtonIcons() {
        // We have to replace or restore the back and home button icons when exiting or entering
        // carmode, respectively. Recents are not available in CarMode in nav bar so change
        // to recent icon is not required.
        final boolean useAltBack =
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        KeyButtonDrawable backIcon = mBackIcon;
        orientBackButton(backIcon);
        KeyButtonDrawable homeIcon = mHomeDefaultIcon;
        if (!mUseCarModeUi) {
            orientHomeButton(homeIcon);
        }
        getHomeButton().setImageDrawable(homeIcon);
        getBackButton().setImageDrawable(backIcon);

        updateRecentsIcon();

        // Update arrow buttons
        if (showDpadArrowKeys()) {
            getKeyButtonViewById(R.id.dpad_left).setImageDrawable(mArrowLeftIcon);
            getKeyButtonViewById(R.id.dpad_right).setImageDrawable(mArrowRightIcon);
            updateDpadKeys();
        }

        // Update IME button visibility, a11y and rotate button always overrides the appearance
        mContextualButtonGroup.setButtonVisibility(R.id.ime_switcher,
                (mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);

        // right arrow overrules ime in 3 button mode cause there is not enough space
        if (QuickStepContract.isLegacyMode(mNavBarMode) && showDpadArrowKeys()) {
            mContextualButtonGroup.setButtonVisibility(R.id.ime_switcher, false);
        }

        mBarTransitions.reapplyDarkIntensity();

        boolean disableHome = isGesturalMode(mNavBarMode)
                || ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        // Always disable recents when alternate car mode UI is active and for secondary displays.
        boolean disableRecent = isRecentsButtonDisabled();

        // Disable the home handle if both hone and recents are disabled
        boolean disableHomeHandle = disableRecent
                && ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);

        boolean disableBack = !useAltBack && (mEdgeBackGestureHandler.isHandlingGestures()
                || ((mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0));

        // When screen pinning, don't hide back and home when connected service or back and
        // recents buttons when disconnected from launcher service in screen pinning mode,
        // as they are used for exiting.
        final boolean pinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        if (mOverviewProxyService.isEnabled()) {
            // Force disable recents when not in legacy mode
            disableRecent |= !QuickStepContract.isLegacyMode(mNavBarMode);
            if (pinningActive && !QuickStepContract.isGesturalMode(mNavBarMode)) {
                disableBack = disableHome = false;
            }
        } else if (pinningActive) {
            disableBack = disableRecent = false;
        }
        if (pinningActive && isGesturalMode(mNavBarMode)){
            disableBack = true;
        }

        ViewGroup navButtons = getCurrentView().findViewById(R.id.nav_buttons);
        if (navButtons != null) {
            LayoutTransition lt = navButtons.getLayoutTransition();
            if (lt != null) {
                if (!lt.getTransitionListeners().contains(mTransitionListener)) {
                    lt.addTransitionListener(mTransitionListener);
                }
            }
        }

        getBackButton().setVisibility(disableBack       ? View.INVISIBLE : View.VISIBLE);
        getHomeButton().setVisibility(disableHome       ? View.INVISIBLE : View.VISIBLE);
        getRecentsButton().setVisibility(disableRecent  ? View.INVISIBLE : View.VISIBLE);
        getHomeHandle().setVisibility(disableHomeHandle || mHomeHandleForceHidden ? View.INVISIBLE : View.VISIBLE);
        notifyActiveTouchRegions();
    }

    public void hideHomeHandle(boolean hide) {
        mHomeHandleForceHidden = hide;
        boolean disableRecent = isRecentsButtonDisabled() | !QuickStepContract.isLegacyMode(mNavBarMode);
        boolean disableHomeHandle = disableRecent
                && ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        getHomeHandle().setVisibility(disableHomeHandle || hide ? View.INVISIBLE : View.VISIBLE);
    }

    public boolean isHomeHandleForceHidden() {
        return mHomeHandleForceHidden;
    }

    @VisibleForTesting
    boolean isRecentsButtonDisabled() {
        return mUseCarModeUi || !isOverviewEnabled()
                || getContext().getDisplayId() != Display.DEFAULT_DISPLAY;
    }

    private Display getContextDisplay() {
        return getContext().getDisplay();
    }

    public void setLayoutTransitionsEnabled(boolean enabled) {
        mLayoutTransitionsEnabled = enabled;
        updateLayoutTransitionsEnabled();
    }

    public void setWakeAndUnlocking(boolean wakeAndUnlocking) {
        setUseFadingAnimations(wakeAndUnlocking);
        mWakeAndUnlocking = wakeAndUnlocking;
        updateLayoutTransitionsEnabled();
    }

    private void updateLayoutTransitionsEnabled() {
        boolean enabled = !mWakeAndUnlocking && mLayoutTransitionsEnabled;
        ViewGroup navButtons = (ViewGroup) getCurrentView().findViewById(R.id.nav_buttons);
        LayoutTransition lt = navButtons.getLayoutTransition();
        if (lt != null) {
            if (enabled) {
                lt.enableTransitionType(LayoutTransition.APPEARING);
                lt.enableTransitionType(LayoutTransition.DISAPPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            } else {
                lt.disableTransitionType(LayoutTransition.APPEARING);
                lt.disableTransitionType(LayoutTransition.DISAPPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            }
        }
    }

    private void setUseFadingAnimations(boolean useFadingAnimations) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) ((ViewGroup) getParent())
                .getLayoutParams();
        if (lp != null) {
            boolean old = lp.windowAnimations != 0;
            if (!old && useFadingAnimations) {
                lp.windowAnimations = R.style.Animation_NavigationBarFadeIn;
            } else if (old && !useFadingAnimations) {
                lp.windowAnimations = 0;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout((View) getParent(), lp);
        }
    }

    public void onStatusBarPanelStateChanged() {
        updateSlippery();
        updatePanelSystemUiStateFlags();
    }

    public void updateBackArrowForGesture() {
        mEdgeBackGestureHandler.setBackArrowVisibility();
    }

    public void updateBackGestureHaptic() {
        mEdgeBackGestureHandler.setBackGestureHaptic();
    }

    public void updateDisabledSystemUiStateFlags() {
        int displayId = mContext.getDisplayId();

        mSysUiFlagContainer.setFlag(SYSUI_STATE_SCREEN_PINNING,
                        ActivityManagerWrapper.getInstance().isScreenPinningActive())
                .setFlag(SYSUI_STATE_OVERVIEW_DISABLED,
                        mBlockedGesturalNavigation ||
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0)
                .setFlag(SYSUI_STATE_HOME_DISABLED,
                        mBlockedGesturalNavigation ||
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0)
                .setFlag(SYSUI_STATE_SEARCH_DISABLED,
                        mBlockedGesturalNavigation ||
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0)
                .commitUpdate(displayId);
    }

    public void updatePanelSystemUiStateFlags() {
        int displayId = mContext.getDisplayId();
        if (SysUiState.DEBUG) {
            Log.d(TAG, "Updating panel sysui state flags: panelView=" + mPanelView);
        }
        if (mPanelView != null) {
            if (SysUiState.DEBUG) {
                Log.d(TAG, "Updating panel sysui state flags: fullyExpanded="
                        + mPanelView.isFullyExpanded() + " inQs=" + mPanelView.isInSettings());
            }
            mSysUiFlagContainer.setFlag(SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED,
                    mPanelView.isFullyExpanded() && !mPanelView.isInSettings())
                    .setFlag(SYSUI_STATE_QUICK_SETTINGS_EXPANDED,
                            mBlockedGesturalNavigation || mPanelView.isInSettings())
                    .commitUpdate(displayId);
        }
    }

    public void updateStates() {
        final boolean showSwipeUpUI = mOverviewProxyService.shouldShowSwipeUpUI();

        if (mNavigationInflaterView != null) {
            // Reinflate the navbar if needed, no-op unless the swipe up state changes
            mNavigationInflaterView.onLikelyDefaultLayoutChange();
        }

        updateSlippery();
        reloadNavIcons();
        updateNavButtonIcons();
        setUpSwipeUpOnboarding(isQuickStepSwipeUpEnabled());
        WindowManagerWrapper.getInstance().setNavBarVirtualKeyHapticFeedbackEnabled(!showSwipeUpUI);
        getHomeButton().setAccessibilityDelegate(
                showSwipeUpUI ? mQuickStepAccessibilityDelegate : null);
    }

    public void setBlockedGesturalNavigation(boolean blocked) {
        mBlockedGesturalNavigation = blocked;
        mEdgeBackGestureHandler.setBlockedGesturalNavigation(blocked);
        updateDisabledSystemUiStateFlags();
        updatePanelSystemUiStateFlags();
    }

    /**
     * Updates the {@link WindowManager.LayoutParams.FLAG_SLIPPERY} state dependent on if swipe up
     * is enabled, or the notifications is fully opened without being in an animated state. If
     * slippery is enabled, touch events will leave the nav bar window and enter into the fullscreen
     * app/home window, if not nav bar will receive a cancelled touch event once gesture leaves bar.
     */
    public void updateSlippery() {
        setSlippery(!isQuickStepSwipeUpEnabled() ||
                (mPanelView != null && mPanelView.isFullyExpanded() && !mPanelView.isCollapsing()));
    }

    private void setSlippery(boolean slippery) {
        setWindowFlag(WindowManager.LayoutParams.FLAG_SLIPPERY, slippery);
    }

    private void setWindowFlag(int flags, boolean enable) {
        final ViewGroup navbarView = ((ViewGroup) getParent());
        if (navbarView == null) {
            return;
        }
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) navbarView.getLayoutParams();
        if (lp == null || enable == ((lp.flags & flags) != 0)) {
            return;
        }
        if (enable) {
            lp.flags |= flags;
        } else {
            lp.flags &= ~flags;
        }
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.updateViewLayout(navbarView, lp);
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
        mBarTransitions.onNavigationModeChanged(mNavBarMode);
        mEdgeBackGestureHandler.onNavigationModeChanged(mNavBarMode);
        mRecentsOnboarding.onNavigationModeChanged(mNavBarMode);
        getRotateSuggestionButton().onNavigationModeChanged(mNavBarMode);

        if (isGesturalMode(mNavBarMode)) {
            mRegionSamplingHelper.start(mSamplingBounds);
        } else {
            mRegionSamplingHelper.stop();
        }
    }

    public void setAccessibilityButtonState(final boolean visible, final boolean longClickable) {
        mLongClickableAccessibilityButton = longClickable;
        getAccessibilityButton().setLongClickable(longClickable);
        mContextualButtonGroup.setButtonVisibility(R.id.accessibility_button, visible);
    }

    void hideRecentsOnboarding() {
        mRecentsOnboarding.hide(true);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mNavigationInflaterView = findViewById(R.id.navigation_inflater);
        mNavigationInflaterView.setButtonDispatchers(mButtonDispatchers);

        getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);

        Divider divider = Dependency.get(Divider.class);
        divider.registerInSplitScreenListener(mDockedListener);
        updateOrientationViews();
        reloadNavIcons();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mDeadZone.onDraw(canvas);
        super.onDraw(canvas);
    }

    private void updateSamplingRect() {
        mSamplingBounds.setEmpty();
        // TODO: Extend this to 2/3 button layout as well
        View view = getHomeHandle().getCurrentView();

        if (view != null) {
            int[] pos = new int[2];
            view.getLocationOnScreen(pos);
            Point displaySize = new Point();
            view.getContext().getDisplay().getRealSize(displaySize);
            final Rect samplingBounds = new Rect(pos[0] - mNavColorSampleMargin,
                    displaySize.y - getNavBarHeight(),
                    pos[0] + view.getWidth() + mNavColorSampleMargin,
                    displaySize.y);
            mSamplingBounds.set(samplingBounds);
        }
    }

    void setOrientedHandleSamplingRegion(Rect orientedHandleSamplingRegion) {
        mOrientedHandleSamplingRegion = orientedHandleSamplingRegion;
        mRegionSamplingHelper.updateSamplingRect();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        notifyActiveTouchRegions();
        mRecentsOnboarding.setNavBarHeight(getMeasuredHeight());
    }

    /**
     * Notifies the overview service of the active touch regions.
     */
    public void notifyActiveTouchRegions() {
        mOverviewProxyService.onActiveNavBarRegionChanges(
                getButtonLocations(true /* includeFloatingRotationButton */, true /* inScreen */));
    }

    private Region getButtonLocations(boolean includeFloatingRotationButton,
            boolean inScreenSpace) {
        mTmpRegion.setEmpty();
        updateButtonLocation(getBackButton(), inScreenSpace);
        updateButtonLocation(getHomeButton(), inScreenSpace);
        updateButtonLocation(getRecentsButton(), inScreenSpace);
        updateButtonLocation(getImeSwitchButton(), inScreenSpace);
        updateButtonLocation(getAccessibilityButton(), inScreenSpace);
        if (includeFloatingRotationButton && mFloatingRotationButton.isVisible()) {
            updateButtonLocation(mFloatingRotationButton.getCurrentView(), inScreenSpace);
        } else {
            updateButtonLocation(getRotateSuggestionButton(), inScreenSpace);
        }
        return mTmpRegion;
    }

    private void updateButtonLocation(ButtonDispatcher button, boolean inScreenSpace) {
        View view = button.getCurrentView();
        if (view == null || !button.isVisible()) {
            return;
        }
        updateButtonLocation(view, inScreenSpace);
    }

    private void updateButtonLocation(View view, boolean inScreenSpace) {
        if (inScreenSpace) {
            view.getBoundsOnScreen(mTmpBounds);
        } else {
            view.getLocationInWindow(mTmpPosition);
            mTmpBounds.set(mTmpPosition[0], mTmpPosition[1],
                    mTmpPosition[0] + view.getWidth(),
                    mTmpPosition[1] + view.getHeight());
        }
        mTmpRegion.op(mTmpBounds, Op.UNION);
    }

    private void updateOrientationViews() {
        mHorizontal = findViewById(R.id.horizontal);
        mVertical = findViewById(R.id.vertical);

        updateCurrentView();
    }

    boolean needsReorient(int rotation) {
        return mCurrentRotation != rotation;
    }

    private void updateCurrentView() {
        resetViews();
        mCurrentView = mIsVertical ? mVertical : mHorizontal;
        mCurrentView.setVisibility(View.VISIBLE);
        mNavigationInflaterView.setVertical(mIsVertical);
        mCurrentRotation = getContextDisplay().getRotation();
        mNavigationInflaterView.setAlternativeOrder(mCurrentRotation == Surface.ROTATION_90);
        mNavigationInflaterView.updateButtonDispatchersCurrentView();
        updateLayoutTransitionsEnabled();
    }

    private void resetViews() {
        mHorizontal.setVisibility(View.GONE);
        mVertical.setVisibility(View.GONE);
    }

    private void updateRecentsIcon() {
        mDockedIcon.setRotation(mDockedStackExists && mIsVertical ? 90 : 0);
        getRecentsButton().setImageDrawable(mDockedStackExists ? mDockedIcon : mRecentIcon);
        mBarTransitions.reapplyDarkIntensity();
    }

    public void showPinningEnterExitToast(boolean entering) {
        if (entering) {
            mScreenPinningNotify.showPinningStartToast();
        } else {
            mScreenPinningNotify.showPinningExitToast();
        }
    }

    public void showPinningEscapeToast() {
        mScreenPinningNotify.showEscapeToast(
                mNavBarMode == NAV_BAR_MODE_GESTURAL, isRecentsButtonVisible());
    }

    public boolean isVertical() {
        return mIsVertical;
    }

    public NavigationBarFrame getNavbarFrame() {
        return ((NavigationBarFrame) getRootView());
    }

    public void reorient() {
        updateCurrentView();

        ((NavigationBarFrame) getRootView()).setDeadZone(mDeadZone);
        mDeadZone.onConfigurationChanged(mCurrentRotation);

        // force the low profile & disabled states into compliance
        mBarTransitions.init();

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mCurrentRotation);
        }

        // Resolve layout direction if not resolved since components changing layout direction such
        // as changing languages will recreate this view and the direction will be resolved later
        if (!isLayoutDirectionResolved()) {
            resolveLayoutDirection();
        }
        updateNavButtonIcons();

        getHomeButton().setVertical(mIsVertical);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (DEBUG) Log.d(TAG, String.format(
                "onMeasure: (%dx%d) old: (%dx%d)", w, h, getMeasuredWidth(), getMeasuredHeight()));

        final boolean newVertical = w > 0 && h > w
                && !isGesturalMode(mNavBarMode);
        if (newVertical != mIsVertical) {
            mIsVertical = newVertical;
            if (DEBUG) {
                Log.d(TAG, String.format("onMeasure: h=%d, w=%d, vert=%s", h, w,
                        mIsVertical ? "y" : "n"));
            }
            reorient();
            notifyVerticalChangedListener(newVertical);
        }

        if (isGesturalMode(mNavBarMode)) {
            // Update the nav bar background to match the height of the visible nav bar
            int height = mIsVertical
                    ? getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_height_landscape)
                    : getResources().getDimensionPixelSize(
                            com.android.internal.R.dimen.navigation_bar_height);
            int frameHeight = getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_frame_height);
            mBarTransitions.setBackgroundFrame(new Rect(0, frameHeight - height, w, h));
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private int getNavBarHeight() {
        return mIsVertical
                ? getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height_landscape)
                : getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.navigation_bar_height);
    }

    private void notifyVerticalChangedListener(boolean newVertical) {
        if (mOnVerticalChangedListener != null) {
            mOnVerticalChangedListener.onVerticalChanged(newVertical);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mTmpLastConfiguration.updateFrom(mConfiguration);
        mConfiguration.updateFrom(newConfig);
        boolean uiCarModeChanged = updateCarMode();
        updateIcons(mTmpLastConfiguration);
        updateRecentsIcon();
        mRecentsOnboarding.onConfigurationChanged(mConfiguration);
        if (uiCarModeChanged || mTmpLastConfiguration.densityDpi != mConfiguration.densityDpi
                || mTmpLastConfiguration.getLayoutDirection() != mConfiguration.getLayoutDirection()) {
            // If car mode or density changes, we need to reset the icons.
            updateNavButtonIcons();
        }
    }

    /**
     * If the configuration changed, update the carmode and return that it was updated.
     */
    private boolean updateCarMode() {
        boolean uiCarModeChanged = false;
        if (mConfiguration != null) {
            int uiMode = mConfiguration.uiMode & Configuration.UI_MODE_TYPE_MASK;
            final boolean isCarMode = (uiMode == Configuration.UI_MODE_TYPE_CAR);

            if (isCarMode != mInCarMode) {
                mInCarMode = isCarMode;
                if (ALTERNATE_CAR_MODE_UI) {
                    mUseCarModeUi = isCarMode;
                    uiCarModeChanged = true;
                } else {
                    // Don't use car mode behavior if ALTERNATE_CAR_MODE_UI not set.
                    mUseCarModeUi = false;
                }
            }
        }
        return uiCarModeChanged;
    }

    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = getContext().getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
        reorient();
        onNavigationModeChanged(mNavBarMode);
        setUpSwipeUpOnboarding(isQuickStepSwipeUpEnabled());
        if (mRotationButtonController != null) {
            mRotationButtonController.registerListeners();
        }

        mEdgeBackGestureHandler.onNavBarAttached();
        getViewTreeObserver().addOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(NavigationModeController.class).removeListener(this);
        setUpSwipeUpOnboarding(false);
        for (int i = 0; i < mButtonDispatchers.size(); ++i) {
            mButtonDispatchers.valueAt(i).onDestroy();
        }
        if (mRotationButtonController != null) {
            mRotationButtonController.unregisterListeners();
        }

        mEdgeBackGestureHandler.onNavBarDetached();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(
                mOnComputeInternalInsetsListener);
    }

    private void setUpSwipeUpOnboarding(boolean connectedToOverviewProxy) {
        if (connectedToOverviewProxy) {
            mRecentsOnboarding.onConnectedToLauncher();
        } else {
            mRecentsOnboarding.onDisconnectedFromLauncher();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        getContextDisplay().getRealSize(size);

        pw.println(String.format("      this: " + StatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: "
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s %f",
                        getResourceName(getCurrentView().getId()),
                        getCurrentView().getWidth(), getCurrentView().getHeight(),
                        visibilityToString(getCurrentView().getVisibility()),
                        getCurrentView().getAlpha()));

        pw.println(String.format("      disabled=0x%08x vertical=%s darkIntensity=%.2f",
                        mDisabledFlags,
                        mIsVertical ? "true" : "false",
                        getLightTransitionsController().getCurrentDarkIntensity()));

        pw.println("      mOrientedHandleSamplingRegion: " + mOrientedHandleSamplingRegion);

        dumpButton(pw, "back", getBackButton());
        dumpButton(pw, "home", getHomeButton());
        dumpButton(pw, "rcnt", getRecentsButton());
        dumpButton(pw, "rota", getRotateSuggestionButton());
        dumpButton(pw, "a11y", getAccessibilityButton());
        dumpButton(pw, "ime", getImeSwitchButton());

        pw.println("    }");
        pw.println("    mScreenOn: " + mScreenOn);

        if (mNavigationInflaterView != null) {
            mNavigationInflaterView.dump(pw);
        }
        mContextualButtonGroup.dump(pw);
        mRecentsOnboarding.dump(pw);
        mRegionSamplingHelper.dump(pw);
        mEdgeBackGestureHandler.dump(pw);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int leftInset = insets.getSystemWindowInsetLeft();
        int rightInset = insets.getSystemWindowInsetRight();
        setPadding(leftInset, insets.getSystemWindowInsetTop(), rightInset,
                insets.getSystemWindowInsetBottom());
        // we're passing the insets onto the gesture handler since the back arrow is only
        // conditionally added and doesn't always get all the insets.
        mEdgeBackGestureHandler.setInsets(leftInset, rightInset);

        // this allows assist handle to be drawn outside its bound so that it can align screen
        // bottom by translating its y position.
        final boolean shouldClip =
                !isGesturalMode(mNavBarMode) || insets.getSystemWindowInsetBottom() == 0;
        setClipChildren(shouldClip);
        setClipToPadding(shouldClip);

        NavigationBarController navigationBarController =
                Dependency.get(NavigationBarController.class);
        AssistHandleViewController controller =
                navigationBarController == null
                        ? null : navigationBarController.getAssistHandlerViewController();
        if (controller != null) {
            controller.setBottomOffset(insets.getSystemWindowInsetBottom());
        }
        return super.onApplyWindowInsets(insets);
    }

    private static void dumpButton(PrintWriter pw, String caption, ButtonDispatcher button) {
        pw.print("      " + caption + ": ");
        if (button == null) {
            pw.print("null");
        } else {
            pw.print(visibilityToString(button.getVisibility())
                    + " alpha=" + button.getAlpha()
                    );
        }
        pw.println();
    }

    public interface OnVerticalChangedListener {
        void onVerticalChanged(boolean isVertical);
    }

    private final Consumer<Boolean> mDockedListener = exists -> post(() -> {
        mDockedStackExists = exists;
        updateRecentsIcon();
    });

    private void updateDpadKeys() {
        final int visibility = showDpadArrowKeys() && (mNavigationIconHints
                & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0 ? View.VISIBLE : View.GONE;

        getKeyButtonViewById(R.id.dpad_left).setVisibility(visibility);
        getKeyButtonViewById(R.id.dpad_right).setVisibility(visibility);
    }

    public void setDpadDarkIntensity(float darkIntensity) {
        if (showDpadArrowKeys()) {
            getKeyButtonViewById(R.id.dpad_left).setDarkIntensity(darkIntensity);
            getKeyButtonViewById(R.id.dpad_right).setDarkIntensity(darkIntensity);
        }
    }

    private boolean showDpadArrowKeys() {
        return Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.NAVIGATION_BAR_ARROW_KEYS, 0, UserHandle.USER_CURRENT) != 0;
    }
}
