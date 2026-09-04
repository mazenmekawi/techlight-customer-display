from __future__ import annotations

from pathlib import Path
import re

ROOT = Path('.')
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/sa/techlight/customerdisplay'
GRADLE = APP / 'build.gradle'
ACTIVITY = JAVA / 'KitchenStableActivity.java'

ALERT_ENGINE = r'''package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Lightweight, dependency-free alert engine for a noisy kitchen.
 * New orders, invoice modifications/add-ons, and late orders use clearly
 * different patterns. The late-order pattern uses the alarm audio stream.
 */
public final class KitchenAlertEngine {
    public enum Kind { NEW_ORDER, MODIFIED, LATE }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ToneGenerator activeTone;
    private static int generation;
    private static long lastNewAt;
    private static long lastModifiedAt;
    private static long lastLateAt;

    private KitchenAlertEngine() { }

    public static long lateAfterMs(SharedPreferences prefs) {
        int minutes = safeInt(prefs, "kds_late_after_minutes", 5);
        minutes = clamp(minutes, 1, 120);
        return minutes * 60_000L;
    }

    public static long lateRepeatMs(SharedPreferences prefs) {
        int minutes = safeInt(prefs, "kds_late_repeat_minutes", 2);
        minutes = clamp(minutes, 1, 30);
        return minutes * 60_000L;
    }

    public static boolean motionEnabled(SharedPreferences prefs) {
        return prefs == null || prefs.getBoolean("kds_motion_enabled", true);
    }

    public static void playNew(Context context, SharedPreferences prefs) {
        if (!allowed(prefs, Kind.NEW_ORDER, false)) return;
        play(context, prefs, Kind.NEW_ORDER, false, -1, true);
    }

    public static void playModified(Context context, SharedPreferences prefs) {
        if (!allowed(prefs, Kind.MODIFIED, false)) return;
        play(context, prefs, Kind.MODIFIED, false, -1, true);
    }

    public static void playLate(Context context, SharedPreferences prefs) {
        if (!allowed(prefs, Kind.LATE, false)) return;
        play(context, prefs, Kind.LATE, false, -1, true);
    }

    public static void preview(Context context, Kind kind, int volume, boolean vibrate) {
        play(context, null, kind, true, clamp(volume, 35, 100), vibrate);
    }

    public static void cancel() {
        synchronized (LOCK) {
            generation++;
            if (activeTone != null) {
                try { activeTone.stopTone(); } catch (Throwable ignored) { }
                try { activeTone.release(); } catch (Throwable ignored) { }
                activeTone = null;
            }
        }
        try { MAIN.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
    }

    private static boolean allowed(SharedPreferences prefs, Kind kind, boolean preview) {
        if (preview) return true;
        if (prefs != null && !prefs.getBoolean("kds_alert_sound", true)
                && !prefs.getBoolean("kds_alert_vibration", true)) return false;
        if (prefs != null && System.currentTimeMillis() < prefs.getLong("kds_alert_snooze_until", 0L)) return false;

        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (kind == Kind.NEW_ORDER) {
                if (now - lastNewAt < 900L) return false;
                lastNewAt = now;
            } else if (kind == Kind.MODIFIED) {
                if (now - lastModifiedAt < 1_200L) return false;
                lastModifiedAt = now;
            } else {
                if (now - lastLateAt < 8_000L) return false;
                lastLateAt = now;
            }
        }
        return true;
    }

    private static void play(Context context, SharedPreferences prefs, Kind kind,
                             boolean preview, int forcedVolume, boolean forcedVibration) {
        if (context == null) return;
        final boolean sound = preview || prefs == null || prefs.getBoolean("kds_alert_sound", true);
        final boolean vibrate = preview ? forcedVibration : prefs == null || prefs.getBoolean("kds_alert_vibration", true);
        final int volume = forcedVolume >= 0 ? forcedVolume : clamp(safeInt(prefs, "kds_alert_volume", 100), 35, 100);

        if (vibrate) vibrate(context.getApplicationContext(), kind);
        if (!sound) return;

        final int stream = kind == Kind.LATE ? AudioManager.STREAM_ALARM : AudioManager.STREAM_NOTIFICATION;
        final int[] tones;
        final int[] durations;
        final int[] gaps;
        if (kind == Kind.NEW_ORDER) {
            tones = new int[] { ToneGenerator.TONE_PROP_BEEP, ToneGenerator.TONE_PROP_BEEP };
            durations = new int[] { 190, 250 };
            gaps = new int[] { 95, 0 };
        } else if (kind == Kind.MODIFIED) {
            tones = new int[] { ToneGenerator.TONE_DTMF_9, ToneGenerator.TONE_DTMF_6,
                    ToneGenerator.TONE_PROP_BEEP2, ToneGenerator.TONE_PROP_BEEP2 };
            durations = new int[] { 220, 220, 330, 330 };
            gaps = new int[] { 80, 90, 130, 0 };
        } else {
            tones = new int[] { ToneGenerator.TONE_PROP_BEEP2, ToneGenerator.TONE_DTMF_9,
                    ToneGenerator.TONE_PROP_BEEP2, ToneGenerator.TONE_DTMF_9,
                    ToneGenerator.TONE_PROP_BEEP2 };
            durations = new int[] { 480, 420, 620, 420, 760 };
            gaps = new int[] { 100, 110, 150, 110, 0 };
        }

        final ToneGenerator generator;
        final int token;
        synchronized (LOCK) {
            generation++;
            token = generation;
            if (activeTone != null) {
                try { activeTone.stopTone(); } catch (Throwable ignored) { }
                try { activeTone.release(); } catch (Throwable ignored) { }
            }
            try {
                generator = new ToneGenerator(stream, volume);
                activeTone = generator;
            } catch (Throwable error) {
                activeTone = null;
                return;
            }
        }

        long at = 0L;
        for (int index = 0; index < tones.length; index++) {
            final int tone = tones[index];
            final int duration = durations[index];
            MAIN.postDelayed(() -> {
                synchronized (LOCK) {
                    if (token != generation || generator != activeTone) return;
                }
                try { generator.startTone(tone, duration); } catch (Throwable ignored) { }
            }, at);
            at += durations[index] + gaps[index];
        }
        MAIN.postDelayed(() -> {
            synchronized (LOCK) {
                if (token != generation || generator != activeTone) return;
                try { generator.stopTone(); } catch (Throwable ignored) { }
                try { generator.release(); } catch (Throwable ignored) { }
                activeTone = null;
            }
        }, at + 120L);
    }

    private static void vibrate(Context context, Kind kind) {
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern;
            if (kind == Kind.NEW_ORDER) {
                pattern = new long[] { 0, 120, 90, 150 };
            } else if (kind == Kind.MODIFIED) {
                pattern = new long[] { 0, 170, 80, 170, 90, 280 };
            } else {
                pattern = new long[] { 0, 300, 110, 300, 110, 520 };
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                //noinspection deprecation
                vibrator.vibrate(pattern, -1);
            }
        } catch (Throwable ignored) { }
    }

    private static int safeInt(SharedPreferences prefs, String key, int fallback) {
        if (prefs == null) return fallback;
        try { return prefs.getInt(key, fallback); }
        catch (Throwable ignored) {
            try { return Integer.parseInt(prefs.getString(key, String.valueOf(fallback))); }
            catch (Throwable ignoredAgain) { return fallback; }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
'''

MOTION_GRID = r'''package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** GridLayout that animates only genuinely new or modified tickets. */
public final class KitchenMotionGrid extends GridLayout {
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Long> SEEN = new LinkedHashMap<>();
    private final SharedPreferences prefs;

    public KitchenMotionGrid(Context context) {
        super(context);
        prefs = context.getSharedPreferences("kitchen_settings_v3", Context.MODE_PRIVATE);
    }

    public KitchenMotionGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
        prefs = context.getSharedPreferences("kitchen_settings_v3", Context.MODE_PRIVATE);
    }

    public KitchenMotionGrid(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        prefs = context.getSharedPreferences("kitchen_settings_v3", Context.MODE_PRIVATE);
    }

    @Override protected void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child == null || !KitchenAlertEngine.motionEnabled(prefs)) return;
        child.post(() -> animateTicket(child));
    }

    private void animateTicket(View child) {
        String signature = signature(child);
        boolean first;
        synchronized (LOCK) {
            first = !SEEN.containsKey(signature);
            SEEN.put(signature, System.currentTimeMillis());
            while (SEEN.size() > 500) {
                String key = SEEN.keySet().iterator().next();
                SEEN.remove(key);
            }
        }
        String lower = signature.toLowerCase(Locale.ROOT);
        boolean modified = lower.contains("إضافة") || lower.contains("تعديل")
                || lower.contains("محدث") || lower.contains("modified")
                || lower.contains("updated") || lower.contains("add-on");

        if (first) {
            child.setAlpha(0f);
            child.setTranslationY(dp(18));
            child.setScaleX(0.985f);
            child.setScaleY(0.985f);
            child.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(230L).setInterpolator(new DecelerateInterpolator()).start();
        } else if (modified) {
            child.animate().cancel();
            child.setScaleX(0.985f);
            child.setScaleY(0.985f);
            child.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(180L).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private String signature(View view) {
        StringBuilder value = new StringBuilder();
        collect(view, value, 0);
        String normalized = value.toString()
                .replaceAll("\\b\\d{1,3}:\\d{2}\\b", "<timer>")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() > 260) normalized = normalized.substring(0, 260);
        return normalized.isEmpty() ? "view:" + System.identityHashCode(view) : normalized;
    }

    private void collect(View view, StringBuilder out, int depth) {
        if (view == null || depth > 8 || out.length() > 320) return;
        Object tag = view.getTag();
        if (tag != null) out.append('|').append(tag);
        CharSequence description = view.getContentDescription();
        if (description != null) out.append('|').append(description);
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) out.append('|').append(text);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out, depth + 1);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
'''

MATERIAL_STYLER = r'''package sa.techlight.customerdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/** Google/Material-inspired polish and a compact kitchen alert control panel. */
public final class KitchenMaterialStyler {
    private static final String CONTROL_TAG = "techlight_kds_alert_control_v69";
    private static final int PURPLE = 0xFF6750A4;
    private static final int PURPLE_DARK = 0xFF4F378B;

    private KitchenMaterialStyler() { }

    public static void apply(Activity activity, FrameLayout shell) {
        if (activity == null || shell == null) return;
        SharedPreferences prefs = activity.getSharedPreferences("kitchen_settings_v3", Activity.MODE_PRIVATE);
        shell.setClipChildren(false);
        shell.setClipToPadding(false);
        polishTree(shell, prefs);

        if (KitchenAlertEngine.motionEnabled(prefs)) {
            shell.setAlpha(0.96f);
            shell.setTranslationY(dp(activity, 6));
            shell.animate().alpha(1f).translationY(0f).setDuration(210L).start();
        }
        addAlertControl(activity, shell, prefs);
    }

    private static void polishTree(View view, SharedPreferences prefs) {
        if (view == null) return;
        if (view.isClickable()) {
            view.setHapticFeedbackEnabled(true);
            if (Build.VERSION.SDK_INT >= 23 && view.getForeground() == null) {
                try {
                    view.setForeground(new RippleDrawable(
                            ColorStateList.valueOf(0x226750A4), null, null));
                } catch (Throwable ignored) { }
            }
            view.setOnTouchListener((target, event) -> {
                if (!KitchenAlertEngine.motionEnabled(prefs)) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    target.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70L).start();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    target.animate().scaleX(1f).scaleY(1f).setDuration(130L).start();
                }
                return false;
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) polishTree(group.getChildAt(i), prefs);
        }
    }

    private static void addAlertControl(Activity activity, FrameLayout shell, SharedPreferences prefs) {
        if (shell.findViewWithTag(CONTROL_TAG) != null) return;
        boolean ar = !"en".equalsIgnoreCase(prefs.getString("language", "ar"));
        TextView control = new TextView(activity);
        control.setTag(CONTROL_TAG);
        control.setText(ar ? "إعداد التنبيهات" : "Alert controls");
        control.setTextColor(Color.WHITE);
        control.setTextSize(13f);
        control.setGravity(Gravity.CENTER);
        control.setPadding(dp(activity, 18), 0, dp(activity, 18), 0);
        control.setBackground(round(PURPLE, 18, 0x336750A4));
        control.setElevation(dp(activity, 8));
        control.setClickable(true);
        control.setFocusable(true);
        control.setOnClickListener(v -> showAlertSettings(activity, prefs, control));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(activity, 48),
                Gravity.BOTTOM | Gravity.START);
        params.setMargins(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
        shell.addView(control, params);
    }

    private static void showAlertSettings(Activity activity, SharedPreferences prefs, TextView control) {
        boolean ar = !"en".equalsIgnoreCase(prefs.getString("language", "ar"));
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 22), dp(activity, 8), dp(activity, 22), dp(activity, 12));
        root.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root);

        TextView intro = text(activity,
                ar ? "ثلاث نغمات مختلفة: طلب جديد، تعديل أو إضافات، وتأخير طلب بنغمة إنذار قوية."
                        : "Three distinct patterns: new order, modifications/add-ons, and a strong late-order alarm.",
                13, 0xFF5F6368, false);
        intro.setPadding(0, 0, 0, dp(activity, 8));
        root.addView(intro);

        CheckBox sound = check(activity, ar ? "تشغيل الأصوات القوية" : "Strong alert sounds",
                prefs.getBoolean("kds_alert_sound", true));
        CheckBox vibration = check(activity, ar ? "اهتزاز الجهاز" : "Device vibration",
                prefs.getBoolean("kds_alert_vibration", true));
        CheckBox motion = check(activity, ar ? "الحركة الخفيفة للكروت والأزرار" : "Light card and button motion",
                prefs.getBoolean("kds_motion_enabled", true));
        root.addView(sound); root.addView(vibration); root.addView(motion);

        root.addView(section(activity, ar ? "قوة الصوت" : "Alert volume"));
        SeekBar volume = new SeekBar(activity);
        volume.setMax(100);
        volume.setProgress(Math.max(35, prefs.getInt("kds_alert_volume", 100)));
        root.addView(volume, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(section(activity, ar ? "يُعد الطلب متأخرًا بعد" : "Mark order late after"));
        final int[] lateValues = new int[] { 2, 5, 10, 15 };
        String[] lateLabels = ar
                ? new String[] { "دقيقتين", "5 دقائق", "10 دقائق", "15 دقيقة" }
                : new String[] { "2 minutes", "5 minutes", "10 minutes", "15 minutes" };
        Spinner late = spinner(activity, lateLabels,
                indexOf(lateValues, prefs.getInt("kds_late_after_minutes", 5)));
        root.addView(late);

        root.addView(section(activity, ar ? "تكرار تنبيه التأخير كل" : "Repeat late alert every"));
        final int[] repeatValues = new int[] { 1, 2, 3, 5 };
        String[] repeatLabels = ar
                ? new String[] { "دقيقة", "دقيقتين", "3 دقائق", "5 دقائق" }
                : new String[] { "1 minute", "2 minutes", "3 minutes", "5 minutes" };
        Spinner repeat = spinner(activity, repeatLabels,
                indexOf(repeatValues, prefs.getInt("kds_late_repeat_minutes", 2)));
        root.addView(repeat);

        root.addView(section(activity, ar ? "اختبار التنبيهات" : "Test alerts"));
        LinearLayout tests = new LinearLayout(activity);
        tests.setOrientation(LinearLayout.HORIZONTAL);
        tests.setGravity(Gravity.CENTER);
        Button testNew = action(activity, ar ? "طلب جديد" : "New");
        Button testModified = action(activity, ar ? "تعديل/إضافة" : "Modified");
        Button testLate = action(activity, ar ? "متأخر" : "Late");
        tests.addView(testNew, weight()); tests.addView(testModified, weight()); tests.addView(testLate, weight());
        root.addView(tests);
        testNew.setOnClickListener(v -> KitchenAlertEngine.preview(activity,
                KitchenAlertEngine.Kind.NEW_ORDER, volume.getProgress(), vibration.isChecked()));
        testModified.setOnClickListener(v -> KitchenAlertEngine.preview(activity,
                KitchenAlertEngine.Kind.MODIFIED, volume.getProgress(), vibration.isChecked()));
        testLate.setOnClickListener(v -> KitchenAlertEngine.preview(activity,
                KitchenAlertEngine.Kind.LATE, volume.getProgress(), vibration.isChecked()));

        Button snooze = action(activity, ar ? "كتم مؤقت 15 دقيقة" : "Snooze 15 minutes");
        snooze.setTextColor(PURPLE_DARK);
        snooze.setBackground(round(0xFFEADDFF, 14, 0x336750A4));
        LinearLayout.LayoutParams snoozeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 48));
        snoozeParams.setMargins(0, dp(activity, 12), 0, 0);
        root.addView(snooze, snoozeParams);
        snooze.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            long until = prefs.getLong("kds_alert_snooze_until", 0L);
            boolean active = until > now;
            prefs.edit().putLong("kds_alert_snooze_until", active ? 0L : now + 15L * 60_000L).apply();
            KitchenAlertEngine.cancel();
            snooze.setText(active
                    ? (ar ? "تم تشغيل التنبيهات" : "Alerts resumed")
                    : (ar ? "تم الكتم لمدة 15 دقيقة" : "Snoozed for 15 minutes"));
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(ar ? "تنبيهات المطبخ" : "Kitchen alerts")
                .setView(scroll)
                .setNegativeButton(ar ? "إلغاء" : "Cancel", null)
                .setPositiveButton(ar ? "حفظ" : "Save", (d, which) -> {
                    prefs.edit()
                            .putBoolean("kds_alert_sound", sound.isChecked())
                            .putBoolean("kds_alert_vibration", vibration.isChecked())
                            .putBoolean("kds_motion_enabled", motion.isChecked())
                            .putInt("kds_alert_volume", Math.max(35, volume.getProgress()))
                            .putInt("kds_late_after_minutes", lateValues[late.getSelectedItemPosition()])
                            .putInt("kds_late_repeat_minutes", repeatValues[repeat.getSelectedItemPosition()])
                            .apply();
                    updateControlText(control, prefs, ar);
                })
                .create();
        dialog.setOnDismissListener(d -> KitchenAlertEngine.cancel());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setDimAmount(0.55f);
    }

    private static void updateControlText(TextView control, SharedPreferences prefs, boolean ar) {
        boolean snoozed = prefs.getLong("kds_alert_snooze_until", 0L) > System.currentTimeMillis();
        control.setText(snoozed
                ? (ar ? "التنبيهات مكتومة" : "Alerts snoozed")
                : (ar ? "إعداد التنبيهات" : "Alert controls"));
    }

    private static CheckBox check(Activity activity, String title, boolean checked) {
        CheckBox box = new CheckBox(activity);
        box.setText(title);
        box.setTextSize(14f);
        box.setTextColor(0xFF202124);
        box.setChecked(checked);
        box.setMinHeight(dp(activity, 46));
        return box;
    }

    private static TextView section(Activity activity, String value) {
        TextView label = text(activity, value, 13, 0xFF3C4043, true);
        label.setPadding(0, dp(activity, 14), 0, dp(activity, 5));
        return label;
    }

    private static TextView text(Activity activity, String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private static Spinner spinner(Activity activity, String[] values, int selected) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(dp(activity, 48));
        return spinner;
    }

    private static Button action(Activity activity, String title) {
        Button button = new Button(activity);
        button.setText(title);
        button.setTextSize(11f);
        button.setTextColor(PURPLE_DARK);
        button.setAllCaps(false);
        button.setMinHeight(dp(activity, 44));
        button.setBackground(round(0xFFF7F2FA, 13, 0x336750A4));
        return button;
    }

    private static LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(3, 0, 3, 0);
        return params;
    }

    private static int indexOf(int[] values, int selected) {
        for (int i = 0; i < values.length; i++) if (values[i] == selected) return i;
        return 0;
    }

    private static GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius * 2f);
        drawable.setStroke(1, stroke);
        return drawable;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
'''


def replace_void_method(source: str, name: str, body: str) -> tuple[str, bool]:
    pattern = re.compile(r'\b(?:public|private|protected)\s+(?:static\s+)?void\s+' + re.escape(name) + r'\s*\([^)]*\)\s*\{')
    match = pattern.search(source)
    if not match:
        return source, False
    opening = source.find('{', match.start(), match.end())
    depth = 0
    closing = -1
    for index in range(opening, len(source)):
        char = source[index]
        if char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                closing = index
                break
    if closing < 0:
        raise SystemExit(f'Could not find closing brace for {name}')
    formatted = '\n' + '\n'.join('        ' + line if line else '' for line in body.splitlines()) + '\n    '
    return source[:opening + 1] + formatted + source[closing:], True


if not ACTIVITY.exists():
    raise SystemExit('KitchenStableActivity.java must be generated before v6.9 patch')

JAVA.mkdir(parents=True, exist_ok=True)
(JAVA / 'KitchenAlertEngine.java').write_text(ALERT_ENGINE, encoding='utf-8')
(JAVA / 'KitchenMotionGrid.java').write_text(MOTION_GRID, encoding='utf-8')
(JAVA / 'KitchenMaterialStyler.java').write_text(MATERIAL_STYLER, encoding='utf-8')

source = ACTIVITY.read_text(encoding='utf-8')

# Material 3 / Google-like neutral palette while retaining TechLight identity.
for old, new in {
    '0xFF7432E0': '0xFF6750A4',
    '0xFF6EA8FF': '0xFF8AB4F8',
    '0xFF4AD6A0': '0xFF81C995',
    '0xFFFFC863': '0xFFFDD663',
    '0xFFFF737D': '0xFFF28B82',
    '0xFF090B0F': '0xFF202124',
    '0xFF12161C': '0xFF292A2D',
    '0xFF1A2028': '0xFF303134',
    '0xFF252D37': '0xFF3C4043',
    '0xFFF4F6F9': '0xFFF8F9FA',
    '0xFFF7F9FC': '0xFFF1F3F4',
    '0xFFE0E5EB': '0xFFDADCE0',
    '0xFF111827': '0xFF202124',
    '0xFF667587': '0xFF5F6368',
}.items():
    source = source.replace(old, new)

# Animate ticket insertion without rebuilding the UI framework or adding heavy libraries.
source, grid_count = re.subn(
    r'(\bboard\s*=\s*)new\s+GridLayout\s*\(\s*this\s*\)',
    r'\1new KitchenMotionGrid(this)', source, count=1)
if grid_count != 1:
    raise SystemExit('Could not route the stable ticket board to KitchenMotionGrid')

# Add Material touch polish and the compact alerts panel after the screen is attached.
if 'KitchenMaterialStyler.apply(this, shell);' not in source:
    if 'setContentView(shell);' not in source:
        raise SystemExit('Stable shell attachment point not found')
    source = source.replace('setContentView(shell);',
                            'setContentView(shell); KitchenMaterialStyler.apply(this, shell);', 1)

# Route every existing beep method to a clear operational pattern.
beep_names = re.findall(r'\bvoid\s+(beep[A-Za-z0-9_]*)\s*\(', source)
patched_late = False
patched_modified = False
patched_new = False
for name in beep_names:
    lower = name.lower()
    if 'late' in lower or 'delay' in lower or 'overdue' in lower:
        body = 'KitchenAlertEngine.playLate(this, getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE));'
        patched_late = True
    elif any(token in lower for token in ('modif', 'update', 'change', 'addon', 'addition')):
        body = 'KitchenAlertEngine.playModified(this, getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE));'
        patched_modified = True
    else:
        body = 'KitchenAlertEngine.playNew(this, getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE));'
        patched_new = True
    source, replaced = replace_void_method(source, name, body)
    if not replaced:
        raise SystemExit(f'Could not patch alert method {name}')

if not patched_late or not patched_modified:
    raise SystemExit('Stable source must expose separate late and modification alerts')

# Make late threshold and repeat interval configurable. Keep declarations as fallbacks,
# but route all operational uses through KitchenAlertEngine.
lines: list[str] = []
for line in source.splitlines():
    if 'private static final long LATE_AFTER_MS' in line:
        line = re.sub(r'=\s*[^;]+;', '= 300_000L;', line)
    elif 'LATE_AFTER_MS' in line:
        line = line.replace('LATE_AFTER_MS',
                            'KitchenAlertEngine.lateAfterMs(getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE))')
    if 'private static final long LATE_REPEAT_MS' in line:
        line = re.sub(r'=\s*[^;]+;', '= 120_000L;', line)
    elif 'LATE_REPEAT_MS' in line:
        line = line.replace('LATE_REPEAT_MS',
                            'KitchenAlertEngine.lateRepeatMs(getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE))')
    lines.append(line)
source = '\n'.join(lines) + '\n'

# Preserve the proven Foodics-like operational core: invoice-first, oldest-first,
# persistent modifications, employee assignment, group routing, and reports.
required_core = (
    'Comparator.comparingLong',
    'KitchenStableMetaStore',
    'showEmployeePicker',
    'showGroupPicker',
    'startOldest',
    'showPerformanceReport',
)
missing_core = [marker for marker in required_core if marker not in source]
if missing_core:
    raise SystemExit('Operational core marker(s) lost: ' + ', '.join(missing_core))

required_v69 = (
    'new KitchenMotionGrid(this)',
    'KitchenMaterialStyler.apply(this, shell)',
    'KitchenAlertEngine.playLate',
    'KitchenAlertEngine.playModified',
)
missing_v69 = [marker for marker in required_v69 if marker not in source]
if missing_v69:
    raise SystemExit('v6.9 marker(s) missing: ' + ', '.join(missing_v69))

ACTIVITY.write_text(source, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle, code_count = re.subn(r'(?m)^\s*versionCode\s+\d+\s*$', '        versionCode 690', gradle, count=1)
gradle, name_count = re.subn(r"(?m)^\s*versionName\s+'[^']+'\s*$", "        versionName '6.9.0-google-ops'", gradle, count=1)
if code_count != 1 or name_count != 1:
    raise SystemExit('Gradle v6.9 version patch failed')
GRADLE.write_text(gradle, encoding='utf-8')

print('Applied TechLight Kitchen v6.9 Google-style motion, strong alerts, and stable oldest-first operations')
