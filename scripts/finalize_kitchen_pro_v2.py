from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit("KDS Pro v2 patch missing anchor: " + label)
    return text.replace(old, new, 1)


activity_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV42.java")
activity = activity_path.read_text(encoding="utf-8")

# Avoid java.lang.String.join on Android 6/7 devices where the platform method
# may be absent without core-library desugaring.
activity = activity.replace('String.join(" • ", KitchenGroupPolicy.groups(order))', 'joinText(KitchenGroupPolicy.groups(order), " • ")')
activity = activity.replace('String.join(" و ", KitchenGroupPolicy.groups(order))', 'joinText(KitchenGroupPolicy.groups(order), responseArabic ? " و " : " and ")')

join_helper = '''    private String joinText(List<String> values, String separator) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            String cleanValue = clean(value);
            if (cleanValue.isEmpty()) continue;
            if (out.length() > 0) out.append(separator);
            out.append(cleanValue);
        }
        return out.toString();
    }

'''
activity = replace_once(activity, "    private String joinNumbers(List<String> values, boolean arabic) {\n", join_helper + "    private String joinNumbers(List<String> values, boolean arabic) {\n", "Android-compatible join helper")

# Do not start wake listening when voice is disabled, and suspend it fully while
# another activity is in front.
activity = activity.replace(
    'if (voiceController != null && settings.getBoolean("voice_wake", false)) voiceController.setWakeEnabled(true);',
    'if (voiceController != null && settings.getBoolean("voice_enabled", true) && settings.getBoolean("voice_wake", false)) voiceController.setWakeEnabled(true);',
    1,
)
activity = activity.replace(
    'if (voiceController != null) voiceController.stopListening();\n        super.onPause();',
    'if (voiceController != null) { voiceController.setWakeEnabled(false); voiceController.stopListening(); }\n        super.onPause();',
    1,
)
activity_path.write_text(activity, encoding="utf-8")

queue_path = Path("app/src/main/java/sa/techlight/customerdisplay/KitchenStatusSyncQueue.java")
queue = queue_path.read_text(encoding="utf-8")
queue = queue.replace('RequestBody.create(JSON, event.toString())', 'RequestBody.create(event.toString(), JSON)')
queue_path.write_text(queue, encoding="utf-8")

manifest_path = Path("app/src/main/AndroidManifest.xml")
manifest = manifest_path.read_text(encoding="utf-8")
if 'android.hardware.microphone' not in manifest:
    manifest = replace_once(
        manifest,
        '    <uses-feature android:name="android.software.leanback" android:required="false" />\n',
        '    <uses-feature android:name="android.software.leanback" android:required="false" />\n'
        '    <uses-feature android:name="android.hardware.microphone" android:required="false" />\n',
        "optional microphone feature",
    )
manifest_path.write_text(manifest, encoding="utf-8")

for marker in ["joinText(KitchenGroupPolicy.groups(order)", "voiceController.setWakeEnabled(false)"]:
    if marker not in activity:
        raise SystemExit("KDS Pro v2 invariant missing: " + marker)

print("TechLight KDS Pro v2 compatibility patch applied")
