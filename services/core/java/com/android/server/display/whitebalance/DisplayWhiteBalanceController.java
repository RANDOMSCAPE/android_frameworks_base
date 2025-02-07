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

package com.android.server.display.whitebalance;

import android.annotation.NonNull;
import android.util.Slog;
import android.util.Spline;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.display.color.ColorDisplayService.ColorDisplayServiceInternal;
import com.android.server.display.utils.History;

import java.io.PrintWriter;

/**
 * The DisplayWhiteBalanceController drives display white-balance (automatically correcting the
 * display color temperature depending on the ambient color temperature).
 *
 * The DisplayWhiteBalanceController:
 * - Uses the AmbientColorTemperatureSensor to detect changes in the ambient color temperature;
 * - Uses the AmbientColorTemperatureFilter to average these changes over time, filter out the
 *   noise, and arrive at an estimate of the actual ambient color temperature;
 * - Uses the DisplayWhiteBalanceThrottler to decide whether the display color tempearture should
 *   be updated, suppressing changes that are too frequent or too minor.
 */
public class DisplayWhiteBalanceController implements
        AmbientSensor.AmbientBrightnessSensor.Callbacks,
        AmbientSensor.AmbientColorTemperatureSensor.Callbacks {

    protected static final String TAG = "DisplayWhiteBalanceController";
    protected boolean mLoggingEnabled;

    private boolean mEnabled;

    // To decouple the DisplayPowerController from the DisplayWhiteBalanceController, the DPC
    // implements Callbacks and passes itself to the DWBC so it can call back into it without
    // knowing about it.
    private Callbacks mCallbacks;

    private AmbientSensor.AmbientBrightnessSensor mBrightnessSensor;

    @VisibleForTesting
    AmbientFilter mBrightnessFilter;
    private AmbientSensor.AmbientColorTemperatureSensor mColorTemperatureSensor;

    @VisibleForTesting
    AmbientFilter mColorTemperatureFilter;
    private DisplayWhiteBalanceThrottler mThrottler;

    private final float mLowLightAmbientColorTemperature;
    private final float mHighLightAmbientColorTemperature;

    private float mAmbientColorTemperature;

    @VisibleForTesting
    float mPendingAmbientColorTemperature;
    private float mLastAmbientColorTemperature;

    private ColorDisplayServiceInternal mColorDisplayServiceInternal;

    // The most recent ambient color temperature values are kept for debugging purposes.
    private static final int HISTORY_SIZE = 50;
    private History mAmbientColorTemperatureHistory;

    // Override the ambient color temperature for debugging purposes.
    private float mAmbientColorTemperatureOverride;

    // A piecewise linear relationship between ambient and display color temperatures.
    private Spline.LinearSpline mAmbientToDisplayColorTemperatureSpline;

    // In very low or very high brightness conditions ambient EQ should to set to a default
    // instead of using mAmbientToDisplayColorTemperatureSpline. However, setting ambient EQ
    // based on thresholds can cause the display to rapidly change color temperature. To solve
    // this, mLowLightAmbientBrightnessToBiasSpline and mHighLightAmbientBrightnessToBiasSpline
    // are used to smoothly interpolate from ambient color temperature to the defaults.
    // A piecewise linear relationship between low light brightness and low light bias.
    private Spline.LinearSpline mLowLightAmbientBrightnessToBiasSpline;

    // A piecewise linear relationship between high light brightness and high light bias.
    private Spline.LinearSpline mHighLightAmbientBrightnessToBiasSpline;

    private float mLatestAmbientColorTemperature;
    private float mLatestAmbientBrightness;
    private float mLatestLowLightBias;
    private float mLatestHighLightBias;

    /**
     * @param brightnessSensor
     *      The sensor used to detect changes in the ambient brightness.
     * @param brightnessFilter
     *      The filter used to avergae ambient brightness changes over time, filter out the noise
     *      and arrive at an estimate of the actual ambient brightness.
     * @param colorTemperatureSensor
     *      The sensor used to detect changes in the ambient color temperature.
     * @param colorTemperatureFilter
     *      The filter used to average ambient color temperature changes over time, filter out the
     *      noise and arrive at an estimate of the actual ambient color temperature.
     * @param throttler
     *      The throttler used to determine whether the new display color temperature should be
     *      updated or not.
     * @param lowLightAmbientBrightnesses
     *      The ambient brightness used to map the ambient brightnesses to the biases used to
     *      interpolate to lowLightAmbientColorTemperature.
     * @param lowLightAmbientBiases
     *      The biases used to map the ambient brightnesses to the biases used to interpolate to
     *      lowLightAmbientColorTemperature.
     * @param lowLightAmbientColorTemperature
     *      The ambient color temperature to which we interpolate to based on the low light curve.
     * @param highLightAmbientBrightnesses
     *      The ambient brightness used to map the ambient brightnesses to the biases used to
     *      interpolate to highLightAmbientColorTemperature.
     * @param highLightAmbientBiases
     *      The biases used to map the ambient brightnesses to the biases used to interpolate to
     *      highLightAmbientColorTemperature.
     * @param highLightAmbientColorTemperature
     *      The ambient color temperature to which we interpolate to based on the high light curve.
     * @param ambientColorTemperatures
     *      The ambient color tempeartures used to map the ambient color temperature to the display
     *      color temperature (or null if no mapping is necessary).
     * @param displayColorTemperatures
     *      The display color temperatures used to map the ambient color temperature to the display
     *      color temperature (or null if no mapping is necessary).
     *
     * @throws NullPointerException
     *      - brightnessSensor is null;
     *      - brightnessFilter is null;
     *      - colorTemperatureSensor is null;
     *      - colorTemperatureFilter is null;
     *      - throttler is null.
     */
    public DisplayWhiteBalanceController(
            @NonNull AmbientSensor.AmbientBrightnessSensor brightnessSensor,
            @NonNull AmbientFilter brightnessFilter,
            @NonNull AmbientSensor.AmbientColorTemperatureSensor colorTemperatureSensor,
            @NonNull AmbientFilter colorTemperatureFilter,
            @NonNull DisplayWhiteBalanceThrottler throttler,
            float[] lowLightAmbientBrightnesses, float[] lowLightAmbientBiases,
            float lowLightAmbientColorTemperature,
            float[] highLightAmbientBrightnesses, float[] highLightAmbientBiases,
            float highLightAmbientColorTemperature,
            float[] ambientColorTemperatures, float[] displayColorTemperatures) {
        validateArguments(brightnessSensor, brightnessFilter, colorTemperatureSensor,
                colorTemperatureFilter, throttler);
        mLoggingEnabled = false;
        mEnabled = false;
        mCallbacks = null;
        mBrightnessSensor = brightnessSensor;
        mBrightnessFilter = brightnessFilter;
        mColorTemperatureSensor = colorTemperatureSensor;
        mColorTemperatureFilter = colorTemperatureFilter;
        mThrottler = throttler;
        mLowLightAmbientColorTemperature = lowLightAmbientColorTemperature;
        mHighLightAmbientColorTemperature = highLightAmbientColorTemperature;
        mAmbientColorTemperature = -1.0f;
        mPendingAmbientColorTemperature = -1.0f;
        mLastAmbientColorTemperature = -1.0f;
        mAmbientColorTemperatureHistory = new History(HISTORY_SIZE);
        mAmbientColorTemperatureOverride = -1.0f;

        try {
            mLowLightAmbientBrightnessToBiasSpline = new Spline.LinearSpline(
                    lowLightAmbientBrightnesses, lowLightAmbientBiases);
        } catch (Exception e) {
            Slog.e(TAG, "failed to create low light ambient brightness to bias spline.", e);
            mLowLightAmbientBrightnessToBiasSpline = null;
        }
        if (mLowLightAmbientBrightnessToBiasSpline != null) {
            if (mLowLightAmbientBrightnessToBiasSpline.interpolate(0.0f) != 0.0f ||
                    mLowLightAmbientBrightnessToBiasSpline.interpolate(Float.POSITIVE_INFINITY)
                    != 1.0f) {
                Slog.d(TAG, "invalid low light ambient brightness to bias spline, "
                        + "bias must begin at 0.0 and end at 1.0.");
                mLowLightAmbientBrightnessToBiasSpline = null;
            }
        }

        try {
            mHighLightAmbientBrightnessToBiasSpline = new Spline.LinearSpline(
                    highLightAmbientBrightnesses, highLightAmbientBiases);
        } catch (Exception e) {
            Slog.e(TAG, "failed to create high light ambient brightness to bias spline.", e);
            mHighLightAmbientBrightnessToBiasSpline = null;
        }
        if (mHighLightAmbientBrightnessToBiasSpline != null) {
            if (mHighLightAmbientBrightnessToBiasSpline.interpolate(0.0f) != 0.0f ||
                    mHighLightAmbientBrightnessToBiasSpline.interpolate(Float.POSITIVE_INFINITY)
                    != 1.0f) {
                Slog.d(TAG, "invalid high light ambient brightness to bias spline, "
                        + "bias must begin at 0.0 and end at 1.0.");
                mHighLightAmbientBrightnessToBiasSpline = null;
            }
        }

        if (mLowLightAmbientBrightnessToBiasSpline != null &&
                mHighLightAmbientBrightnessToBiasSpline != null) {
            if (lowLightAmbientBrightnesses[lowLightAmbientBrightnesses.length - 1] >
                    highLightAmbientBrightnesses[0]) {
                Slog.d(TAG, "invalid low light and high light ambient brightness to bias spline "
                        + "combination, defined domains must not intersect.");
                mLowLightAmbientBrightnessToBiasSpline = null;
                mHighLightAmbientBrightnessToBiasSpline = null;
            }
        }

        try {
            mAmbientToDisplayColorTemperatureSpline = new Spline.LinearSpline(
                    ambientColorTemperatures, displayColorTemperatures);
        } catch (Exception e) {
            Slog.e(TAG, "failed to create ambient to display color temperature spline.", e);
            mAmbientToDisplayColorTemperatureSpline = null;
        }

        mColorDisplayServiceInternal = LocalServices.getService(ColorDisplayServiceInternal.class);
    }

    /**
     * Enable/disable the controller.
     *
     * @param enabled
     *      Whether the controller should be on/off.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setEnabled(boolean enabled) {
        if (enabled) {
            return enable();
        } else {
            return disable();
        }
    }

    /**
     * Set an object to call back to when the display color temperature should be updated.
     *
     * @param callbacks
     *      The object to call back to.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setCallbacks(Callbacks callbacks) {
        if (mCallbacks == callbacks) {
            return false;
        }
        mCallbacks = callbacks;
        return true;
    }

    /**
     * Enable/disable logging.
     *
     * @param loggingEnabled
     *      Whether logging should be on/off.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setLoggingEnabled(boolean loggingEnabled) {
        return true;
    }

    /**
     * Set the ambient color temperature override.
     *
     * This is only applied when the ambient color temperature changes or is updated (in which case
     * it overrides the ambient color temperature estimate); in other words, it doesn't necessarily
     * change the display color temperature immediately.
     *
     * @param ambientColorTemperatureOverride
     *      The ambient color temperature override.
     *
     * @return Whether the method succeeded or not.
     */
    public boolean setAmbientColorTemperatureOverride(float ambientColorTemperatureOverride) {
        if (mAmbientColorTemperatureOverride == ambientColorTemperatureOverride) {
            return false;
        }
        mAmbientColorTemperatureOverride = ambientColorTemperatureOverride;
        return true;
    }

    /**
     * Dump the state.
     *
     * @param writer
     *      The writer used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println("DisplayWhiteBalanceController");
        writer.println("  mLoggingEnabled=" + mLoggingEnabled);
        writer.println("  mEnabled=" + mEnabled);
        writer.println("  mCallbacks=" + mCallbacks);
        mBrightnessSensor.dump(writer);
        mBrightnessFilter.dump(writer);
        mColorTemperatureSensor.dump(writer);
        mColorTemperatureFilter.dump(writer);
        mThrottler.dump(writer);
        writer.println("  mLowLightAmbientColorTemperature=" + mLowLightAmbientColorTemperature);
        writer.println("  mHighLightAmbientColorTemperature=" + mHighLightAmbientColorTemperature);
        writer.println("  mAmbientColorTemperature=" + mAmbientColorTemperature);
        writer.println("  mPendingAmbientColorTemperature=" + mPendingAmbientColorTemperature);
        writer.println("  mLastAmbientColorTemperature=" + mLastAmbientColorTemperature);
        writer.println("  mAmbientColorTemperatureHistory=" + mAmbientColorTemperatureHistory);
        writer.println("  mAmbientColorTemperatureOverride=" + mAmbientColorTemperatureOverride);
        writer.println("  mAmbientToDisplayColorTemperatureSpline="
                + mAmbientToDisplayColorTemperatureSpline);
        writer.println("  mLowLightAmbientBrightnessToBiasSpline="
                + mLowLightAmbientBrightnessToBiasSpline);
        writer.println("  mHighLightAmbientBrightnessToBiasSpline="
                + mHighLightAmbientBrightnessToBiasSpline);
    }

    @Override // AmbientSensor.AmbientBrightnessSensor.Callbacks
    public void onAmbientBrightnessChanged(float value) {
        final long time = System.currentTimeMillis();
        mBrightnessFilter.addValue(time, value);
        updateAmbientColorTemperature();
    }

    @Override // AmbientSensor.AmbientColorTemperatureSensor.Callbacks
    public void onAmbientColorTemperatureChanged(float value) {
        final long time = System.currentTimeMillis();
        mColorTemperatureFilter.addValue(time, value);
        updateAmbientColorTemperature();
    }

    /**
     * Updates the ambient color temperature.
     */
    public void updateAmbientColorTemperature() {
        final long time = System.currentTimeMillis();
        float ambientColorTemperature = mColorTemperatureFilter.getEstimate(time);
        mLatestAmbientColorTemperature = ambientColorTemperature;

        if (mAmbientToDisplayColorTemperatureSpline != null && ambientColorTemperature != -1.0f) {
            ambientColorTemperature =
                mAmbientToDisplayColorTemperatureSpline.interpolate(ambientColorTemperature);
        }

        float ambientBrightness = mBrightnessFilter.getEstimate(time);
        mLatestAmbientBrightness = ambientBrightness;

        if (ambientColorTemperature != -1.0f &&
                mLowLightAmbientBrightnessToBiasSpline != null) {
            float bias = mLowLightAmbientBrightnessToBiasSpline.interpolate(ambientBrightness);
            ambientColorTemperature =
                    bias * ambientColorTemperature + (1.0f - bias)
                    * mLowLightAmbientColorTemperature;
            mLatestLowLightBias = bias;
        }
        if (ambientColorTemperature != -1.0f &&
                mHighLightAmbientBrightnessToBiasSpline != null) {
            float bias = mHighLightAmbientBrightnessToBiasSpline.interpolate(ambientBrightness);
            ambientColorTemperature =
                    (1.0f - bias) * ambientColorTemperature + bias
                    * mHighLightAmbientColorTemperature;
            mLatestHighLightBias = bias;
        }

        if (mAmbientColorTemperatureOverride != -1.0f) {
            if (mLoggingEnabled) {
                Slog.d(TAG, "override ambient color temperature: " + ambientColorTemperature
                        + " => " + mAmbientColorTemperatureOverride);
            }
            ambientColorTemperature = mAmbientColorTemperatureOverride;
        }

        // When the display color temperature needs to be updated, we call DisplayPowerController to
        // call our updateColorTemperature. The reason we don't call it directly is that we want
        // all changes to the system to happen in a predictable order in DPC's main loop
        // (updatePowerState).
        if (ambientColorTemperature == -1.0f || mThrottler.throttle(ambientColorTemperature)) {
            return;
        }

        if (mLoggingEnabled) {
            Slog.d(TAG, "pending ambient color temperature: " + ambientColorTemperature);
        }
        mPendingAmbientColorTemperature = ambientColorTemperature;
        if (mCallbacks != null) {
            mCallbacks.updateWhiteBalance();
        }
    }

    /**
     * Updates the display color temperature.
     */
    public void updateDisplayColorTemperature() {
        float ambientColorTemperature = -1.0f;

        // If both the pending and the current ambient color temperatures are -1, it means the DWBC
        // was just enabled, and we use the last ambient color temperature until new sensor events
        // give us a better estimate.
        if (mAmbientColorTemperature == -1.0f && mPendingAmbientColorTemperature == -1.0f) {
            ambientColorTemperature = mLastAmbientColorTemperature;
        }

        // Otherwise, we use the pending ambient color temperature, but only if it's non-trivial
        // and different than the current one.
        if (mPendingAmbientColorTemperature != -1.0f
                && mPendingAmbientColorTemperature != mAmbientColorTemperature) {
            ambientColorTemperature = mPendingAmbientColorTemperature;
        }

        if (ambientColorTemperature == -1.0f) {
            return;
        }

        mAmbientColorTemperature = ambientColorTemperature;
        if (mLoggingEnabled) {
            Slog.d(TAG, "ambient color temperature: " + mAmbientColorTemperature);
        }
        mPendingAmbientColorTemperature = -1.0f;
        mAmbientColorTemperatureHistory.add(mAmbientColorTemperature);
        Slog.d(TAG, "Display cct: " + mAmbientColorTemperature
                + " Latest ambient cct: " + mLatestAmbientColorTemperature
                + " Latest ambient lux: " + mLatestAmbientBrightness
                + " Latest low light bias: " + mLatestLowLightBias
                + " Latest high light bias: " + mLatestHighLightBias);
        mColorDisplayServiceInternal.setDisplayWhiteBalanceColorTemperature(
                (int) mAmbientColorTemperature);
        mLastAmbientColorTemperature = mAmbientColorTemperature;
    }

    /**
     * The DisplayWhiteBalanceController decouples itself from its parent (DisplayPowerController)
     * by providing this interface to implement (and a method to set its callbacks object), and
     * calling these methods.
     */
    public interface Callbacks {

        /**
         * Called whenever the display white-balance state has changed.
         *
         * Usually, this means the estimated ambient color temperature has changed enough, and the
         * display color temperature should be updated; but it is also called if settings change.
         */
        void updateWhiteBalance();
    }

    private void validateArguments(AmbientSensor.AmbientBrightnessSensor brightnessSensor,
            AmbientFilter brightnessFilter,
            AmbientSensor.AmbientColorTemperatureSensor colorTemperatureSensor,
            AmbientFilter colorTemperatureFilter,
            DisplayWhiteBalanceThrottler throttler) {
        Preconditions.checkNotNull(brightnessSensor, "brightnessSensor must not be null");
        Preconditions.checkNotNull(brightnessFilter, "brightnessFilter must not be null");
        Preconditions.checkNotNull(colorTemperatureSensor,
                "colorTemperatureSensor must not be null");
        Preconditions.checkNotNull(colorTemperatureFilter,
                "colorTemperatureFilter must not be null");
        Preconditions.checkNotNull(throttler, "throttler cannot be null");
    }

    private boolean enable() {
        if (mEnabled) {
            return false;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "enabling");
        }
        mEnabled = true;
        mBrightnessSensor.setEnabled(true);
        mColorTemperatureSensor.setEnabled(true);
        return true;
    }

    private boolean disable() {
        if (!mEnabled) {
            return false;
        }
        if (mLoggingEnabled) {
            Slog.d(TAG, "disabling");
        }
        mEnabled = false;
        mBrightnessSensor.setEnabled(false);
        mBrightnessFilter.clear();
        mColorTemperatureSensor.setEnabled(false);
        mColorTemperatureFilter.clear();
        mThrottler.clear();
        mAmbientColorTemperature = -1.0f;
        mPendingAmbientColorTemperature = -1.0f;
        mColorDisplayServiceInternal.resetDisplayWhiteBalanceColorTemperature();
        return true;
    }

}
