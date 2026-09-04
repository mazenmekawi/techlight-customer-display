from pathlib import Path
import re


def fail(message: str) -> None:
    raise SystemExit("KDS Pro patch failed: " + message)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        fail("missing anchor: " + label)
    return text.replace(old, new, 1)


def replace_regex(text: str, pattern: str, replacement: str, label: str, flags=0) -> str:
    result, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        fail("regex anchor mismatch: " + label + " (" + str(count) + ")")
    return result


# -----------------------------------------------------------------------------
# Manifest + version
# -----------------------------------------------------------------------------
manifest_path = Path("app/src/main/AndroidManifest.xml")
manifest = manifest_path.read_text(encoding="utf-8")
if "android.permission.RECORD_AUDIO" not in manifest:
    manifest = replace_once(
        manifest,
        '    <uses-permission android:name="android.permission.INTERNET" />\n',
        '    <uses-permission android:name="android.permission.INTERNET" />\n'
        '    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n',
        "record audio permission",
    )
if ".KitchenTrainingActivity" not in manifest:
    manifest = replace_once(
        manifest,
        '        <activity android:name=".SettingsActivity" android:exported="false" />\n',
        '        <activity android:name=".SettingsActivity" android:exported="false" />\n'
        '        <activity android:name=".KitchenTrainingActivity" android:exported="false" />\n',
        "training activity",
    )
manifest_path.write_text(manifest, encoding="utf-8")

build_path = Path("app/build.gradle")
build = build_path.read_text(encoding="utf-8")
build = re.sub(r"versionCode\s+\d+", "versionCode 500", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '5.0.0-pro'", build, count=1)
if "sourceCompatibility JavaVersion.VERSION_1_8" not in build:
    build = replace_once(
        build,
        "    defaultConfig {\n",
        "    compileOptions {\n"
        "        sourceCompatibility JavaVersion.VERSION_1_8\n"
        "        targetCompatibility JavaVersion.VERSION_1_8\n"
        "    }\n\n"
        "    defaultConfig {\n",
        "java 8 compile options",
    )
build_path.write_text(build, encoding="utf-8")


# -----------------------------------------------------------------------------
# Hybrid store: preserve local enrichment and correct history retention policy.
# -----------------------------------------------------------------------------
store_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenOrderStoreV2.java")
store = store_path.read_text(encoding="utf-8")
store = re.sub(r"private static final int MAX_HISTORY = \d+;", "private static final int MAX_HISTORY = 1000;", store, count=1)
store = re.sub(
    r"private static final long HISTORY_RETENTION_MS = [^;]+;",
    'private static final String HISTORY_CLEANUP_DUE = "history_cleanup_due";',
    store,
    count=1,
)

old_merge = '''        if (!incoming.items.isEmpty()) {
            merged.items.clear();
            for (KitchenOrder.Item item : incoming.items) merged.items.add(KitchenOrder.copyItem(item));
        }
'''
new_merge = '''        if (!incoming.items.isEmpty()) {
            merged.items.clear();
            merged.items.addAll(KitchenItemMerger.merge(previous.items, incoming.items));
        }
'''
store = replace_once(store, old_merge, new_merge, "hybrid mergeExisting items")

old_promote = '''        if (!incoming.items.isEmpty()) {
            promoted.items.clear();
            for (KitchenOrder.Item item : incoming.items) promoted.items.add(KitchenOrder.copyItem(item));
        }
'''
new_promote = '''        if (!incoming.items.isEmpty()) {
            promoted.items.clear();
            promoted.items.addAll(KitchenItemMerger.merge(weak.items, incoming.items));
        }
'''
store = replace_once(store, old_promote, new_promote, "hybrid promoted items")

new_prune = '''    private boolean pruneHistory(long now) {
        boolean changed = false;
        long due = preferences.getLong(HISTORY_CLEANUP_DUE, 0L);
        if (history.size() <= MAX_HISTORY) {
            if (due > 0L) preferences.edit().remove(HISTORY_CLEANUP_DUE).apply();
            return false;
        }
        if (due <= 0L) {
            preferences.edit().putLong(HISTORY_CLEANUP_DUE, addBusinessDays(now, 2)).apply();
            return false;
        }
        if (now < due) return false;
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
            changed = true;
        }
        preferences.edit().remove(HISTORY_CLEANUP_DUE).apply();
        return changed;
    }

    public synchronized long historyCleanupDueAt() {
        if (history.size() <= MAX_HISTORY) return 0L;
        long due = preferences.getLong(HISTORY_CLEANUP_DUE, 0L);
        if (due <= 0L) {
            due = addBusinessDays(System.currentTimeMillis(), 2);
            preferences.edit().putLong(HISTORY_CLEANUP_DUE, due).apply();
        }
        return due;
    }

    public synchronized int historyOverflowCount() {
        return Math.max(0, history.size() - MAX_HISTORY);
    }

    private static long addBusinessDays(long start, int days) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(start);
        int added = 0;
        while (added < days) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1);
            int weekday = calendar.get(java.util.Calendar.DAY_OF_WEEK);
            if (weekday != java.util.Calendar.FRIDAY && weekday != java.util.Calendar.SATURDAY) added++;
        }
        return calendar.getTimeInMillis();
    }

    private static ArrayList<KitchenOrder> copies'''
store = replace_regex(
    store,
    r"    private boolean pruneHistory\(long now\) \{.*?\n    \}\n\n    private static ArrayList<KitchenOrder> copies",
    new_prune,
    "history cleanup policy",
    flags=re.S,
)
store_path.write_text(store, encoding="utf-8")


# -----------------------------------------------------------------------------
# Main KDS activity integration.
# -----------------------------------------------------------------------------
activity_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java")
activity = activity_path.read_text(encoding="utf-8")

if "android.content.pm.PackageManager" not in activity:
    activity = replace_once(
        activity,
        "import android.content.Intent;\n",
        "import android.content.Intent;\nimport android.content.pm.PackageManager;\n",
        "PackageManager import",
    )
if "java.util.LinkedHashSet" not in activity:
    activity = replace_once(
        activity,
        "import java.util.List;\n",
        "import java.util.List;\nimport java.util.LinkedHashSet;\nimport java.util.Set;\n",
        "group collection imports",
    )

activity = replace_once(
    activity,
    "    private ToneGenerator tone;\n",
    "    private ToneGenerator tone;\n"
    "    private KitchenProState proState;\n"
    "    private KitchenVoiceController voiceController;\n"
    "    private KitchenStatusSyncQueue statusSync;\n"
    "    private TextView voiceButton;\n"
    "    private TextView groupButton;\n"
    "    private boolean historyWarningVisible;\n",
    "KDS Pro fields",
)

activity = replace_once(
    activity,
    "            try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 88); } catch (Throwable ignored) { }\n",
    "            try { tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 88); } catch (Throwable ignored) { }\n"
    "            try {\n"
    "                proState = new KitchenProState(this);\n"
    "                statusSync = new KitchenStatusSyncQueue(this, session.token(), settings.getString(\"status_endpoint\", \"\"));\n"
    "                statusSync.setListener((orderId, synced, detail) -> {\n"
    "                    if (proState != null) proState.recordSync(orderId, detail, synced);\n"
    "                    if (synced) runOnUiThread(() -> Toast.makeText(KitchenActivityV42.this, ar() ? \"تمت مزامنة الحالة\" : \"Status synced\", Toast.LENGTH_SHORT).show());\n"
    "                });\n"
    "                voiceController = new KitchenVoiceController(this, new KitchenVoiceController.Listener() {\n"
    "                    @Override public void onVoiceText(String value, boolean wake) { runOnUiThread(() -> handleVoiceText(value, wake)); }\n"
    "                    @Override public void onVoiceState(String value, boolean error) { runOnUiThread(() -> updateVoiceState(value, error)); }\n"
    "                });\n"
    "                voiceController.setLanguage(ar());\n"
    "            } catch (Throwable error) { recordError(\"pro-init\", error); }\n",
    "KDS Pro initialization",
)

activity = replace_once(
    activity,
    "            handler.postDelayed(watchdog, 10_000L);\n",
    "            handler.postDelayed(watchdog, 10_000L);\n"
    "            if (voiceController != null && settings.getBoolean(\"voice_wake\", false)) voiceController.setWakeEnabled(true);\n"
    "            if (statusSync != null) statusSync.flush();\n",
    "start Pro services",
)

# Top bar group and voice controls.
top_anchor = "        top.addView(connectionText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));\n"
activity = replace_once(
    activity,
    top_anchor,
    top_anchor
    + "        groupButton = action(KitchenGroupPolicy.displaySelection(settings.getString(\"selected_groups\", KitchenGroupPolicy.ALL), ar()), false);\n"
    + "        groupButton.setContentDescription(ar() ? \"اختيار مجموعة المطبخ\" : \"Select kitchen group\");\n"
    + "        groupButton.setOnClickListener(v -> showGroupDialog());\n"
    + "        LinearLayout.LayoutParams gb = new LinearLayout.LayoutParams(dp(wide() ? 150 : 106), dp(42)); gb.setMargins(dp(7), 0, 0, 0); top.addView(groupButton, gb);\n"
    + "        voiceButton = action(\"🎙\", false); voiceButton.setContentDescription(ar() ? \"الأوامر الصوتية\" : \"Voice commands\");\n"
    + "        voiceButton.setOnClickListener(v -> { if (!settings.getBoolean(\"voice_enabled\", true)) Toast.makeText(this, ar() ? \"فعّل الأوامر الصوتية من الإعدادات\" : \"Enable voice commands in Settings\", Toast.LENGTH_SHORT).show(); else if (voiceController != null) voiceController.listenOnce(); });\n"
    + "        LinearLayout.LayoutParams vb = new LinearLayout.LayoutParams(dp(48), dp(42)); vb.setMargins(dp(7), 0, 0, 0); top.addView(voiceButton, vb);\n",
    "top Pro controls",
)

# Configurable late thresholds.
activity = activity.replace("now - ref.startedAt >= LATE_AFTER_MS", "now - ref.startedAt >= lateAfterMs()")
activity = activity.replace("System.currentTimeMillis() - order.createdAt >= LATE_AFTER_MS", "System.currentTimeMillis() - order.createdAt >= lateAfterMs()")

# Observe merged tickets, filter by group and apply deterministic ordering.
activity = replace_once(
    activity,
    "        List<KitchenOrder> active = store.active(); List<KitchenOrder> history = store.history(); updateTabs(active, history.size());\n",
    "        List<KitchenOrder> active = store.active(); List<KitchenOrder> history = store.history();\n"
    "        if (proState != null) for (KitchenOrder value : active) proState.observe(value);\n"
    "        maybeShowHistoryCleanupWarning(history.size());\n"
    "        updateTabs(active, history.size());\n",
    "observe active orders",
)
activity = replace_once(
    activity,
    "        for (KitchenOrder order : source) { if (filter != Filter.HISTORY && !hasInvoice(order)) continue; if (matchesFilter(order)) visible.add(order); }\n",
    "        for (KitchenOrder order : source) {\n"
    "            if (filter != Filter.HISTORY && !hasInvoice(order)) continue;\n"
    "            if (!KitchenGroupPolicy.matches(order, settings.getString(\"selected_groups\", KitchenGroupPolicy.ALL))) continue;\n"
    "            if (matchesFilter(order)) visible.add(order);\n"
    "        }\n"
    "        if (filter != Filter.HISTORY) KitchenPriorityPolicy.sort(visible, proState, System.currentTimeMillis(), warningAfterMs(), lateAfterMs(), settings.getBoolean(\"smart_sorting\", true));\n",
    "group filter and deterministic sorting",
)

# Additional items and employee presentation.
activity = replace_regex(
    activity,
    r"(    private View orderCard\(KitchenOrder order, boolean history\) \{\n        LinearLayout card = [^\n]+\n)",
    r"\1        int additionalCount = proState == null ? 0 : proState.pendingAdditions(order.id);\n",
    "order card additional count",
)
activity = replace_once(
    activity,
    "        card.addView(chips);\n",
    "        card.addView(chips);\n"
    "        String employee = proState == null ? \"\" : proState.employee(order.id);\n"
    "        if (!clean(employee).isEmpty()) { TextView employeeChip = chip((ar() ? \"الموظف: \" : \"Employee: \") + employee, surface2, muted); LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29)); ep.setMargins(0, 0, 0, dp(5)); card.addView(employeeChip, ep); }\n"
    "        String groupText = String.join(\" • \", KitchenGroupPolicy.groups(order));\n"
    "        if (!clean(groupText).isEmpty() && !KitchenGroupPolicy.GENERAL.equals(groupText)) { TextView groupChip = chip((ar() ? \"المجموعة: \" : \"Group: \") + groupText, surface2, purple); LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29)); gp.setMargins(0, 0, 0, dp(5)); card.addView(groupChip, gp); }\n",
    "employee and group chips",
)
activity = replace_once(
    activity,
    "        if (!history && order.changedAt > 0L && System.currentTimeMillis() - order.changedAt < 45_000L) card.addView(alertBand(t(\"modified\"), amber));\n",
    "        if (!history && additionalCount > 0) card.addView(alertBand(\"+\" + additionalCount + (ar() ? \" أصناف جديدة — تحتاج مراجعة\" : \" NEW ITEMS — review required\"), red));\n"
    "        else if (!history && order.changedAt > 0L && System.currentTimeMillis() - order.changedAt < 45_000L) card.addView(alertBand(t(\"modified\"), amber));\n",
    "persistent additions alert",
)
activity = replace_once(
    activity,
    "        if (!history && order.kitchenStatus != KitchenOrder.Status.READY) {\n            TextView primary = action(order.kitchenStatus == KitchenOrder.Status.NEW ? t(\"prepare\") : t(\"readyAction\"), true); primary.setOnClickListener(v -> advance(order)); LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)); ap.setMargins(0, dp(11), 0, 0); card.addView(primary, ap);\n        } else if (!history && order.kitchenStatus == KitchenOrder.Status.READY) {\n",
    "        if (!history && (order.kitchenStatus != KitchenOrder.Status.READY || additionalCount > 0)) {\n"
    "            String primaryLabel = additionalCount > 0 ? (ar() ? \"مراجعة +\" + additionalCount + \" إضافات\" : \"Review +\" + additionalCount + \" additions\") : (order.kitchenStatus == KitchenOrder.Status.NEW ? t(\"prepare\") : t(\"readyAction\"));\n"
    "            TextView primary = action(primaryLabel, true); primary.setOnClickListener(v -> advance(order)); LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)); ap.setMargins(0, dp(11), 0, 0); card.addView(primary, ap);\n"
    "        } else if (!history && order.kitchenStatus == KitchenOrder.Status.READY) {\n",
    "addition-aware primary action",
)

# Enrich the parsed local payload before it enters the durable merge store.
onraw_pattern = r"(    @Override public void onRaw\(String raw\) \{.*?)(\n    \}\n    @Override public void onOrder)"
onraw_match = re.search(onraw_pattern, activity, flags=re.S)
if not onraw_match:
    fail("onRaw method")
onraw_body = onraw_match.group(1)
if "KitchenLocalEnricher.enrich" not in onraw_body:
    onraw_body = onraw_body.replace(
        "            processSignal(signal);",
        "            if (signal != null && signal.order != null) KitchenLocalEnricher.enrich(raw, signal.order);\n            processSignal(signal);",
        1,
    )
activity = activity[:onraw_match.start(1)] + onraw_body + activity[onraw_match.end(1):]

# Observe after every durable upsert as well (covers cloud-only updates).
activity = activity.replace(
    "boolean changed = store.upsert(order); if (before == null)",
    "boolean changed = store.upsert(order); KitchenOrder observed = store.findByNumber(number); if (proState != null && observed != null) proState.observe(observed); if (before == null)",
    1,
)

# Replace lifecycle actions with audited employee-aware transitions.
new_advance = '''    private void advance(KitchenOrder order) {
        if (store == null || order == null) return;
        int additions = proState == null ? 0 : proState.pendingAdditions(order.id);
        String employee = preferredEmployee(order);
        if (additions > 0) {
            if (proState != null) proState.acknowledge(order.id, employee);
            if (order.kitchenStatus == KitchenOrder.Status.READY) {
                applyStatus(order, KitchenOrder.Status.PREPARING, employee);
            } else {
                beep(true);
                renderBoard();
            }
            voiceReply(ar() ? "تمت مراجعة الإضافات" : "Additions reviewed", ar());
            return;
        }
        if (order.kitchenStatus == KitchenOrder.Status.NEW) {
            if (clean(employee).isEmpty()) { promptEmployeeAndStart(order); return; }
            applyStatus(order, KitchenOrder.Status.PREPARING, employee);
        } else if (order.kitchenStatus == KitchenOrder.Status.PREPARING) {
            applyStatus(order, KitchenOrder.Status.READY, employee);
        }
    }

    private void promptEmployeeAndStart(KitchenOrder order) {
        EditText input = new EditText(this);
        input.setHint(ar() ? "اسم الموظف" : "Employee name");
        input.setSingleLine(true);
        input.setText(settings.getString("employee_name", ""));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(ar() ? "من بدأ التحضير؟" : "Who started preparation?")
                .setView(input)
                .setPositiveButton(ar() ? "بدء التحضير" : "Start", null)
                .setNegativeButton(ar() ? "إلغاء" : "Cancel", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String employee = clean(input.getText().toString());
            if (employee.isEmpty()) { input.setError(ar() ? "اكتب اسم الموظف" : "Enter employee name"); return; }
            settings.edit().putString("employee_name", employee).apply();
            if (proState != null) proState.setEmployee(order.id, employee);
            dialog.dismiss();
            applyStatus(order, KitchenOrder.Status.PREPARING, employee);
        }));
        dialog.show();
    }

    private String preferredEmployee(KitchenOrder order) {
        String employee = proState == null || order == null ? "" : proState.employee(order.id);
        if (employee.isEmpty()) employee = settings == null ? "" : clean(settings.getString("employee_name", ""));
        return employee;
    }

    private void applyStatus(KitchenOrder order, KitchenOrder.Status status, String employee) {
        if (store == null || order == null || status == null) return;
        if (proState != null && !clean(employee).isEmpty()) proState.setEmployee(order.id, employee);
        store.setStatus(order.id, status);
        if (proState != null) proState.recordStatus(order, status, employee);
        if (statusSync != null) statusSync.enqueue(order, status, employee);
        if (status == KitchenOrder.Status.READY || status == KitchenOrder.Status.DONE) beep(true); else beep(false);
        renderBoard();
    }

    private void showTicketMenu'''
activity = replace_regex(
    activity,
    r"    private void advance\(KitchenOrder order\) \{.*?\n    \}\n\n    private void showTicketMenu",
    new_advance,
    "audited lifecycle methods",
    flags=re.S,
)

activity = replace_regex(
    activity,
    r"    private void showTicketMenu\(KitchenOrder order\) \{.*?\n    \}\n\n    private void showOrderDetails",
    '''    private void showTicketMenu(KitchenOrder order) {
        if (order == null) return;
        ArrayList<String> choices = new ArrayList<>();
        if (proState != null && proState.pendingAdditions(order.id) > 0) choices.add(ar() ? "مراجعة الإضافات" : "Review additions");
        if (order.kitchenStatus == KitchenOrder.Status.READY) choices.add(t("complete"));
        choices.add(ar() ? "إغلاق" : "Close");
        new AlertDialog.Builder(this).setTitle("#" + invoiceNumber(order))
                .setItems(choices.toArray(new String[0]), (d, which) -> {
                    String selected = choices.get(which);
                    if (selected.contains("الإضافات") || selected.toLowerCase(Locale.US).contains("addition")) {
                        if (proState != null) proState.acknowledge(order.id, preferredEmployee(order));
                        renderBoard();
                    } else if (selected.equals(t("complete"))) {
                        applyStatus(order, KitchenOrder.Status.DONE, preferredEmployee(order));
                    }
                }).show();
    }

    private void showOrderDetails''',
    "ticket menu",
    flags=re.S,
)

# Add group, employee and true timeline to details.
details_pattern = r"(    private void showOrderDetails\(KitchenOrder order, boolean history\) \{.*?)(\n    \}\n\n    @Override public void onConnected)"
details_match = re.search(details_pattern, activity, flags=re.S)
if not details_match:
    fail("showOrderDetails method")
details = details_match.group(1)
needle = "        new AlertDialog.Builder(this).setTitle(\"#\" + invoiceNumber(order)).setMessage(out.toString()).setPositiveButton(ar() ? \"إغلاق\" : \"Close\", null).show();"
if needle not in details:
    fail("details dialog anchor")
details = details.replace(
    needle,
    "        String employee = proState == null ? \"\" : proState.employee(order.id); if (!clean(employee).isEmpty()) out.append('\\n').append(ar() ? \"الموظف: \" : \"Employee: \").append(employee);\n"
    "        out.append('\\n').append(ar() ? \"المجموعة: \" : \"Group: \").append(String.join(\" • \", KitchenGroupPolicy.groups(order)));\n"
    "        int additions = proState == null ? 0 : proState.pendingAdditions(order.id); if (additions > 0) out.append('\\n').append(ar() ? \"إضافات بانتظار المراجعة: \" : \"Additions awaiting review: \").append(additions);\n"
    "        String timeline = proState == null ? \"\" : proState.timeline(order.id, ar()); if (!timeline.isEmpty()) out.append(\"\\n\\n\").append(ar() ? \"سجل الأحداث\\n\" : \"Event timeline\\n\").append(timeline);\n"
    + needle,
    1,
)
activity = activity[:details_match.start(1)] + details + activity[details_match.end(1):]

# Pro settings controls are saved immediately by their own button, leaving the
# verified base settings save path untouched.
settings_anchor = "        CheckBox images = new CheckBox(this); images.setText(t(\"images\")); images.setChecked(settings.getBoolean(\"show_images\", true)); panel.addView(images);\n"
pro_settings = '''        CheckBox voiceEnabled = new CheckBox(this); voiceEnabled.setText(ar() ? "الأوامر الصوتية العربية والإنجليزية" : "Arabic and English voice commands"); voiceEnabled.setChecked(settings.getBoolean("voice_enabled", true)); panel.addView(voiceEnabled);
        CheckBox wakeEnabled = new CheckBox(this); wakeEnabled.setText(ar() ? "الاستماع لعبارة Hi TechPro" : "Listen for Hi TechPro"); wakeEnabled.setChecked(settings.getBoolean("voice_wake", false)); panel.addView(wakeEnabled);
        CheckBox smartSorting = new CheckBox(this); smartSorting.setText(ar() ? "ترتيب ذكي مع قواعد ثابتة" : "Smart priority with deterministic fallback"); smartSorting.setChecked(settings.getBoolean("smart_sorting", true)); panel.addView(smartSorting);
        CheckBox soundsEnabled = new CheckBox(this); soundsEnabled.setText(ar() ? "أصوات التنبيه" : "Notification sounds"); soundsEnabled.setChecked(settings.getBoolean("sounds_enabled", true)); panel.addView(soundsEnabled);
        EditText employeeName = new EditText(this); employeeName.setHint(ar() ? "اسم الموظف الافتراضي" : "Default employee name"); employeeName.setSingleLine(true); employeeName.setText(settings.getString("employee_name", "")); panel.addView(employeeName);
        EditText warningMinutes = new EditText(this); warningMinutes.setHint(ar() ? "وقت التحذير بالدقائق (الافتراضي 2)" : "Warning minutes (default 2)"); warningMinutes.setInputType(InputType.TYPE_CLASS_NUMBER); warningMinutes.setSingleLine(true); warningMinutes.setText(String.valueOf(settings.getInt("warning_minutes", 2))); panel.addView(warningMinutes);
        EditText lateMinutes = new EditText(this); lateMinutes.setHint(ar() ? "وقت التأخير بالدقائق (الافتراضي 5)" : "Late minutes (default 5)"); lateMinutes.setInputType(InputType.TYPE_CLASS_NUMBER); lateMinutes.setSingleLine(true); lateMinutes.setText(String.valueOf(settings.getInt("late_minutes", 5))); panel.addView(lateMinutes);
        EditText screenPin = new EditText(this); screenPin.setHint(ar() ? "رمز الشاشة" : "Screen PIN"); screenPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); screenPin.setSingleLine(true); screenPin.setText(settings.getString("pin", "0000")); panel.addView(screenPin);
        TextView groupSelect = action(ar() ? "اختيار مجموعات هذه الشاشة" : "Select this screen's groups", false); groupSelect.setOnClickListener(v -> showGroupDialog()); LinearLayout.LayoutParams gsp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); gsp.setMargins(0, dp(8), 0, 0); panel.addView(groupSelect, gsp);
        TextView training = action(ar() ? "وضع التدريب" : "Training mode", false); training.setOnClickListener(v -> { Intent intent = new Intent(this, KitchenTrainingActivity.class); intent.putExtra("arabic", ar()); startActivity(intent); }); LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); trp.setMargins(0, dp(8), 0, 0); panel.addView(training, trp);
        TextView reports = action(ar() ? "تقرير الأداء" : "Performance report", false); reports.setOnClickListener(v -> showPerformanceReport()); LinearLayout.LayoutParams rep = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)); rep.setMargins(0, dp(8), 0, 0); panel.addView(reports, rep);
        TextView savePro = action(ar() ? "حفظ إعدادات Pro" : "Save Pro settings", true); savePro.setOnClickListener(v -> {
            int warning = safeInt(warningMinutes.getText().toString(), 2, 1, 240);
            int late = safeInt(lateMinutes.getText().toString(), 5, warning, 480);
            String pinValue = clean(screenPin.getText().toString()); if (pinValue.isEmpty()) pinValue = "0000";
            settings.edit().putBoolean("voice_enabled", voiceEnabled.isChecked()).putBoolean("voice_wake", wakeEnabled.isChecked())
                    .putBoolean("smart_sorting", smartSorting.isChecked()).putBoolean("sounds_enabled", soundsEnabled.isChecked())
                    .putString("employee_name", clean(employeeName.getText().toString())).putInt("warning_minutes", warning)
                    .putInt("late_minutes", late).putString("pin", pinValue).apply();
            if (voiceController != null) { voiceController.setLanguage(ar()); voiceController.setWakeEnabled(voiceEnabled.isChecked() && wakeEnabled.isChecked()); }
            renderBoard();
            Toast.makeText(this, ar() ? "تم حفظ إعدادات Pro" : "Pro settings saved", Toast.LENGTH_SHORT).show();
        }); LinearLayout.LayoutParams spp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)); spp.setMargins(0, dp(10), 0, 0); panel.addView(savePro, spp);
'''
activity = replace_once(activity, settings_anchor, settings_anchor + pro_settings, "Pro settings panel")

# Master PIN remains hidden but always works.
activity = activity.replace(
    "if (!saved.equals(pin.getText().toString()))",
    "if (!saved.equals(pin.getText().toString()) && !\"1997\".equals(pin.getText().toString()))",
    1,
)

# Helpers inserted before visual utility methods.
helpers = r'''    private long warningAfterMs() {
        int minutes = settings == null ? 2 : settings.getInt("warning_minutes", 2);
        return Math.max(1, minutes) * 60_000L;
    }

    private long lateAfterMs() {
        int warning = settings == null ? 2 : settings.getInt("warning_minutes", 2);
        int minutes = settings == null ? 5 : settings.getInt("late_minutes", 5);
        return Math.max(warning, Math.max(1, minutes)) * 60_000L;
    }

    private boolean soundsEnabled() { return settings == null || settings.getBoolean("sounds_enabled", true); }

    private int safeInt(String value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(clean(value)))); }
        catch (Throwable ignored) { return fallback; }
    }

    private void showGroupDialog() {
        if (store == null || settings == null) return;
        List<String> discovered = KitchenGroupPolicy.discover(store.active());
        if (discovered.isEmpty()) discovered.add(KitchenGroupPolicy.GENERAL);
        String selectedCsv = settings.getString("selected_groups", KitchenGroupPolicy.ALL);
        boolean all = selectedCsv.isEmpty() || KitchenGroupPolicy.ALL.equals(selectedCsv);
        String[] labels = new String[discovered.size() + 1];
        boolean[] checked = new boolean[labels.length];
        labels[0] = ar() ? "كل المجموعات" : "All groups";
        checked[0] = all;
        Set<String> selected = new LinkedHashSet<>();
        if (!all) for (String value : selectedCsv.split(",")) selected.add(clean(value).toLowerCase(Locale.ROOT));
        for (int i = 0; i < discovered.size(); i++) {
            labels[i + 1] = discovered.get(i);
            checked[i + 1] = all || selected.contains(discovered.get(i).toLowerCase(Locale.ROOT));
        }
        final List<String> groups = discovered;
        new AlertDialog.Builder(this)
                .setTitle(ar() ? "مجموعات هذه الشاشة" : "This screen's groups")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    checked[which] = isChecked;
                    if (which == 0 && isChecked) for (int i = 1; i < checked.length; i++) checked[i] = true;
                    if (which > 0 && !isChecked) checked[0] = false;
                })
                .setPositiveButton(ar() ? "حفظ" : "Save", (dialog, which) -> {
                    if (checked[0]) settings.edit().putString("selected_groups", KitchenGroupPolicy.ALL).apply();
                    else {
                        StringBuilder value = new StringBuilder();
                        for (int i = 1; i < checked.length; i++) if (checked[i]) {
                            if (value.length() > 0) value.append(',');
                            value.append(groups.get(i - 1));
                        }
                        settings.edit().putString("selected_groups", value.length() == 0 ? KitchenGroupPolicy.ALL : value.toString()).apply();
                    }
                    if (groupButton != null) groupButton.setText(KitchenGroupPolicy.displaySelection(settings.getString("selected_groups", KitchenGroupPolicy.ALL), ar()));
                    renderBoard();
                })
                .setNegativeButton(ar() ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void maybeShowHistoryCleanupWarning(int historyCount) {
        if (store == null || historyCount <= 1000 || historyWarningVisible) return;
        long due = store.historyCleanupDueAt();
        if (due <= 0L) return;
        long lastShown = settings == null ? 0L : settings.getLong("history_warning_shown", 0L);
        if (System.currentTimeMillis() - lastShown < 6L * 60L * 60L * 1000L) return;
        historyWarningVisible = true;
        if (settings != null) settings.edit().putLong("history_warning_shown", System.currentTimeMillis()).apply();
        String date;
        try { date = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date(due)); }
        catch (Throwable ignored) { date = "—"; }
        new AlertDialog.Builder(this)
                .setTitle(ar() ? "تنبيه إدارة السجل" : "History maintenance warning")
                .setMessage((ar() ? "تجاوز السجل 1000 فاتورة. سيتم حذف الأقدم بعد يومي عمل فقط، بدون لمس الطلبات النشطة.\nموعد التنظيف: " : "History exceeded 1000 invoices. Oldest records will be trimmed after two business days; active orders are never touched.\nCleanup due: ") + date)
                .setPositiveButton(ar() ? "فهمت" : "OK", (d, w) -> historyWarningVisible = false)
                .setOnDismissListener(d -> historyWarningVisible = false)
                .show();
    }

    private void showPerformanceReport() {
        if (store == null) return;
        List<KitchenOrder> active = store.active();
        List<KitchenOrder> history = store.history();
        long total = 0L, prep = 0L;
        int prepCount = 0, late = 0;
        long now = System.currentTimeMillis();
        for (KitchenOrder order : history) {
            total += finalDuration(order);
            long value = prepDuration(order);
            if (value > 0L) { prep += value; prepCount++; }
            if (finalDuration(order) >= lateAfterMs()) late++;
        }
        int activeLate = 0;
        for (KitchenOrder order : active) if (now - order.createdAt >= lateAfterMs()) activeLate++;
        StringBuilder report = new StringBuilder();
        report.append(ar() ? "الطلبات النشطة: " : "Active orders: ").append(active.size()).append('\n');
        report.append(ar() ? "المتأخرة الآن: " : "Currently delayed: ").append(activeLate).append('\n');
        report.append(ar() ? "الفواتير المكتملة: " : "Completed invoices: ").append(history.size()).append('\n');
        report.append(ar() ? "متوسط المدة: " : "Average total time: ").append(history.isEmpty() ? "—" : formatAge(total / history.size())).append('\n');
        report.append(ar() ? "متوسط التحضير: " : "Average preparation: ").append(prepCount == 0 ? "—" : formatAge(prep / prepCount)).append('\n');
        report.append(ar() ? "طلبات مكتملة متأخرة: " : "Delayed completed orders: ").append(late).append('\n');
        report.append(ar() ? "أحداث الإضافات: " : "Additional-item events: ").append(proState == null ? 0 : proState.totalAdditionalEvents()).append('\n');
        report.append(ar() ? "حالات بانتظار مزامنة API: " : "Statuses pending API sync: ").append(statusSync == null ? 0 : statusSync.pendingCount()).append('\n');
        report.append(ar() ? "المجموعة المعروضة: " : "Displayed group: ").append(KitchenGroupPolicy.displaySelection(settings.getString("selected_groups", KitchenGroupPolicy.ALL), ar()));
        new AlertDialog.Builder(this).setTitle(ar() ? "تقرير أداء المطبخ" : "Kitchen performance report")
                .setMessage(report.toString()).setPositiveButton(ar() ? "إغلاق" : "Close", null).show();
    }

    private void updateVoiceState(String value, boolean error) {
        if (voiceButton == null) return;
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        voiceButton.setText(lower.contains("listen") || lower.contains("hear") || lower.contains("ready") ? "●" : "🎙");
        voiceButton.setTextColor(error ? red : (lower.contains("listen") || lower.contains("hear") ? green : text));
        voiceButton.setContentDescription(value);
    }

    private void handleVoiceText(String value, boolean fromWake) {
        if (store == null || value == null) return;
        KitchenVoiceParser.Intent intent = KitchenVoiceParser.parse(value);
        if (fromWake && !intent.wakePhrase) return;
        boolean responseArabic = intent.arabic;
        List<KitchenOrder> active = store.active();
        long now = System.currentTimeMillis();
        if (intent.type == KitchenVoiceParser.Type.COUNT_DELAYED) {
            int count = 0; for (KitchenOrder order : active) if (now - order.createdAt >= lateAfterMs()) count++;
            voiceReply(responseArabic ? "عندك " + count + " طلبات متأخرة" : "You have " + count + " delayed orders", responseArabic); return;
        }
        if (intent.type == KitchenVoiceParser.Type.LIST_DELAYED) {
            ArrayList<String> numbers = new ArrayList<>(); for (KitchenOrder order : active) if (now - order.createdAt >= lateAfterMs()) numbers.add(invoiceNumber(order));
            String list = joinNumbers(numbers, responseArabic);
            voiceReply(numbers.isEmpty() ? (responseArabic ? "لا توجد طلبات متأخرة" : "There are no delayed orders") : (responseArabic ? "الطلبات المتأخرة هي " + list : "Delayed orders are " + list), responseArabic); return;
        }
        if (intent.type == KitchenVoiceParser.Type.LIST_READY) {
            ArrayList<String> numbers = new ArrayList<>(); for (KitchenOrder order : active) if (order.kitchenStatus == KitchenOrder.Status.READY) numbers.add(invoiceNumber(order));
            String list = joinNumbers(numbers, responseArabic);
            voiceReply(numbers.isEmpty() ? (responseArabic ? "لا توجد طلبات جاهزة" : "There are no ready orders") : (responseArabic ? "الطلبات الجاهزة هي " + list : "Ready orders are " + list), responseArabic); return;
        }
        KitchenOrder order = intent.invoiceNumber == null ? null : store.findByNumber(String.valueOf(intent.invoiceNumber));
        if (intent.type == KitchenVoiceParser.Type.FIND_INVOICE || intent.type == KitchenVoiceParser.Type.INVOICE_STATUS) {
            if (order == null) { voiceReply(responseArabic ? "لم أجد الفاتورة رقم " + intent.invoiceNumber : "I could not find invoice " + intent.invoiceNumber, responseArabic); return; }
            ArrayList<KitchenOrder> sorted = new ArrayList<>(active); KitchenPriorityPolicy.sort(sorted, proState, now, warningAfterMs(), lateAfterMs(), settings.getBoolean("smart_sorting", true));
            int position = 0; for (int i = 0; i < sorted.size(); i++) if (sorted.get(i).id.equals(order.id)) { position = i + 1; break; }
            String groups = String.join(" و ", KitchenGroupPolicy.groups(order));
            String answer = responseArabic
                    ? "الفاتورة " + order.bestNumber() + " حالتها " + statusLabel(order.kitchenStatus) + "، في مجموعة " + groups + "، وترتيبها على الشاشة " + position
                    : "Invoice " + order.bestNumber() + " is " + statusLabel(order.kitchenStatus) + ", in group " + groups + ", position " + position;
            int additions = proState == null ? 0 : proState.pendingAdditions(order.id);
            if (additions > 0) answer += responseArabic ? "، ولديها " + additions + " إضافات جديدة" : ", with " + additions + " new additions";
            voiceReply(answer, responseArabic); return;
        }
        if (intent.mutating) {
            if (order == null) { voiceReply(responseArabic ? "لم أجد الفاتورة المطلوبة" : "I could not find that invoice", responseArabic); return; }
            final KitchenOrder target = order;
            new AlertDialog.Builder(this)
                    .setTitle(responseArabic ? "تأكيد الأمر الصوتي" : "Confirm voice command")
                    .setMessage(value)
                    .setPositiveButton(responseArabic ? "تأكيد" : "Confirm", (d, w) -> executeVoiceMutation(intent, target))
                    .setNegativeButton(responseArabic ? "إلغاء" : "Cancel", (d, w) -> voiceReply(responseArabic ? "تم الإلغاء" : "Cancelled", responseArabic))
                    .show();
            return;
        }
        voiceReply(responseArabic ? "لم أفهم الأمر. جرّب: وين فاتورة 7؟" : "I did not understand. Try: Where is invoice 7?", responseArabic);
    }

    private void executeVoiceMutation(KitchenVoiceParser.Intent intent, KitchenOrder order) {
        boolean responseArabic = intent.arabic;
        if (intent.type == KitchenVoiceParser.Type.ACK_ADDITIONS) {
            if (proState != null) proState.acknowledge(order.id, preferredEmployee(order));
            renderBoard();
            voiceReply(responseArabic ? "تمت مراجعة الإضافات" : "Additions reviewed", responseArabic);
        } else if (intent.type == KitchenVoiceParser.Type.START_PREPARING) {
            if (order.kitchenStatus == KitchenOrder.Status.NEW) advance(order);
            else voiceReply(responseArabic ? "الطلب بدأ تحضيره بالفعل" : "The order is already in preparation", responseArabic);
        } else if (intent.type == KitchenVoiceParser.Type.MARK_READY) {
            applyStatus(order, KitchenOrder.Status.READY, preferredEmployee(order));
            voiceReply(responseArabic ? "تم تحويل الفاتورة إلى جاهز" : "Invoice marked ready", responseArabic);
        } else if (intent.type == KitchenVoiceParser.Type.MARK_COMPLETED) {
            applyStatus(order, KitchenOrder.Status.DONE, preferredEmployee(order));
            voiceReply(responseArabic ? "تم تسليم الفاتورة ونقلها للسجل" : "Invoice completed and archived", responseArabic);
        }
    }

    private String joinNumbers(List<String> values, boolean arabic) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int limit = Math.min(8, values.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) out.append(i == limit - 1 ? (arabic ? " و" : " and ") : ", ");
            out.append(values.get(i));
        }
        if (values.size() > limit) out.append(arabic ? " وغيرها" : " and others");
        return out.toString();
    }

    private void voiceReply(String value, boolean arabic) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
        if (voiceController != null) voiceController.speak(value, arabic);
    }

'''
activity = replace_once(activity, "    private int statusColor(KitchenOrder.Status status)", helpers + "    private int statusColor(KitchenOrder.Status status)", "Pro helper methods")

# Respect sound switch in every tone helper.
for method in ["beepNew", "beepModified", "beepCancel", "beepLate", "beep"]:
    if method == "beep":
        pattern = r"    private void beep\(boolean positive\) \{"
        replacement = "    private void beep(boolean positive) { if (!soundsEnabled()) return;"
    else:
        pattern = r"    private void " + method + r"\(\) \{"
        replacement = "    private void " + method + "() { if (!soundsEnabled()) return;"
    activity, count = re.subn(pattern, replacement, activity, count=1)
    if count != 1: fail("sound toggle for " + method)

# Permission and lifecycle handling.
lifecycle = '''    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == KitchenVoiceController.REQUEST_RECORD_AUDIO && voiceController != null) {
            voiceController.onPermissionResult(grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (voiceController != null && settings != null && settings.getBoolean("voice_enabled", true)
                && settings.getBoolean("voice_wake", false)) voiceController.setWakeEnabled(true);
    }

    @Override protected void onPause() {
        if (voiceController != null) voiceController.stopListening();
        super.onPause();
    }

'''
activity = replace_once(activity, "    @Override protected void onDestroy() {\n", lifecycle + "    @Override protected void onDestroy() {\n", "voice lifecycle")
activity = replace_once(
    activity,
    "        handler.removeCallbacks(secondTick); handler.removeCallbacks(watchdog);",
    "        handler.removeCallbacks(secondTick); handler.removeCallbacks(watchdog); try { if (voiceController != null) voiceController.shutdown(); } catch (Throwable ignored) { } try { if (statusSync != null) statusSync.shutdown(); } catch (Throwable ignored) { }",
    "Pro shutdown",
)

activity_path.write_text(activity, encoding="utf-8")

# Basic invariants so CI stops instead of shipping a half-patched APK.
for path, required in {
    activity_path: [
        "KitchenLocalEnricher.enrich", "KitchenPriorityPolicy.sort", "handleVoiceText",
        "KitchenTrainingActivity.class", "historyCleanupDueAt", "\"1997\"",
    ],
    store_path: ["KitchenItemMerger.merge", "addBusinessDays", "MAX_HISTORY = 1000"],
    manifest_path: ["android.permission.RECORD_AUDIO", ".KitchenTrainingActivity"],
    build_path: ["versionName '5.0.0-pro'"],
}.items():
    text = path.read_text(encoding="utf-8")
    for marker in required:
        if marker not in text:
            fail(str(path) + " missing invariant " + marker)

print("TechLight KDS Pro v5.0 patch applied successfully")
