package sa.techlight.customerdisplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class SettingsActivity extends Activity {
    private static final int PICK_LOGO = 42;
    private static final int RESYNC_CATALOG = 43;
    private static final int ACCENT = 0xFF4D0E81;

    private SharedPreferences preferences;
    private EditText welcome;
    private EditText thanks;
    private EditText footer;
    private EditText color;
    private ImageView logo;
    private RadioGroup themeGroup;
    private RadioGroup orientationGroup;
    private RadioGroup ableGroup;
    private int selectedTemplate;
    private final LinearLayout[] templateCards = new LinearLayout[2];
    private final TextView[] templateChecks = new TextView[2];

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("ui", 0);
        showPassword();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable cardBackground(boolean selected) {
        GradientDrawable drawable = round(Color.WHITE, 20);
        drawable.setStroke(dp(selected ? 2 : 1), selected ? ACCENT : 0xFFE4DFE8);
        return drawable;
    }

    private TextView text(String value, int size, int textColor) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(textColor);
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView button(String label, boolean primary) {
        TextView view = text(label, 15, primary ? Color.WHITE : ACCENT);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable content = round(primary ? ACCENT : Color.WHITE, 17);
        content.setStroke(dp(1), primary ? ACCENT : 0xFFDDD5E3);
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x185B2A86),
                content,
                null
        ));
        view.setClickable(true);
        view.setFocusable(true);
        view.setElevation(dp(primary ? 3 : 1));
        return view;
    }

    private void showPassword() {
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("0000");
        pin.setGravity(Gravity.CENTER);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("رمز إعدادات ضوء التقنية")
                .setMessage("أدخل الرمز لفتح إعدادات شاشة العميل")
                .setView(pin)
                .setCancelable(false)
                .setNegativeButton("إلغاء", (d, which) -> finish())
                .setPositiveButton("دخول", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if ("0000".equals(pin.getText().toString())) {
                dialog.dismiss();
                buildSettings();
            } else {
                pin.setError("الرمز غير صحيح");
            }
        }));
        dialog.show();
    }

    private void buildSettings() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFF8F6FA, 0xFFFFFFFF}
        );
        root.setBackground(background);
        scroll.addView(root);

        addHeader(root);
        addAppearanceSection(root);
        addTemplateSection(root);
        addIdentitySection(root);
        addAbleSignSection(root);
        addAccountSection(root);
        addConnectionSection(root);

        TextView save = button("حفظ الإعدادات والعودة للشاشة", true);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        );
        saveParams.setMargins(0, dp(18), 0, 0);
        root.addView(save, saveParams);
        save.setOnClickListener(view -> saveAndClose());
        setContentView(scroll);
    }

    private void addHeader(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(2), dp(4), dp(12));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_techlight);
        header.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(10), 0, 0, 0);
        TextView heading = text("إعدادات شاشة العميل", 25, 0xFF262229);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(heading);
        TextView hint = text("بعد الحفظ تختفي الإعدادات؛ المس شاشة العميل لإظهار زرها.", 12, 0xFF817B85);
        copy.addView(hint);
        header.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);
    }

    private LinearLayout section(LinearLayout root, String titleValue, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        card.setBackground(cardBackground(false));
        card.setElevation(dp(1));
        TextView heading = text(titleValue, 19, 0xFF302B33);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(heading);
        TextView detail = text(subtitle, 12, 0xFF85808A);
        detail.setPadding(0, dp(3), 0, dp(12));
        card.addView(detail);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, dp(8));
        root.addView(card, params);
        return card;
    }

    private void addAppearanceSection(LinearLayout root) {
        LinearLayout section = section(
                root,
                "المظهر واتجاه الشاشة",
                "خيارات واضحة فقط؛ اختر الشكل والاتجاه ثم احفظ. الوضع التلقائي هو الأنسب لمعظم الأجهزة."
        );

        section.addView(fieldLabel("ألوان شاشة العميل"));
        themeGroup = new RadioGroup(this);
        themeGroup.setOrientation(RadioGroup.HORIZONTAL);
        themeGroup.setGravity(Gravity.CENTER_VERTICAL);
        String savedTheme = preferences.getString("theme", "light");
        themeGroup.addView(radioOption("فاتح", 100, "light".equals(savedTheme)), radioParams());
        themeGroup.addView(radioOption("داكن", 101, "dark".equals(savedTheme)), radioParams());
        section.addView(themeGroup);

        section.addView(fieldLabel("اتجاه العرض"));
        orientationGroup = new RadioGroup(this);
        orientationGroup.setOrientation(RadioGroup.VERTICAL);
        int orientation = preferences.getInt("orientation", 0);
        orientationGroup.addView(radioOption("تلقائي — يدور حسب وضع الجهاز", 200, orientation == 0));
        orientationGroup.addView(radioOption("أفقي — للشاشات والتلفزيون", 201, orientation == 1));
        orientationGroup.addView(radioOption("رأسي — للتابلت العمودي", 202, orientation == 2));
        section.addView(orientationGroup);
    }

    private RadioButton radioOption(String label, int id, boolean checked) {
        RadioButton option = new RadioButton(this);
        option.setId(id);
        option.setText(label);
        option.setTextSize(14);
        option.setTextColor(0xFF403A44);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(dp(6), dp(8), dp(6), dp(8));
        option.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, 0xFFAFA8B2}
        ));
        option.setChecked(checked);
        return option;
    }

    private RadioGroup.LayoutParams radioParams() {
        return new RadioGroup.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private void addTemplateSection(LinearLayout root) {
        LinearLayout section = section(
                root,
                "اختر واجهة العميل",
                "عاين التصاميم الثلاثة هنا واضغط على التصميم المطلوب قبل الحفظ."
        );
        selectedTemplate = Math.min(1, Math.max(0, preferences.getInt("template", 0)));
        boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        section.addView(choices);
        String[] titles = {"ذكي تلقائي", "الإجمالي أولًا"};
        String[] subtitles = {"يتكيّف تلقائيًا مع مساحة الشاشة", "يبرز المبلغ ثم تفاصيل الطلب"};
        for (int i = 0; i < 2; i++) {
            LinearLayout card = templateCard(i, titles[i], subtitles[i]);
            templateCards[i] = card;
            if (wide) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(175), 1);
                params.setMargins(dp(4), 0, dp(4), 0);
                choices.addView(card, params);
            } else {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(165)
                );
                params.setMargins(0, dp(5), 0, dp(5));
                choices.addView(card, params);
            }
        }
        refreshTemplateCards(false);
    }

    private LinearLayout templateCard(int index, String titleValue, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.setGravity(Gravity.CENTER);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> {
            selectedTemplate = index;
            refreshTemplateCards(true);
        });

        LinearLayout preview = new LinearLayout(this);
        preview.setPadding(dp(6), dp(6), dp(6), dp(6));
        preview.setBackground(round(0xFFF5F2F7, 12));
        LinearLayout order = new LinearLayout(this);
        order.setOrientation(LinearLayout.VERTICAL);
        order.setPadding(dp(6), dp(6), dp(6), dp(6));
        order.setBackground(round(Color.WHITE, 8));
        for (int row = 0; row < 3; row++) {
            View line = new View(this);
            line.setBackground(round(row == 0 ? 0xFFD9D2DF : 0xFFE9E5EC, 4));
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(row == 0 ? 8 : 6)
            );
            lineParams.setMargins(0, dp(3), 0, dp(3));
            order.addView(line, lineParams);
        }
        FrameLayout totalBlock = new FrameLayout(this);
        totalBlock.setBackground(round(0xFFEADFF2, 8));
        TextView amount = text("99", 15, ACCENT);
        amount.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        amount.setGravity(Gravity.CENTER);
        totalBlock.addView(amount, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        preview.setOrientation(LinearLayout.HORIZONTAL);
        if (index == 1) {
            LinearLayout.LayoutParams totalParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            totalParams.setMargins(0, 0, dp(5), 0);
            preview.addView(totalBlock, totalParams);
            preview.addView(order, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2));
        } else {
            LinearLayout.LayoutParams orderParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2);
            orderParams.setMargins(0, 0, dp(5), 0);
            preview.addView(order, orderParams);
            preview.addView(totalBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        card.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1
        ));

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        labelRow.setPadding(dp(2), dp(7), dp(2), 0);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(titleValue, 14, 0xFF3B3540);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(name);
        TextView detail = text(subtitle, 10, 0xFF8B858F);
        copy.addView(detail);
        labelRow.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView check = text("✓", 16, Color.WHITE);
        check.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        check.setGravity(Gravity.CENTER);
        check.setBackground(round(ACCENT, 12));
        templateChecks[index] = check;
        labelRow.addView(check, new LinearLayout.LayoutParams(dp(27), dp(27)));
        card.addView(labelRow);
        return card;
    }

    private void refreshTemplateCards(boolean animate) {
        for (int i = 0; i < templateCards.length; i++) {
            if (templateCards[i] == null) continue;
            boolean selected = i == selectedTemplate;
            templateCards[i].setBackground(cardBackground(selected));
            templateChecks[i].setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            if (selected && animate) {
                templateCards[i].setScaleX(0.98f);
                templateCards[i].setScaleY(0.98f);
                templateCards[i].animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            }
        }
    }

    private void addIdentitySection(LinearLayout root) {
        LinearLayout section = section(
                root,
                "هوية المتجر والنصوص",
                "هذه البيانات فقط هي التي يراها العميل بعد الربط؛ شعار ضوء التقنية لا يظهر في واجهة الطلب."
        );

        section.addView(fieldLabel("لون الهوية — اللون الأساسي في الشريط والحركات"));
        color = input(preferences.getString("color", "#4D0E81"));
        section.addView(color);
        LinearLayout presets = new LinearLayout(this);
        String[] colors = {"#4D0E81", "#832ACC", "#343F5A", "#0F766E", "#B42318", "#1D4ED8"};
        for (String value : colors) {
            TextView swatch = text("●", 28, Color.parseColor(value));
            swatch.setGravity(Gravity.CENTER);
            swatch.setBackground(round(0xFFF6F4F7, 12));
            swatch.setOnClickListener(view -> color.setText(value));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMargins(dp(3), dp(5), dp(3), dp(5));
            presets.addView(swatch, params);
        }
        section.addView(presets);

        section.addView(fieldLabel("نص الترحيب"));
        welcome = input(preferences.getString("welcome", "أهلًا وسهلًا بك"));
        section.addView(welcome);
        section.addView(fieldLabel("نص الشكر بعد الدفع"));
        thanks = input(preferences.getString("thanks", "شكرًا لزيارتكم"));
        section.addView(thanks);
        section.addView(fieldLabel("النص السفلي"));
        footer = input(preferences.getString("footer", "نسعد بخدمتكم دائمًا"));
        section.addView(footer);

        section.addView(fieldLabel("شعار المطعم أو المتجر"));
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setPadding(dp(12), dp(12), dp(12), dp(12));
        logo.setBackground(round(0xFFF7F4F8, 16));
        showStoredLogo();
        section.addView(logo, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)
        ));
        LinearLayout logoActions = new LinearLayout(this);
        logoActions.setOrientation(LinearLayout.HORIZONTAL);
        TextView pick = button("اختيار شعار", false);
        pick.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_LOGO);
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        actionParams.setMargins(dp(3), dp(7), dp(3), 0);
        logoActions.addView(pick, actionParams);
        TextView remove = button("إزالة الشعار", false);
        remove.setOnClickListener(view -> {
            preferences.edit().remove("logo").apply();
            logo.setImageResource(R.drawable.ic_store);
        });
        logoActions.addView(remove, actionParams);
        section.addView(logoActions);
    }

    private void addAbleSignSection(LinearLayout root) {
        LinearLayout section = section(
                root,
                "AbleSign والمحتوى الإعلاني",
                "يظهر بعد 10 ثوانٍ من عدم وجود طلب ويختفي فور وصول أول صنف. أول تشغيل يعرض كود اقتران من 6 أرقام داخل الشاشة."
        );
        AbleSignController controller = new AbleSignController(this);
        boolean installed = controller.isInstalled();
        TextView state = text(
                "●  المشغّل الرسمي مدمج — لا يحتاج API Key أو رقم شاشة",
                14,
                0xFF12805C
        );
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state.setPadding(0, dp(3), 0, dp(8));
        section.addView(state);
        ableGroup = new RadioGroup(this);
        ableGroup.setOrientation(RadioGroup.VERTICAL);
        int mode = preferences.getInt("able_mode", 0);
        if (mode == 2 && !installed) mode = 1;
        ableGroup.addView(radioOption("إيقاف المحتوى الإعلاني", 300, mode == 0));
        ableGroup.addView(radioOption("AbleSign مدمج — اقتران بكود 6 أرقام (موصى به)", 301, mode == 1));
        RadioButton compatibility = radioOption("وضع التوافق مع تطبيق AbleSign المثبت", 302, mode == 2);
        compatibility.setEnabled(installed);
        ableGroup.addView(compatibility);
        section.addView(ableGroup);

        TextView instructions = text(
                "طريقة الربط: اختر الوضع المدمج واحفظ. بعد 10 ثوانٍ سيظهر كود من 6 أرقام؛ أدخله مرة واحدة في حساب AbleSign من Add Screen. سيبقى الاقتران محفوظًا بعد إغلاق التطبيق.",
                12,
                0xFF625A67
        );
        instructions.setPadding(dp(12), dp(10), dp(12), dp(10));
        instructions.setBackground(round(0xFFF7F4F8, 12));
        section.addView(instructions);

        TextView reset = button("إعادة ضبط AbleSign وإظهار كود جديد", false);
        reset.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("إنشاء كود اقتران جديد؟")
                .setMessage("سيُلغى اقتران AbleSign المحفوظ في هذه الشاشة، ثم يظهر كود جديد عند تشغيل الإعلانات.")
                .setPositiveButton("إنشاء كود جديد", (dialog, which) -> {
                    preferences.edit()
                            .putBoolean("able_reset_requested", true)
                            .putInt("able_mode", 1)
                            .apply();
                    if (ableGroup != null) ableGroup.check(301);
                    Toast.makeText(this, "سيظهر كود AbleSign الجديد بعد الحفظ", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("إلغاء", null)
                .show());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        );
        resetParams.setMargins(0, dp(10), 0, 0);
        section.addView(reset, resetParams);
    }

    private void addAccountSection(LinearLayout root) {
        TechProSession session = new TechProSession(this);
        ProductCatalog catalog = new ProductCatalog(this);
        int catalogCount = catalog.count();
        long syncedAt = catalog.syncedAt();
        catalog.close();
        String syncText = syncedAt == 0
                ? "لم تتم المزامنة"
                : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(syncedAt));

        LinearLayout section = section(
                root,
                "حساب TechPro والأصناف",
                "تسجيل الدخول هو الذي يحمّل أسماء الأصناف وأسعار الحساب قبل ربط شاشة العميل."
        );
        TextView account = text(
                "الحساب: " + (session.accountName().isEmpty() ? session.userName() : session.accountName())
                        + "\nدليل الأصناف: " + catalogCount + " سجل"
                        + "\nآخر مزامنة: " + syncText,
                13,
                0xFF514A55
        );
        account.setPadding(dp(12), dp(10), dp(12), dp(10));
        account.setBackground(round(0xFFF7F4F8, 12));
        section.addView(account);

        TextView sync = button("تحديث أسماء الأصناف والأسعار", false);
        sync.setOnClickListener(view -> {
            Intent intent = new Intent(this, LoginActivity.class);
            intent.putExtra("resync", true);
            startActivityForResult(intent, RESYNC_CATALOG);
        });
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        syncParams.setMargins(0, dp(10), 0, dp(7));
        section.addView(sync, syncParams);

        TextView logout = button("تسجيل الخروج من TechPro", false);
        logout.setTextColor(0xFFB42318);
        logout.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("تسجيل الخروج؟")
                .setMessage("سيتم حذف جلسة الحساب ودليل الأصناف والاقتران من شاشة العميل فقط.")
                .setPositiveButton("تسجيل الخروج", (dialog, which) -> {
                    new TechProSession(this).clear();
                    ProductCatalog localCatalog = new ProductCatalog(this);
                    localCatalog.clearCatalog();
                    localCatalog.close();
                    getSharedPreferences("pair", 0).edit().clear().apply();
                    Intent intent = new Intent(this, LoginActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("إلغاء", null)
                .show());
        section.addView(logout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        ));
    }

    private void addConnectionSection(LinearLayout root) {
        LinearLayout section = section(
                root,
                "ربط Tech Pro",
                "امسح بيانات الربط فقط عند تغيير جهاز الكاشير أو الشبكة."
        );
        SharedPreferences pair = getSharedPreferences("pair", 0);
        String ip = pair.getString("ip", null);
        TextView connection = text(
                ip == null ? "الشاشة غير مرتبطة حاليًا" : "مرتبط بـ " + ip + ":" + pair.getInt("port", 4040),
                14,
                ip == null ? 0xFF8A838D : 0xFF12805C
        );
        connection.setPadding(0, dp(2), 0, dp(10));
        section.addView(connection);

        TextView compatibility = text(
                "تنبيه: نسخة Tech Pro 1.0.12 القديمة لا تحتوي خادم شاشة العميل. استخدم نسخة Tech Pro التي تعرض خيار QR لشاشة العميل وتأكد أن الطلب يُفتح في النسخة نفسها.",
                12,
                0xFF8A5A00
        );
        compatibility.setPadding(0, 0, 0, dp(10));
        section.addView(compatibility);

        SharedPreferences diagnosticPreferences = getSharedPreferences("diagnostics", 0);
        String report = diagnosticReport(diagnosticPreferences, ip, pair.getInt("port", 4040));
        TextView diagnostic = text(report, 12, 0xFF514A55);
        diagnostic.setTextIsSelectable(true);
        diagnostic.setPadding(dp(12), dp(10), dp(12), dp(10));
        diagnostic.setBackground(round(0xFFF7F4F8, 12));
        section.addView(diagnostic);

        TextView copy = button("نسخ تقرير التشخيص", false);
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("TechLight diagnostics", report));
                Toast.makeText(this, "تم نسخ تقرير التشخيص", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        );
        copyParams.setMargins(0, dp(9), 0, dp(7));
        section.addView(copy, copyParams);

        TextView reset = button("إلغاء الربط وإظهار شاشة QR", false);
        reset.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("إلغاء ربط الشاشة؟")
                .setMessage("سيعود التطبيق إلى شاشة QR وستحتاج إلى ربطه مرة أخرى.")
                .setPositiveButton("إلغاء الربط", (dialog, which) -> {
                    getSharedPreferences("pair", 0).edit().clear().apply();
                    setResult(RESULT_OK);
                    Toast.makeText(this, "تم مسح الاقتران", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("تراجع", null)
                .show());
        section.addView(reset, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ));
    }

    private String diagnosticReport(SharedPreferences diagnostics, String ip, int port) {
        long updatedAt = diagnostics.getLong("updated_at", 0);
        long rawAt = diagnostics.getLong("last_raw_at", 0);
        int rawLength = diagnostics.getInt("last_raw_length", 0);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        String updated = updatedAt == 0 ? "لا يوجد" : format.format(new Date(updatedAt));
        String rawTime = rawAt == 0 ? "لا يوجد" : format.format(new Date(rawAt));
        String raw = diagnostics.getString("last_raw", "");
        String history = diagnostics.getString("raw_history", "");
        return "الإصدار: " + appVersion()
                + "\nالعنوان: " + (ip == null ? "غير مرتبط" : "ws://" + ip + ":" + port)
                + "\nالمرحلة: " + diagnostics.getString("stage", "لا يوجد")
                + "\nالتفصيل: " + diagnostics.getString("detail", "لا يوجد")
                + "\nآخر تحديث: " + updated
                + "\nآخر رسالة: " + rawTime + " — " + rawLength + " حرف"
                + (raw.isEmpty() ? "" : "\nRAW: " + raw)
                + (history.isEmpty() ? "" : "\nآخر 12 رسالة: " + history);
    }

    private String appVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "غير معروف" : version;
        } catch (Exception ignored) {
            return "غير معروف";
        }
    }

    private TextView fieldLabel(String value) {
        TextView label = text(value, 14, 0xFF56505A);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, dp(12), 0, dp(5));
        return label;
    }

    private EditText input(String value) {
        EditText editText = new EditText(this);
        editText.setText(value);
        editText.setTextSize(16);
        editText.setTextColor(0xFF2E2931);
        editText.setSingleLine(true);
        editText.setPadding(dp(13), dp(10), dp(13), dp(10));
        editText.setBackground(cardBackground(false));
        return editText;
    }

    private void showStoredLogo() {
        String uri = preferences.getString("logo", null);
        if (uri == null) {
            logo.setImageResource(R.drawable.ic_store);
            return;
        }
        try {
            logo.setImageURI(Uri.parse(uri));
        } catch (Exception ignored) {
            logo.setImageResource(R.drawable.ic_store);
        }
    }

    private void saveAndClose() {
        String colorValue = color.getText().toString().trim();
        try {
            Color.parseColor(colorValue);
        } catch (Exception error) {
            color.setError("اللون غير صحيح");
            return;
        }
        int ableMode = ableGroup == null ? 0
                : ableGroup.getCheckedRadioButtonId() == 301 ? 1
                : ableGroup.getCheckedRadioButtonId() == 302 ? 2 : 0;
        preferences.edit()
                .putInt("template", selectedTemplate)
                .putString("theme", themeGroup != null && themeGroup.getCheckedRadioButtonId() == 101
                        ? "dark" : "light")
                .putInt("orientation", orientationGroup == null ? 0
                        : orientationGroup.getCheckedRadioButtonId() == 201 ? 1
                        : orientationGroup.getCheckedRadioButtonId() == 202 ? 2 : 0)
                .putString("color", colorValue)
                .putString("welcome", welcome.getText().toString().trim())
                .putString("thanks", thanks.getText().toString().trim())
                .putString("footer", footer.getText().toString().trim())
                .putInt("able_mode", ableMode)
                .remove("able_idle")
                .apply();
        setResult(RESULT_OK);
        finish();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESYNC_CATALOG) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "تم تحديث دليل الأصناف", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != PICK_LOGO || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Some gallery apps return a readable URI without persistable permissions.
        }
        preferences.edit().putString("logo", uri.toString()).apply();
        logo.setImageURI(uri);
    }
}
