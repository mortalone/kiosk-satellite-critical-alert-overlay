// SPDX-License-Identifier: MIT
package me.jxl.kiosk.plugins.criticalalert;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

public class CriticalAlertOverlayPlugin implements KioskPlugin {

    private PluginHost host;
    private Context context;
    private Application application;
    private WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String alertEntity = "binary_sensor.ev_uonsket_dyr_ladning_aktiv";
    private String alertState = "on";
    private String alertTitle = "🚨 ADVARSEL: KRITISK DYR LADNING! 🚨";
    private String alertMessage = "Bilen eller apparater bruger strøm i spærretid! Tag stikket ud af bilen!";
    private String overlayTarget = "Screensaver + Dashboards";
    private boolean pulseAnimation = true;
    private boolean dismissible = true;
    private int dismissMinutes = 15;

    private String currentEntityState = "";
    private long snoozedUntilMs = 0L;
    private boolean screensaverActive = false;
    private final Set<String> subscribedEntities = new HashSet<>();

    private FrameLayout rootOverlayView;
    private LinearLayout alertCard;
    private ValueAnimator pulseAnimator;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private BroadcastReceiver dreamReceiver;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.context = applicationContext(host);
        if (context == null) {
            host.status("Could not obtain Android application context.", true);
            return;
        }
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            host.status("Grant Display over other apps to Kiosk Satellite.", true);
            return;
        }

        registerDreamReceiver();
        registerActivityLifecycle();
        host.subscribe("screensaver.state");
        host.subscribe("screensaver.view");

        applySettings(settings);
        host.status("Critical Alert Overlay ready. Monitoring: " + alertEntity, false);
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        applySettings(settings);
    }

    private synchronized void applySettings(Map<String, Object> settings) {
        if (settings == null) return;

        Object entityVal = settings.get("alertEntity");
        if (entityVal instanceof String && !((String) entityVal).trim().isEmpty()) {
            this.alertEntity = ((String) entityVal).trim();
        }

        Object stateVal = settings.get("alertState");
        if (stateVal instanceof String && !((String) stateVal).trim().isEmpty()) {
            this.alertState = ((String) stateVal).trim();
        }

        Object titleVal = settings.get("alertTitle");
        if (titleVal instanceof String) {
            this.alertTitle = (String) titleVal;
        }

        Object msgVal = settings.get("alertMessage");
        if (msgVal instanceof String) {
            this.alertMessage = (String) msgVal;
        }

        Object targetVal = settings.get("overlayTarget");
        if (targetVal instanceof String) {
            this.overlayTarget = (String) targetVal;
        }

        Object pulseVal = settings.get("pulseAnimation");
        if (pulseVal instanceof Boolean) {
            this.pulseAnimation = (Boolean) pulseVal;
        }

        Object dismissVal = settings.get("dismissible");
        if (dismissVal instanceof Boolean) {
            this.dismissible = (Boolean) dismissVal;
        }

        Object minsVal = settings.get("dismissMinutes");
        if (minsVal instanceof Number) {
            this.dismissMinutes = Math.max(1, ((Number) minsVal).intValue());
        }

        updateSubscriptions();
        main.post(this::evaluateOverlayState);
    }

    private synchronized void updateSubscriptions() {
        if (host == null) return;
        for (String old : new HashSet<>(subscribedEntities)) {
            if (!old.equals(alertEntity)) {
                try { host.unsubscribe("ha.entity." + old); } catch (Throwable ignored) {}
                subscribedEntities.remove(old);
            }
        }
        if (!alertEntity.isEmpty() && subscribedEntities.add(alertEntity)) {
            try { host.subscribe("ha.entity." + alertEntity); } catch (Throwable ignored) {}
        }
    }

    @Override
    public synchronized void execute(String command, Map<String, Object> arguments) {
        if ("test".equals(command) || "show".equals(command)) {
            main.post(() -> showOverlayView("TEST ALARM (Tvungen visning)"));
        } else if ("hide".equals(command) || "dismiss".equals(command)) {
            main.post(this::hideOverlayView);
        } else if ("resetSnooze".equals(command)) {
            snoozedUntilMs = 0L;
            main.post(this::evaluateOverlayState);
        }
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (payload == null) return;

        if ("screensaver.state".equals(event) || "ks.screensaver.state".equals(event)) {
            screensaverActive = Boolean.TRUE.equals(payload.get("active"));
            main.post(this::evaluateOverlayState);
            return;
        }

        String entityId = Objects.toString(payload.get("entity_id"), "");
        if (entityId.equals(alertEntity)) {
            String state = Objects.toString(payload.get("state"), "");
            currentEntityState = state;
            if (!isAlertConditionMet(state)) {
                // Return to normal/safe resets any snooze
                snoozedUntilMs = 0L;
            }
            main.post(this::evaluateOverlayState);
        }
    }

    private boolean isAlertConditionMet(String state) {
        if (state == null) return false;
        String s = state.trim().toLowerCase();
        String target = alertState.trim().toLowerCase();
        if (s.equals(target)) return true;
        if ("on".equals(target) && ("true".equals(s) || "detected".equals(s) || "active".equals(s) || "problem".equals(s))) {
            return true;
        }
        return false;
    }

    private void evaluateOverlayState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::evaluateOverlayState);
            return;
        }

        boolean alertActive = isAlertConditionMet(currentEntityState);
        long now = System.currentTimeMillis();
        boolean isSnoozed = now < snoozedUntilMs;

        boolean targetMatches = true;
        if ("Screensaver only".equals(overlayTarget)) {
            targetMatches = screensaverActive;
        } else if ("Dashboards only".equals(overlayTarget)) {
            targetMatches = !screensaverActive;
        }

        if (alertActive && !isSnoozed && targetMatches) {
            showOverlayView(null);
        } else {
            hideOverlayView();
        }
    }

    private void showOverlayView(String testSubtitle) {
        if (context == null || windowManager == null) return;
        if (rootOverlayView != null) return; // already showing

        rootOverlayView = new FrameLayout(context);
        rootOverlayView.setBackgroundColor(0xDD000000); // 87% black dimmed scrim

        int cardPadding = dp(24);
        alertCard = new LinearLayout(context);
        alertCard.setOrientation(LinearLayout.VERTICAL);
        alertCard.setGravity(Gravity.CENTER_HORIZONTAL);
        alertCard.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);

        // Crimson red gradient with yellow warning border
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFB71C1C); // Material Deep Red
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(4), 0xFFFFEB3B); // Golden yellow warning border
        alertCard.setBackground(cardBg);

        // Warning Icon emoji
        TextView iconView = new TextView(context);
        iconView.setText("🚨");
        iconView.setTextSize(48);
        iconView.setGravity(Gravity.CENTER);
        alertCard.addView(iconView);

        // Headline Title
        TextView titleView = new TextView(context);
        titleView.setText(alertTitle);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(24);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(12);
        alertCard.addView(titleView, titleParams);

        // Message
        TextView msgView = new TextView(context);
        msgView.setText(testSubtitle != null ? testSubtitle : alertMessage);
        msgView.setTextColor(0xFFFFF9C4); // Light yellow text
        msgView.setTextSize(17);
        msgView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgParams.topMargin = dp(12);
        alertCard.addView(msgView, msgParams);

        // Snooze Button
        if (dismissible) {
            Button snoozeBtn = new Button(context);
            snoozeBtn.setText("Afvis midlertidigt (" + dismissMinutes + " min)");
            snoozeBtn.setTextColor(Color.BLACK);
            snoozeBtn.setTextSize(16);
            snoozeBtn.setTypeface(Typeface.DEFAULT_BOLD);

            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(0xFFFFEB3B); // Yellow button
            btnBg.setCornerRadius(dp(12));
            snoozeBtn.setBackground(btnBg);
            snoozeBtn.setPadding(dp(20), dp(10), dp(20), dp(10));

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnParams.topMargin = dp(24);
            snoozeBtn.setOnClickListener(v -> {
                snoozedUntilMs = System.currentTimeMillis() + (dismissMinutes * 60L * 1000L);
                hideOverlayView();
            });
            alertCard.addView(snoozeBtn, btnParams);
        }

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int maxCardWidth = Math.min(dp(540), screenWidth - dp(32));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(maxCardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        rootOverlayView.addView(alertCard, cardLp);

        // Pulse Animation
        if (pulseAnimation) {
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.04f);
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.04f);
            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(alertCard, scaleX, scaleY);
            pulseAnimator.setDuration(800);
            pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.start();
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        try {
            windowManager.addView(rootOverlayView, lp);
            if (host != null) host.log("Critical Alert Overlay displayed!");
        } catch (Throwable t) {
            if (host != null) host.status("Failed to add overlay window: " + t.getMessage(), true);
        }
    }

    private void hideOverlayView() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (rootOverlayView != null && windowManager != null) {
            try {
                windowManager.removeView(rootOverlayView);
            } catch (Throwable ignored) {}
            rootOverlayView = null;
            alertCard = null;
            if (host != null) host.log("Critical Alert Overlay hidden.");
        }
    }

    private int dp(int value) {
        if (context == null) return value;
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void registerDreamReceiver() {
        if (context == null) return;
        dreamReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (Intent.ACTION_DREAMING_STARTED.equals(action)) {
                    screensaverActive = true;
                } else if (Intent.ACTION_DREAMING_STOPPED.equals(action)) {
                    screensaverActive = false;
                }
                main.post(CriticalAlertOverlayPlugin.this::evaluateOverlayState);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        context.registerReceiver(dreamReceiver, filter);
    }

    private void registerActivityLifecycle() {
        Context appContext = context == null ? null : context.getApplicationContext();
        if (!(appContext instanceof Application)) return;
        application = (Application) appContext;
        lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {
                main.post(CriticalAlertOverlayPlugin.this::evaluateOverlayState);
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        };
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    @Override
    public synchronized void stop() {
        main.removeCallbacksAndMessages(null);
        hideOverlayView();
        if (context != null && dreamReceiver != null) {
            try { context.unregisterReceiver(dreamReceiver); } catch (Throwable ignored) {}
        }
        dreamReceiver = null;
        if (application != null && lifecycleCallbacks != null) {
            try { application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks); } catch (Throwable ignored) {}
        }
        lifecycleCallbacks = null;
        subscribedEntities.clear();
    }

    private static Context applicationContext(PluginHost host) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            if (value instanceof Application) return ((Application) value).getApplicationContext();
            if (value instanceof Context) return ((Context) value).getApplicationContext();
        } catch (Throwable ignored) {}

        Object current = host;
        for (int depth = 0; current != null && depth < 5; depth++) {
            Class<?> type = current.getClass();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    for (Field field : c.getDeclaredFields()) {
                        field.setAccessible(true);
                        Object value = field.get(current);
                        if (value instanceof Context) return ((Context) value).getApplicationContext();
                    }
                } catch (Throwable ignored) {}
            }
            break;
        }
        return null;
    }
}
