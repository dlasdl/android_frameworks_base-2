/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.Display;
import android.view.View;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.DemoMode;
import com.android.systemui.Dependency;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import libcore.icu.LocaleData;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Digital clock for the status bar.
 */
public class Clock extends TextView implements DemoMode, CommandQueue.Callbacks,
        DarkReceiver, ConfigurationListener {

    public static final String CLOCK_SECONDS = "clock_seconds";
    private static final String CLOCK_SUPER_PARCELABLE = "clock_super_parcelable";
    private static final String CURRENT_USER_ID = "current_user_id";
    private static final String VISIBLE_BY_POLICY = "visible_by_policy";
    private static final String VISIBLE_BY_USER = "visible_by_user";
    private static final String SHOW_SECONDS = "show_seconds";
    private static final String VISIBILITY = "visibility";
    private static final String QSHEADER = "qsheader";

    private final CurrentUserTracker mCurrentUserTracker;
    private final CommandQueue mCommandQueue;
    private int mCurrentUserId;

    private boolean mClockVisibleByPolicy = true;
    private boolean mClockVisibleByUser = true;
    protected boolean mClockHideableByUser = true;

    private boolean mAttached;
    private boolean mScreenReceiverRegistered;
    private Calendar mCalendar;
    private String mClockFormatString;
    private SimpleDateFormat mClockFormat;
    private SimpleDateFormat mContentDescriptionFormat;
    private Locale mLocale;

    public static final int AM_PM_STYLE_GONE    = 0;
    public static final int AM_PM_STYLE_SMALL   = 1;
    public static final int AM_PM_STYLE_NORMAL  = 2;

    private static int AM_PM_STYLE = AM_PM_STYLE_GONE;

    public static final int CLOCK_DATE_DISPLAY_GONE = 0;
    public static final int CLOCK_DATE_DISPLAY_SMALL = 1;
    public static final int CLOCK_DATE_DISPLAY_NORMAL = 2;

    public static final int CLOCK_DATE_STYLE_REGULAR = 0;
    public static final int CLOCK_DATE_STYLE_LOWERCASE = 1;
    public static final int CLOCK_DATE_STYLE_UPPERCASE = 2;

    public static final int STYLE_CLOCK_LEFT   = 0;
    public static final int STYLE_CLOCK_CENTER = 1;
    public static final int STYLE_CLOCK_RIGHT  = 2;

    public static final int STYLE_DATE_LEFT = 0;
    public static final int STYLE_DATE_RIGHT = 1;

    private int mClockFontStyle = FONT_NORMAL;
    public static final int FONT_NORMAL = 0;
    public static final int FONT_ITALIC = 1;
    public static final int FONT_BOLD = 2;
    public static final int FONT_BOLD_ITALIC = 3;
    public static final int FONT_LIGHT = 4;
    public static final int FONT_LIGHT_ITALIC = 5;
    public static final int FONT_THIN = 6;
    public static final int FONT_THIN_ITALIC = 7;
    public static final int FONT_CONDENSED = 8;
    public static final int FONT_CONDENSED_ITALIC = 9;
    public static final int FONT_CONDENSED_LIGHT = 10;
    public static final int FONT_CONDENSED_LIGHT_ITALIC = 11;
    public static final int FONT_CONDENSED_BOLD = 12;
    public static final int FONT_CONDENSED_BOLD_ITALIC = 13;
    public static final int FONT_MEDIUM = 14;
    public static final int FONT_MEDIUM_ITALIC = 15;
    public static final int FONT_BLACK = 16;
    public static final int FONT_BLACK_ITALIC = 17;
    public static final int FONT_DANCINGSCRIPT = 18;
    public static final int FONT_DANCINGSCRIPT_BOLD = 19;
    public static final int FONT_COMINGSOON = 20;
    public static final int FONT_NOTOSERIF = 21;
    public static final int FONT_NOTOSERIF_ITALIC = 22;
    public static final int FONT_NOTOSERIF_BOLD = 23;
    public static final int FONT_NOTOSERIF_BOLD_ITALIC = 24;
    public static final int GOBOLD_LIGHT = 25;
    public static final int ROADRAGE = 26;
    public static final int SNOWSTORM = 27;
    public static final int GOOGLESANS = 28;
    public static final int NEONEON = 29;
    public static final int THEMEABLE = 30;
    public int DEFAULT_CLOCK_SIZE = 14;
    public int DEFAULT_CLOCK_COLOR = 0xffffffff;

    protected int mClockDateDisplay = CLOCK_DATE_DISPLAY_GONE;
    protected int mClockDateStyle = CLOCK_DATE_STYLE_REGULAR;
    protected int mClockStyle = STYLE_CLOCK_LEFT;
    protected boolean mShowClock = true;
    private int mClockColor = 0xffffffff;
    private int mClockSize = 14;
    protected int mClockDatePosition;
    private int mAmPmStyle;

    private final boolean mShowDark;
    protected boolean mQsHeader;
    private boolean mShowSeconds;
    private Handler mSecondsHandler;
    private SettingsObserver mSettingsObserver;

    /**
     * Whether we should use colors that adapt based on wallpaper/the scrim behind quick settings
     * for text.
     */
    private boolean mUseWallpaperTextColor;

    /**
     * Color to be set on this {@link TextView}, when wallpaperTextColor is <b>not</b> utilized.
     */
    private int mNonAdaptedColor;

    private final BroadcastDispatcher mBroadcastDispatcher;

    protected class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_STYLE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK_SECONDS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_AM_PM_STYLE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_DATE_DISPLAY),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_DATE_STYLE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_DATE_FORMAT),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUSBAR_CLOCK_DATE_POSITION),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK_SIZE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CLOCK_FONT_STYLE),
                    false, this, UserHandle.USER_ALL);
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            updateClockColor();
            updateClockSize();
            updateClockFontStyle();
        }
    }

    public Clock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Clock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mCommandQueue = Dependency.get(CommandQueue.class);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.Clock,
                0, 0);
        try {
            mAmPmStyle = a.getInt(R.styleable.Clock_amPmStyle, AM_PM_STYLE_GONE);
            mShowDark = a.getBoolean(R.styleable.Clock_showDark, true);
            mNonAdaptedColor = getCurrentTextColor();
        } finally {
            a.recycle();
        }
        mBroadcastDispatcher = Dependency.get(BroadcastDispatcher.class);
        mCurrentUserTracker = new CurrentUserTracker(mBroadcastDispatcher) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUserId = newUserId;
            }
        };
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(CLOCK_SUPER_PARCELABLE, super.onSaveInstanceState());
        bundle.putInt(CURRENT_USER_ID, mCurrentUserId);
        bundle.putBoolean(VISIBLE_BY_POLICY, mClockVisibleByPolicy);
        bundle.putBoolean(VISIBLE_BY_USER, mClockVisibleByUser);
        bundle.putBoolean(SHOW_SECONDS, mShowSeconds);
        bundle.putInt(VISIBILITY, getVisibility());
        bundle.putBoolean(QSHEADER, mQsHeader);

        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (state == null || !(state instanceof Bundle)) {
            super.onRestoreInstanceState(state);
            return;
        }

        Bundle bundle = (Bundle) state;
        Parcelable superState = bundle.getParcelable(CLOCK_SUPER_PARCELABLE);
        super.onRestoreInstanceState(superState);
        if (bundle.containsKey(CURRENT_USER_ID)) {
            mCurrentUserId = bundle.getInt(CURRENT_USER_ID);
        }
        mClockVisibleByPolicy = bundle.getBoolean(VISIBLE_BY_POLICY, true);
        mClockVisibleByUser = bundle.getBoolean(VISIBLE_BY_USER, true);
        mShowSeconds = bundle.getBoolean(SHOW_SECONDS, false);
        if (bundle.containsKey(VISIBILITY)) {
            super.setVisibility(bundle.getInt(VISIBILITY));
        }
        mQsHeader = bundle.getBoolean(QSHEADER, false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            filter.addAction(Intent.ACTION_USER_SWITCHED);

            // NOTE: This receiver could run before this method returns, as it's not dispatching
            // on the main thread and BroadcastDispatcher may not need to register with Context.
            // The receiver will return immediately if the view does not have a Handler yet.
            mBroadcastDispatcher.registerReceiverWithHandler(mIntentReceiver, filter,
                    Dependency.get(Dependency.TIME_TICK_HANDLER), UserHandle.ALL);
            mCommandQueue.addCallback(this);
            if (mShowDark) {
                Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
            }
            mCurrentUserTracker.startTracking();
            mCurrentUserId = mCurrentUserTracker.getCurrentUserId();
        }

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());
        mClockFormatString = "";

        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler());
        }
        mSettingsObserver.observe();
        updateSettings();
        updateShowSeconds();
        updateClockColor();
        updateClockSize();
        updateClockFontStyle();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mScreenReceiverRegistered) {
            mScreenReceiverRegistered = false;
            mBroadcastDispatcher.unregisterReceiver(mScreenReceiver);
            if (mSecondsHandler != null) {
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
            }
        }
        if (mAttached) {
            mBroadcastDispatcher.unregisterReceiver(mIntentReceiver);
            getContext().getContentResolver().unregisterContentObserver(mSettingsObserver);
            mAttached = false;
            mCommandQueue.removeCallback(this);
            if (mShowDark) {
                Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
            }
            mCurrentUserTracker.stopTracking();
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If the handler is null, it means we received a broadcast while the view has not
            // finished being attached or in the process of being detached.
            // In that case, do not post anything.
            Handler handler = getHandler();
            if (handler == null) return;

            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra(Intent.EXTRA_TIMEZONE);
                handler.post(() -> {
                    mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                    if (mClockFormat != null) {
                        mClockFormat.setTimeZone(mCalendar.getTimeZone());
                    }
                });
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                final Locale newLocale = getResources().getConfiguration().locale;
                handler.post(() -> {
                    if (!newLocale.equals(mLocale)) {
                        mLocale = newLocale;
                    }
                    updateSettings();
                    return;
                });
            }
            handler.post(() -> updateClock());
        }
    };

    @Override
    public void setVisibility(int visibility) {
        if (visibility == View.VISIBLE && !shouldBeVisible()) {
            return;
        }

        super.setVisibility(visibility);
    }

    public void setClockVisibleByUser(boolean visible) {
        mClockVisibleByUser = visible;
        updateClockVisibility();
    }

    public void setClockVisibilityByPolicy(boolean visible) {
        mClockVisibleByPolicy = visible;
        updateClockVisibility();
    }

    private boolean shouldBeVisible() {
        return mClockVisibleByPolicy && mClockVisibleByUser;
    }

    protected void updateClockVisibility() {
        boolean visible = ((mClockStyle == STYLE_CLOCK_LEFT) || (mQsHeader))
                && mShowClock && mClockVisibleByPolicy && mClockVisibleByUser;
        int visibility = visible ? View.VISIBLE : View.GONE;
        super.setVisibility(visibility);
    }

    public boolean isClockVisible() {
        return mClockVisibleByPolicy && mClockVisibleByUser;
    }

    public void setClockHideableByUser(boolean value) {
        mClockHideableByUser = value;
    }

    final void updateClock() {
        if (mDemoMode) return;
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        setText(getSmallTime());
        setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getDisplay().getDisplayId()) {
            return;
        }
        boolean clockVisibleByPolicy = (state1 & StatusBarManager.DISABLE_CLOCK) == 0;
        if (clockVisibleByPolicy != mClockVisibleByPolicy) {
            setClockVisibilityByPolicy(clockVisibleByPolicy);
        }
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        mNonAdaptedColor = DarkIconDispatcher.getTint(area, this, tint);
        if (mClockColor == 0xFFFFFFFF) {
            setTextColor(mNonAdaptedColor);
        } else {
            setTextColor(mClockColor);
        }
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        FontSizeUtils.updateFontSize(this, R.dimen.status_bar_clock_size);
        setPaddingRelative(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_starting_padding),
                0,
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_end_padding),
                0);
    }

    /**
     * Sets whether the clock uses the wallpaperTextColor. If we're not using it, we'll revert back
     * to dark-mode-based/tinted colors.
     *
     * @param shouldUseWallpaperTextColor whether we should use wallpaperTextColor for text color
     **/
    public void useWallpaperTextColor(boolean shouldUseWallpaperTextColor) {
        setTextColor(mClockColor);
    }

    private void updateShowSeconds() {
        if (mShowSeconds) {
            // Wait until we have a display to start trying to show seconds.
            if (mSecondsHandler == null && getDisplay() != null) {
                mSecondsHandler = new Handler();
                if (getDisplay().getState() == Display.STATE_ON) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
                mScreenReceiverRegistered = true;
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                mBroadcastDispatcher.registerReceiver(mScreenReceiver, filter);
            }
        } else {
            if (mSecondsHandler != null) {
                mScreenReceiverRegistered = false;
                mBroadcastDispatcher.unregisterReceiver(mScreenReceiver);
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
                updateClock();
            }
        }
    }

    private final CharSequence getSmallTime() {
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context, mCurrentUserId);
        LocaleData d = LocaleData.get(context.getResources().getConfiguration().locale);

        final char MAGIC1 = '\uEF00';
        final char MAGIC2 = '\uEF01';

        SimpleDateFormat sdf;
        String format = mShowSeconds
                ? is24 ? d.timeFormat_Hms : d.timeFormat_hms
                : is24 ? d.timeFormat_Hm : d.timeFormat_hm;
        if (!format.equals(mClockFormatString)) {
            mContentDescriptionFormat = new SimpleDateFormat(format);
            /*
             * Search for an unquoted "a" in the format string, so we can
             * add dummy characters around it to let us find it again after
             * formatting and change its size.
             */
            if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
                int a = -1;
                boolean quoted = false;
                for (int i = 0; i < format.length(); i++) {
                    char c = format.charAt(i);

                    if (c == '\'') {
                        quoted = !quoted;
                    }
                    if (!quoted && c == 'a') {
                        a = i;
                        break;
                    }
                }

                if (a >= 0) {
                    // Move a back so any whitespace before AM/PM is also in the alternate size.
                    final int b = a;
                    while (a > 0 && Character.isWhitespace(format.charAt(a-1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                        + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = sdf = new SimpleDateFormat(format);
            mClockFormatString = format;
        } else {
            sdf = mClockFormat;
        }
        CharSequence dateString = null;
        String result = "";
        String timeResult = sdf.format(mCalendar.getTime());
        String dateResult = "";

        if (!mQsHeader && mClockDateDisplay != CLOCK_DATE_DISPLAY_GONE) {
            Date now = new Date();

            String clockDateFormat = Settings.System.getString(getContext().getContentResolver(),
                    Settings.System.STATUSBAR_CLOCK_DATE_FORMAT);

            if (clockDateFormat == null || clockDateFormat.isEmpty()) {
                // Set dateString to short uppercase Weekday if empty
                dateString = DateFormat.format("EEE", now);
            } else {
                dateString = DateFormat.format(clockDateFormat, now);
            }
            if (mClockDateStyle == CLOCK_DATE_STYLE_LOWERCASE) {
                // When Date style is small, convert date to uppercase
                dateResult = dateString.toString().toLowerCase();
            } else if (mClockDateStyle == CLOCK_DATE_STYLE_UPPERCASE) {
                dateResult = dateString.toString().toUpperCase();
            } else {
                dateResult = dateString.toString();
            }
            result = (mClockDatePosition == STYLE_DATE_LEFT) ? dateResult + " " + timeResult
                    : timeResult + " " + dateResult;
        } else {
            // No date, just show time
            result = timeResult;
        }

        SpannableStringBuilder formatted = new SpannableStringBuilder(result);

        if (mClockDateDisplay != CLOCK_DATE_DISPLAY_NORMAL) {
            if (dateString != null) {
                int dateStringLen = dateString.length();
                int timeStringOffset = (mClockDatePosition == STYLE_DATE_RIGHT)
                        ? timeResult.length() + 1 : 0;
                if (mClockDateDisplay == CLOCK_DATE_DISPLAY_GONE) {
                    formatted.delete(0, dateStringLen);
                } else {
                    if (mClockDateDisplay == CLOCK_DATE_DISPLAY_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, timeStringOffset,
                                timeStringOffset + dateStringLen,
                                Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                }
            }
        }
        if (mAmPmStyle != AM_PM_STYLE_NORMAL) {
            int magic1 = result.indexOf(MAGIC1);
            int magic2 = result.indexOf(MAGIC2);
            if (magic1 >= 0 && magic2 > magic1) {
                if (mAmPmStyle == AM_PM_STYLE_GONE) {
                    formatted.delete(magic1, magic2+1);
                } else {
                    if (mAmPmStyle == AM_PM_STYLE_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, magic1, magic2,
                                          Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                    formatted.delete(magic2, magic2 + 1);
                    formatted.delete(magic1, magic1 + 1);
                }
            }
        }
        return formatted;
    }

    protected void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        mShowClock = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_CLOCK, 1,
                UserHandle.USER_CURRENT) == 1;

        if (mQsHeader) {
            mShowClock = true; // QSHeader clock may override show clock
        }

        mShowSeconds = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_CLOCK_SECONDS, 0,
                UserHandle.USER_CURRENT) == 1;

        if (!mShowClock) {
            mClockStyle = 1; // internally switch to centered clock layout because
                             // left & right will show up again after QS pulldown
        } else {
            mClockStyle = Settings.System.getIntForUser(resolver,
                    Settings.System.STATUSBAR_CLOCK_STYLE, STYLE_CLOCK_LEFT,
                    UserHandle.USER_CURRENT);
        }

        boolean is24hour = DateFormat.is24HourFormat(mContext);
        int amPmStyle = Settings.System.getIntForUser(resolver,
                Settings.System.STATUSBAR_CLOCK_AM_PM_STYLE,
                AM_PM_STYLE_GONE,
                UserHandle.USER_CURRENT);
        mAmPmStyle = is24hour ? AM_PM_STYLE_GONE : amPmStyle;
        mClockFormatString = "";

        mClockDateDisplay = Settings.System.getIntForUser(resolver,
                Settings.System.STATUSBAR_CLOCK_DATE_DISPLAY, CLOCK_DATE_DISPLAY_GONE,
                UserHandle.USER_CURRENT);

        mClockDateStyle = Settings.System.getIntForUser(resolver,
                Settings.System.STATUSBAR_CLOCK_DATE_STYLE, CLOCK_DATE_STYLE_REGULAR,
                UserHandle.USER_CURRENT);

        mClockDatePosition = Settings.System.getIntForUser(resolver,
                Settings.System.STATUSBAR_CLOCK_DATE_POSITION, STYLE_DATE_LEFT,
                UserHandle.USER_CURRENT);

        if (mAttached) {
            updateClockVisibility();
            updateClock();
            updateShowSeconds();
            updateClockColor();
            updateClockSize();
            updateClockFontStyle();
        }
    }

    public boolean isClockDateEnabled() {
        return isClockVisible() && mClockDateDisplay != CLOCK_DATE_DISPLAY_GONE;
    }

    public void setQsHeader() {
        mQsHeader = true;
    }

    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            updateClock();
        } else if (mDemoMode && command.equals(COMMAND_CLOCK)) {
            String millis = args.getString("millis");
            String hhmm = args.getString("hhmm");
            if (millis != null) {
                mCalendar.setTimeInMillis(Long.parseLong(millis));
            } else if (hhmm != null && hhmm.length() == 4) {
                int hh = Integer.parseInt(hhmm.substring(0, 2));
                int mm = Integer.parseInt(hhmm.substring(2));
                boolean is24 = DateFormat.is24HourFormat(getContext(), mCurrentUserId);
                if (is24) {
                    mCalendar.set(Calendar.HOUR_OF_DAY, hh);
                } else {
                    mCalendar.set(Calendar.HOUR, hh);
                }
                mCalendar.set(Calendar.MINUTE, mm);
            }
            setText(getSmallTime());
            setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
        }
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.removeCallbacks(mSecondTick);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
            }
        }
    };

    private final Runnable mSecondTick = new Runnable() {
        @Override
        public void run() {
            if (mCalendar != null) {
                updateClock();
            }
            mSecondsHandler.postAtTime(this, SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
        }
    };

    public void updateClockSize() {
        mClockSize = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CLOCK_SIZE, DEFAULT_CLOCK_SIZE,
                UserHandle.USER_CURRENT);
        setTextSize(mClockSize);
        updateClock();
    }

    private void updateClockColor() {
        mClockColor = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CLOCK_COLOR, DEFAULT_CLOCK_COLOR,
                UserHandle.USER_CURRENT);

        if (mClockColor == 0xFFFFFFFF) {
            setTextColor(mNonAdaptedColor);
        } else {
            setTextColor(mClockColor);
        }
        updateClock();
    }

    private void updateClockFontStyle() {
        mClockFontStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CLOCK_FONT_STYLE, FONT_NORMAL,
		UserHandle.USER_CURRENT);
        getClockFontStyle(mClockFontStyle);
        updateClock();
    }

    public void getClockFontStyle(int font) {
        switch (font) {
            case FONT_NORMAL:
            default:
                setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
            case FONT_ITALIC:
                setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
                break;
            case FONT_BOLD:
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                break;
            case FONT_BOLD_ITALIC:
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD_ITALIC));
                break;
            case FONT_LIGHT:
                setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                break;
            case FONT_LIGHT_ITALIC:
                setTypeface(Typeface.create("sans-serif-light", Typeface.ITALIC));
                break;
            case FONT_THIN:
                setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
                break;
            case FONT_THIN_ITALIC:
                setTypeface(Typeface.create("sans-serif-thin", Typeface.ITALIC));
                break;
            case FONT_CONDENSED:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
                break;
            case FONT_CONDENSED_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.ITALIC));
                break;
            case FONT_CONDENSED_LIGHT:
                setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
                break;
            case FONT_CONDENSED_LIGHT_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.ITALIC));
                break;
            case FONT_CONDENSED_BOLD:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                break;
            case FONT_CONDENSED_BOLD_ITALIC:
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
                break;
            case FONT_MEDIUM:
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case FONT_MEDIUM_ITALIC:
                setTypeface(Typeface.create("sans-serif-medium", Typeface.ITALIC));
                break;
            case FONT_BLACK:
                setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                break;
            case FONT_BLACK_ITALIC:
                setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
                break;
            case FONT_DANCINGSCRIPT:
                setTypeface(Typeface.create("cursive", Typeface.NORMAL));
                break;
            case FONT_DANCINGSCRIPT_BOLD:
                setTypeface(Typeface.create("cursive", Typeface.BOLD));
                break;
            case FONT_COMINGSOON:
                setTypeface(Typeface.create("casual", Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF:
                setTypeface(Typeface.create("serif", Typeface.NORMAL));
                break;
            case FONT_NOTOSERIF_ITALIC:
                setTypeface(Typeface.create("serif", Typeface.ITALIC));
                break;
            case FONT_NOTOSERIF_BOLD:
                setTypeface(Typeface.create("serif", Typeface.BOLD));
                break;
            case FONT_NOTOSERIF_BOLD_ITALIC:
                setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
                break;
            case GOBOLD_LIGHT:
                setTypeface(Typeface.create("gobold-light-sys", Typeface.NORMAL));
                break;
            case ROADRAGE:
                setTypeface(Typeface.create("roadrage-sys", Typeface.NORMAL));
                break;
            case SNOWSTORM:
                setTypeface(Typeface.create("snowstorm-sys", Typeface.NORMAL));
                break;
            case GOOGLESANS:
                setTypeface(Typeface.create("googlesans-sys", Typeface.NORMAL));
                break;
            case NEONEON:
                setTypeface(Typeface.create("neoneon-sys", Typeface.NORMAL));
                break;
            case THEMEABLE:
                setTypeface(Typeface.create("themeable-sys", Typeface.NORMAL));
                break;
        }
    }
}
