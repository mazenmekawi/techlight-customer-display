from pathlib import Path
import re

A=Path('app/src/main/java/sa/techlight/customerdisplay/KitchenStableActivity.java')
M=Path('app/src/main/AndroidManifest.xml')
G=Path('app/build.gradle')
if not all(p.exists() for p in (A,M,G)): raise SystemExit('generate stable sources first')

def one(s,o,n,name):
 c=s.count(o)
 if c!=1: raise SystemExit(f'{name}: {c} matches')
 return s.replace(o,n,1)

def cut_method(s,sig):
 a=s.find(sig); b=s.find('{',a)
 if a<0 or b<0: raise SystemExit('missing '+sig)
 d=0
 for i in range(b,len(s)):
  if s[i]=='{': d+=1
  elif s[i]=='}':
   d-=1
   if d==0: return s[:a]+s[i+1:]
 raise SystemExit('unclosed '+sig)

s=A.read_text()
for x in ['import android.Manifest;\n','import android.content.pm.PackageManager;\n','import java.util.regex.Matcher;\n','import java.util.regex.Pattern;\n']:
 s=s.replace(x,'')
s=one(s,'public final class KitchenStableActivity extends Activity implements TechProClient.Listener, KitchenVoiceController.Listener {','public final class KitchenStableActivity extends Activity implements TechProClient.Listener {','voice interface')
for x in ['    private static final int REQUEST_AUDIO = 9127;\n','    private TextView voiceButton;\n','    private KitchenVoiceController voice;\n','    private boolean activityVisible;\n']:
 s=s.replace(x,'')
s=one(s,'''            try {
                voice = new KitchenVoiceController(this, this);
                voice.setLanguage(ar());
            } catch (Throwable error) { recordError("voice-init", error); }
''','', 'voice init')
for x in ['        if (!settings.contains("voice_reply")) editor.putBoolean("voice_reply", true);\n','        if (!settings.contains("wake_voice")) editor.putBoolean("wake_voice", false);\n','            case "voice": return a ? "أمر صوتي" : "Voice command";\n','        updateVoiceButton("idle", false);\n']:
 s=s.replace(x,'')
s=one(s,'            case "silence": return a ? "كتم 5 دقائق" : "Mute 5 minutes";\n','            case "silence": return a ? "كتم 5 دقائق" : "Mute 5 minutes";\n            case "qr": return a ? "مسح QR" : "Scan QR";\n','qr label')

s=one(s,'''        ImageView logo = new ImageView(this);
        logo.setImageResource(dark ? R.drawable.techlight_brand_white_transparent : R.drawable.techlight_brand_transparent);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(wide() ? 146 : 118), dp(44)));

''','', 'large header logo')
s=one(s,'''        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, dp(10), 0);
        titleBox.addView(label(t("title"), wide() ? 21 : 18, text, true));
        titleBox.addView(label(ar() ? "الأقدم أولاً • فاتورة واحدة بلا تكرار" : "Oldest first • one ticket per invoice", 11, muted, false));
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
''','''        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(5), 0, dp(5), 0);
        titleBox.addView(label(t("title"), wide() ? 18 : 16, text, true));
        metricsText = label(ar() ? "بانتظار الطلبات…" : "Waiting for orders…", wide() ? 12 : 11, muted, true);
        metricsText.setSingleLine(true);
        titleBox.addView(metricsText);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
''','compact title')
s=one(s,'''        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        metrics.setLayoutDirection(direction());
        metrics.setPadding(dp(12), dp(8), dp(12), dp(8));
        metrics.setBackground(cardBg(surface, 20, border));
        metricsText = label(ar() ? "جاري حساب الطلبات…" : "Calculating queue…", wide() ? 14 : 12, muted, true);
        metrics.addView(metricsText, new LinearLayout.LayoutParams(0, dp(38), 1));
        groupText = chip(groupSummary(), surface2, purple);
        metrics.addView(groupText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)));
        LinearLayout.LayoutParams metricParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        metricParams.setMargins(0, dp(8), 0, 0);
        root.addView(metrics, metricParams);

''','''        groupText = chip(groupSummary(), surface2, purple);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        groupParams.setMargins(dp(4), 0, dp(4), 0);
        header.addView(groupText, groupParams);

''','remove metrics row')
s=one(s,'''        TextView search = action(t("search"), false);
        search.setOnClickListener(v -> showSearchDialog());
        tools.addView(search, toolParams(92));
''','''        TextView search = action(t("search"), false);
        search.setOnClickListener(v -> showSearchDialog());
        tools.addView(search, toolParams(78));
        TextView qr = action(t("qr"), false);
        qr.setOnClickListener(v -> openQrPairing());
        tools.addView(qr, toolParams(82));
''','qr tool')
s=one(s,'''        voiceButton = action(t("voice"), false);
        voiceButton.setOnClickListener(v -> startVoiceOnce());
        voiceButton.setOnLongClickListener(v -> { toggleWakeVoice(); return true; });
        tools.addView(voiceButton, toolParams(wide() ? 132 : 112));

''','', 'voice button')

for o,n in [
 ('root.setPadding(dp(12), dp(10), dp(12), dp(8));','root.setPadding(dp(8), dp(6), dp(8), dp(5));'),
 ('header.setPadding(dp(14), dp(9), dp(14), dp(9));','header.setPadding(dp(11), dp(4), dp(9), dp(4));'),
 ('root.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)));','root.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));'),
 ('toolsScroll.addView(tools, new HorizontalScrollView.LayoutParams(HorizontalScrollView.LayoutParams.WRAP_CONTENT, dp(48)));','toolsScroll.addView(tools, new HorizontalScrollView.LayoutParams(HorizontalScrollView.LayoutParams.WRAP_CONTENT, dp(40)));'),
 ('LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));','LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));'),
 ('tabScroll.addView(tabRow, new HorizontalScrollView.LayoutParams(HorizontalScrollView.LayoutParams.WRAP_CONTENT, dp(48)));','tabScroll.addView(tabRow, new HorizontalScrollView.LayoutParams(HorizontalScrollView.LayoutParams.WRAP_CONTENT, dp(38)));'),
 ('LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));','LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42));'),
 ('new LinearLayout.LayoutParams(dp(widthDp), dp(42))','new LinearLayout.LayoutParams(dp(widthDp), dp(36))'),
 ('new LinearLayout.LayoutParams(dp(wide() ? 142 : 118), dp(42))','new LinearLayout.LayoutParams(dp(wide() ? 126 : 106), dp(36))'),
 ('int usable = Math.max(cardWidth, getResources().getConfiguration().screenWidthDp - 36);','int usable = Math.max(cardWidth, getResources().getConfiguration().screenWidthDp - 22);'),
 ('usable / (cardWidth + 12)','usable / (cardWidth + 8)'),
 ('params.setMargins(dp(6), dp(6), dp(6), dp(6));','params.setMargins(dp(4), dp(4), dp(4), dp(4));'),
 ('card.setPadding(dp(15), dp(13), dp(15), dp(14));','card.setPadding(dp(12), dp(9), dp(12), dp(10));'),
 ('card.setMinimumHeight(dp(320));','card.setMinimumHeight(dp(264));'),
 ('row.setPadding(0, dp(5), 0, dp(5));','row.setPadding(0, dp(3), 0, dp(3));'),
 ('new LinearLayout.LayoutParams(dp(56), dp(56))','new LinearLayout.LayoutParams(dp(48), dp(48))'),
]: s=s.replace(o,n)

s=s.replace('Collections.sort(visible, Comparator.comparingLong(value -> value.createdAt));','Collections.sort(visible, Comparator.comparingLong(this::stableCreatedAt));')
s=one(s,'''        long now = System.currentTimeMillis();
        long age = history ? finalDuration(order) : Math.max(0L, now - order.createdAt);
        KitchenStableMetaStore.Meta meta = metadata == null ? new KitchenStableMetaStore.Meta() : metadata.get(order);
''','''        long now = System.currentTimeMillis();
        KitchenStableMetaStore.Meta meta = metadata == null ? new KitchenStableMetaStore.Meta() : metadata.get(order);
        long startedAt = stableCreatedAt(order);
        long age = history ? finalDuration(order) : Math.max(0L, now - startedAt);
''','timer origin')
s=s.replace('timerRefs.put(order.id, new TimerRef(timer, order.createdAt, order.id, order.kitchenStatus));','timerRefs.put(order.id, new TimerRef(timer, startedAt, order.id, order.kitchenStatus));')
s=s.replace('TextView numberView = label("#" + number, wide() ? 30 : 25, text, true);','TextView numberView = label((ar() ? "طلب #" : "Order #") + number, wide() ? 25 : 22, text, true);')
s=s.replace('TextView additions = alertBand((ar() ? "+" + meta.additionalCount + " إضافة جديدة — اضغط للتأكيد" : "+" + meta.additionalCount + " new items — tap to acknowledge"), amber);','TextView additions = alertBand((ar() ? "إضافات على الطلب: +" + meta.additionalCount + " — تبقى حتى المراجعة" : "Order additions: +" + meta.additionalCount + " — stays until reviewed"), amber);')
s=s.replace('now - order.createdAt >= warningMs()','now - stableCreatedAt(order) >= warningMs()')
s=s.replace('System.currentTimeMillis() - order.createdAt >= warningMs()','System.currentTimeMillis() - stableCreatedAt(order) >= warningMs()')

s=one(s,'''        panel.addView(sectionTitle(ar() ? "الأوامر الصوتية" : "Voice commands"));
        CheckBox voiceReply = settingCheck(ar() ? "رد صوتي مختصر بعد تنفيذ الأمر" : "Brief spoken confirmation", settings.getBoolean("voice_reply", true));
        CheckBox wakeVoice = settingCheck(ar() ? "الاستماع إلى Hi TechPro أثناء فتح الشاشة" : "Listen for Hi TechPro while screen is open", settings.getBoolean("wake_voice", false));
        panel.addView(voiceReply); panel.addView(wakeVoice);
        panel.addView(label(ar() ? "مثال: Hi TechPro، أنا أحمد، ابدأ فاتورة 25" : "Example: Hi TechPro, I am Ahmed, start invoice 25", 12, muted, false));

''','', 'voice settings')
for x in ['                        .putBoolean("voice_reply", voiceReply.isChecked())\n','                        .putBoolean("wake_voice", wakeVoice.isChecked())\n','                if (voice != null) voice.setLanguage(english.isChecked() ? false : true);\n']:
 s=s.replace(x,'')
s=one(s,'''        String savedIp = pair == null ? "" : clean(pair.getString("ip", ""));
''','''        TextView qrButton = action(ar() ? "مسح QR وربط الكاشير" : "Scan QR and pair cashier", true);
        qrButton.setOnClickListener(v -> openQrPairing());
        panel.addView(qrButton, settingButtonParams());
        String savedIp = pair == null ? "" : clean(pair.getString("ip", ""));
''','settings qr')

a=s.find('    private void startVoiceOnce()'); b=s.find('    private TextView sectionTitle(String value)',a)
if a<0 or b<0: raise SystemExit('voice method block missing')
qr='''    private void openQrPairing() {
        try { startActivityForResult(new Intent(this, KitchenQrPairingActivity.class), REQUEST_QR_PAIRING); }
        catch (Throwable error) { recordError("qr-pairing", error); Toast.makeText(this, ar() ? "تعذر فتح كاميرا QR" : "Could not open QR scanner", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_QR_PAIRING || resultCode != RESULT_OK) return;
        String ip = data == null ? "" : clean(data.getStringExtra("ip"));
        int port = data == null ? 4040 : data.getIntExtra("port", 4040);
        if (ip.isEmpty() && pair != null) { ip = clean(pair.getString("ip", "")); port = pair.getInt("port", 4040); }
        if (!ip.isEmpty()) connect(ip, port);
    }

'''
s=s[:a]+qr+s[b:]
s=s.replace('    private static final long DEFAULT_WARNING_MS','    private static final int REQUEST_QR_PAIRING = 9310;\n    private static final long DEFAULT_WARNING_MS',1)
s=s.replace('        activityVisible = false;\n','').replace('        try { if (voice != null) voice.shutdown(); } catch (Throwable ignored) { }\n','')

helper='''    private long stableCreatedAt(KitchenOrder order) {
        if (order == null) return System.currentTimeMillis();
        long value = order.createdAt;
        if (metadata != null) {
            KitchenStableMetaStore.Meta meta = metadata.get(order);
            if (meta.firstSeenAt > 0L && (value <= 0L || meta.firstSeenAt < value)) value = meta.firstSeenAt;
        }
        return value > 0L ? value : System.currentTimeMillis();
    }

'''
s=one(s,'    private int cardWidthDp() {',helper+'    private int cardWidthDp() {','timer helper')
s=one(s,'''    private int cardWidthDp() {
        int width = getResources().getConfiguration().screenWidthDp;
        String size = settings == null ? "compact" : settings.getString("card_size", "compact");
        int base = "large".equals(size) ? 360 : "normal".equals(size) ? 320 : 282;
        if (width < 600) return Math.max(270, width - 36);
        if (width >= 1400 && "compact".equals(size)) return 300;
        return base;
    }''','''    private int cardWidthDp() {
        int width = getResources().getConfiguration().screenWidthDp;
        String size = settings == null ? "compact" : settings.getString("card_size", "compact");
        int base = "large".equals(size) ? 344 : "normal".equals(size) ? 304 : 272;
        if (width < 600) return Math.max(258, width - 24);
        if (width >= 1400 && "compact".equals(size)) return 282;
        return base;
    }''','card sizing')
s=s.replace('playTonePattern(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3, 220, 210L);','playTonePattern(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 4, 250, 190L);')
s=s.replace('playTonePattern(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 5, 360, 260L);','playTonePattern(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 6, 380, 240L);')
for x in ['KitchenVoiceController','startVoiceOnce','toggleWakeVoice','onVoiceText','voice_reply','wake_voice','Hi TechPro']:
 if x in s: raise SystemExit('voice remains: '+x)
A.write_text(s)

m=M.read_text()
m=re.sub(r'\s*<uses-permission android:name="android\.permission\.RECORD_AUDIO"\s*/>\s*','\n',m)
m=re.sub(r'\s*<uses-feature android:name="android\.hardware\.microphone"[^>]*/>\s*','\n',m)
if 'android.permission.CAMERA' not in m: m=one(m,'    <uses-permission android:name="android.permission.INTERNET" />\n','    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.CAMERA" />\n','camera permission')
if 'android.hardware.camera' not in m: m=one(m,'    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />\n','    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />\n    <uses-feature android:name="android.hardware.camera" android:required="false" />\n','camera feature')
if '.KitchenQrPairingActivity' not in m: m=one(m,'        <activity android:name=".KitchenLoginActivity" android:exported="false" />\n','        <activity android:name=".KitchenLoginActivity" android:exported="false" />\n        <activity android:name=".KitchenQrPairingActivity" android:exported="false" android:screenOrientation="fullSensor" />\n','qr activity')
M.write_text(m)

g=G.read_text(); g,n1=re.subn(r'(?m)^\s*versionCode\s+\d+\s*$','        versionCode 671',g,count=1); g,n2=re.subn(r"(?m)^\s*versionName\s+'[^']+'\s*$","        versionName '6.7.1-stable-hotfix'",g,count=1)
if n1!=1 or n2!=1: raise SystemExit('version patch failed')
G.write_text(g)

need=['Comparator.comparingLong(this::stableCreatedAt)','new TimerRef(timer, startedAt','"طلب #"','إضافات على الطلب:','acknowledgeAdditions(order)','beepModified()','beepLate()','KitchenQrPairingActivity.class','dp(50)']
miss=[x for x in need if x not in s]
if miss: raise SystemExit('missing '+str(miss))
if 'RECORD_AUDIO' in m or 'hardware.microphone' in m: raise SystemExit('microphone remains')
print('Applied TechLight Kitchen 6.7.1 compact no-voice hotfix')
