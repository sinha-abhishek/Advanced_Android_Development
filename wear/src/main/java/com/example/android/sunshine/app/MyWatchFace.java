/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;



import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;



    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDayPaint;
        boolean mAmbient;
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDayOfWeekFormat;
        java.text.DateFormat mDateFormat;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        Bitmap mIconBitmap;

        String maxTemp;
        String minTemp;

        //params for watch face data

        private static final String WEATHER_PATH = "/weather";
        private static final String WEATHER_DETAIL_PATH = "/weather-info";

        private static final String KEY_UUID = "uuid";
        private static final String KEY_HIGH_TEMP = "highTemp";
        private static final String KEY_LOW_TEMP = "lowTemp";
        private static final String KEY_WEATHER_ID = "weatherId";

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        float mTempYPadding;
        float mTempXMinPadding;
        float mTempXMaxPadding;
        float mPaddingLeft;
        float mPaddingRight;
        float mPaddingTop;
        float mPaddingBottom;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDayPaint = new Paint();
            mDayPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            mMaxTempPaint = new Paint();
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE);

            mMinTempPaint = new Paint();
            mMinTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            //testing code
//            minTemp = "10" + (char) 0x00B0;
//            maxTemp = "20" + (char) 0x00B0;

            initFormats();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            return createTextPaint(textColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            initFormats();
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mPaddingTop = resources.getDimension(isRound ? R.dimen.top_padding_from_center_round : R.dimen.top_padding_from_center);
            mPaddingBottom = resources.getDimension(isRound ? R.dimen.bottom_padding_from_center_round : R.dimen.bottom_padding_from_center);
            mPaddingLeft = resources.getDimension(isRound ? R.dimen.left_padding_from_center_round : R.dimen.left_padding_from_center);
            mPaddingRight = resources.getDimension(isRound ? R.dimen.right_padding_from_center_round : R.dimen.right_padding_from_center);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float maxTextSize = resources.getDimension(R.dimen.temp_max_size);
            float minTextSize = resources.getDimension(R.dimen.temp_min_size);

            mTextPaint.setTextSize(textSize);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mDayPaint.setTextSize(textSize/2);
            mDayPaint.setTextAlign(Paint.Align.CENTER);
            mMinTempPaint.setTextSize(minTextSize);
            mMaxTempPaint.setTextSize(maxTextSize);
            mMaxTempPaint.setTextAlign(Paint.Align.CENTER);
            mMinTempPaint.setTextAlign(Paint.Align.CENTER);

            mTempXMaxPadding = resources.getDimension(R.dimen.temp_x_padding_max);
            mTempXMinPadding = resources.getDimension(R.dimen.temp_x_padding_min);
            mTempYPadding = resources.getDimension(isRound
                    ? R.dimen.temp_y_padding_round : R.dimen.temp_y_padding);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDayPaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            initFormats();
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
//                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
//                            .show();
                    break;
            }
            invalidate();
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE,MMM dd yyyy", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            //mDateFormat = new SimpleDateFormat("MMM dd", Locale.getDefault());//DateFormat.getDateFormat(MyWatchFace.this);
            //mDateFormat.setCalendar(mCalendar);
        }

        private Bitmap createWeatherIconBitmap(int resource) {
            Drawable b = getResources().getDrawable(resource);
            Bitmap icon = ((BitmapDrawable) b).getBitmap();
            float scaledWidth = (mMaxTempPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
            Bitmap bmp = Bitmap.createScaledBitmap(icon, (int) scaledWidth * 2, (int) mMaxTempPaint.getTextSize() * 2, true);
            return bmp;
        }



        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            float timeOffsetY = bounds.centerY() - mPaddingTop ;
            float timeOffsetX = bounds.centerX();


            String text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));

            float dateOffsetY = timeOffsetY + 30;
            float dateOffsetX = bounds.centerX();

            // Only render the day of week and date if there is no peek card, so they do not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {
                // Day of week
                canvas.drawText(
                        mDayOfWeekFormat.format(mDate),
                        dateOffsetX, dateOffsetY, mDayPaint);
            }

            canvas.drawText(text, timeOffsetX, timeOffsetY, mTextPaint);
            canvas.drawLine(bounds.centerX() - 20, bounds.centerY(), bounds.centerX() + 20, bounds.centerY(), mDayPaint);
            if (!mAmbient) {
                if (maxTemp != null) {
                    float xPos = bounds.centerX() + mPaddingRight;
                    float yPos = bounds.centerY() + mPaddingBottom;
                    canvas.drawText(maxTemp, xPos, yPos, mMaxTempPaint);
                }
                if (minTemp != null) {
                    float xPos = bounds.centerX() + mPaddingRight + mMaxTempPaint.measureText(maxTemp);
                    float yPos = bounds.centerY() + mPaddingBottom;
                    canvas.drawText(minTemp, xPos, yPos, mMinTempPaint);
                }
            } else {

                if (maxTemp != null) {
                    float xPos = bounds.centerX()  - mMaxTempPaint.measureText(maxTemp) ;//+ mPaddingRight;
                    float yPos = bounds.centerY() + mPaddingBottom;
                    canvas.drawText(maxTemp, xPos, yPos, mMaxTempPaint);
                }
                if (minTemp != null) {
                    float xPos = bounds.centerX() + mPaddingRight;
                    float yPos = bounds.centerY() + mPaddingBottom;
                    canvas.drawText(minTemp, xPos, yPos, mMinTempPaint);
                }
            }
            if (!mAmbient && minTemp == null && maxTemp == null) {
                float xPos = bounds.centerX() ;
                float yPos = bounds.height() - mTempYPadding;
                canvas.drawText(getString(R.string.trying_text), xPos, yPos, mMinTempPaint);
            }

            Bitmap bmp = mIconBitmap;
            if (!mAmbient && bmp != null) {
                float yPos = bounds.centerY() + mPaddingBottom - bmp.getHeight()/2;
                float highTextLen = mMaxTempPaint.measureText(maxTemp);
                float iconXOffset = bounds.centerX() - (mPaddingLeft + bmp.getWidth());
                canvas.drawBitmap(bmp, iconXOffset, yPos, null);

            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_PATH);
            putDataMapRequest.getDataMap().putString(KEY_UUID, UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d("OnResult", "Failed requesting weather data");
                            } else {
                                Log.d("onResult", "Successfully requested weather data");
                            }
                        }
                    });
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d("onDataChanged", "Got path="+ path);
                    if (path.equals(WEATHER_PATH)) {
                        if (dataMap.containsKey(KEY_HIGH_TEMP)) {
                            maxTemp = dataMap.getString(KEY_HIGH_TEMP);
                            Log.d("onDataChanged", "High = " + maxTemp);
                        } else {
                            Log.d("onDataChanged", "No high");
                        }

                        if (dataMap.containsKey(KEY_LOW_TEMP)) {
                            minTemp = dataMap.getString(KEY_LOW_TEMP);
                            Log.d("onDataChanged", "Low = " + minTemp);
                        } else {
                            Log.d("onDataChanged", "No low");
                        }

                        if (dataMap.containsKey(KEY_WEATHER_ID)) {
                            int weatherId = dataMap.getInt(KEY_WEATHER_ID);
                            int resourceIcon = getIconForWeatherId(weatherId);
                            mIconBitmap = createWeatherIconBitmap(resourceIcon);
                        } else {
                            Log.d("onDataChanged", "no weatherId?");
                            mIconBitmap = null;
                        }

                        invalidate();
                    }
                }
            }
        }
    }

    private int getIconForWeatherId(int weatherId) {
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_cloudy;
        }
        return -1;
    }
}
