from pathlib import Path

activity = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenActivityV3.java')
text = activity.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global text
    if old not in text:
        raise SystemExit(f'V4 patch target not found: {label}')
    text = text.replace(old, new, 1)

replace_once(
    'import java.util.ArrayList;\n',
    'import java.text.SimpleDateFormat;\nimport java.util.ArrayList;\nimport java.util.Date;\n',
    'date imports'
)

replace_once(
'''        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        String mainLabel = number.isEmpty() ? t("temporary") : t("invoice") + "  #" + number;
        TextView invoice = label(mainLabel, wide() ? 23 : 19, text, true);
        TextView table = label(clean(order.table).isEmpty() ? orderTypeLabel(order) : t("table") + " " + order.table, 12, muted, true);
        table.setPadding(0, dp(2), 0, 0);
        numberBox.addView(invoice);
        numberBox.addView(table);
''',
'''        String number = KitchenSignalV2.cleanIdentity(order.displayNumber);
        String mainLabel = history
                ? (number.isEmpty() ? t("temporary") : "#" + number)
                : (number.isEmpty() ? t("temporary") : t("invoice") + "  #" + number);
        TextView invoice = label(mainLabel, wide() ? 23 : 19, text, true);
        numberBox.addView(invoice);
        if (history) {
            TextView historyStamp = label(formatHistoryStamp(order), 12, muted, true);
            historyStamp.setPadding(0, dp(3), 0, 0);
            numberBox.addView(historyStamp);
        } else {
            TextView table = label(clean(order.table).isEmpty() ? orderTypeLabel(order) : t("table") + " " + order.table, 12, muted, true);
            table.setPadding(0, dp(2), 0, 0);
            numberBox.addView(table);
        }
''',
    'history title'
)

replace_once(
'''        stateRow.setPadding(0, dp(9), 0, dp(5));
        stateRow.addView(chip(history ? t("duration") : statusLabel(order.kitchenStatus), statusFill(order.kitchenStatus), history ? purple : statusText(order.kitchenStatus)));
        if (!history && order.temporaryOrder && !order.isPaid()) {
            TextView unpaid = chip(t("unpaid"), surface2, muted);
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
            up.setMargins(dp(7), 0, 0, 0);
            stateRow.addView(unpaid, up);
        }
        if (history) {
            long prep = prepDuration(order);
            if (prep > 0) {
                TextView prepChip = chip(t("prepTime") + "  " + formatAge(prep), surface2, muted);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
                pp.setMargins(dp(7), 0, 0, 0);
                stateRow.addView(prepChip, pp);
            }
        }
''',
'''        stateRow.setPadding(0, dp(9), 0, dp(5));
        if (history) {
            stateRow.addView(chip(t("duration") + "  " + formatAge(finalDuration(order)), surface2, purple));
            long prep = prepDuration(order);
            if (prep > 0) {
                TextView prepChip = chip(t("prepTime") + "  " + formatAge(prep), surface2, muted);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
                pp.setMargins(dp(7), 0, 0, 0);
                stateRow.addView(prepChip, pp);
            }
        } else {
            stateRow.addView(chip(statusLabel(order.kitchenStatus), statusFill(order.kitchenStatus), statusText(order.kitchenStatus)));
            if (order.temporaryOrder && !order.isPaid()) {
                TextView unpaid = chip(t("unpaid"), surface2, muted);
                LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(29));
                up.setMargins(dp(7), 0, 0, 0);
                stateRow.addView(unpaid, up);
            }
        }
''',
    'history state row'
)

replace_once(
'''        if (history) {
            out.append('\n').append(t("duration")).append(": ").append(formatAge(finalDuration(order))).append('\n');
            if (prepDuration(order) > 0) out.append(t("prepTime")).append(": ").append(formatAge(prepDuration(order))).append('\n');
        }
''',
'''        if (history) {
            out.append(formatHistoryStamp(order)).append('\n');
            out.append(t("duration")).append(": ").append(formatAge(finalDuration(order))).append('\n');
            if (prepDuration(order) > 0) out.append(t("prepTime")).append(": ").append(formatAge(prepDuration(order))).append('\n');
        }
''',
    'history details'
)

replace_once(
'''        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        rp.setMargins(0, dp(12), 0, 0);
        panel.addView(reconnect, rp);
        scroll.addView(panel);
''',
'''        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        rp.setMargins(0, dp(12), 0, 0);
        panel.addView(reconnect, rp);

        TextView clearOrders = action(ar() ? "مسح الطلبات" : "Clear orders", false);
        clearOrders.setTextColor(red);
        clearOrders.setOnClickListener(v -> showClearMenu());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        cp.setMargins(0, dp(9), 0, 0);
        panel.addView(clearOrders, cp);
        scroll.addView(panel);
''',
    'clear button'
)

replace_once(
'''    private String formatQty(double value) {
''',
'''    private String formatHistoryStamp(KitchenOrder order) {
        long completedAt = order == null ? 0L : order.updatedAt;
        if (completedAt <= 0L && order != null) completedAt = order.readyAt;
        if (completedAt <= 0L) return "—";
        try {
            return new SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.US).format(new Date(completedAt));
        } catch (Throwable ignored) {
            return "—";
        }
    }

    private void showClearMenu() {
        if (store == null) return;
        String[] choices = ar()
                ? new String[]{"مسح الطلبات الحالية فقط", "مسح السجل فقط", "مسح جميع الطلبات والسجل"}
                : new String[]{"Clear active orders only", "Clear history only", "Clear active orders and history"};
        new AlertDialog.Builder(this)
                .setTitle(ar() ? "مسح الطلبات" : "Clear orders")
                .setItems(choices, (dialog, which) -> confirmClear(which))
                .setNegativeButton(ar() ? "إلغاء" : "Cancel", null)
                .show();
    }

    private void confirmClear(int which) {
        EditText pin = new EditText(this);
        pin.setHint("PIN");
        pin.setGravity(Gravity.CENTER);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(ar() ? "تأكيد المسح" : "Confirm clear")
                .setMessage(ar() ? "أدخل رمز الإعدادات لتنفيذ المسح." : "Enter the settings PIN to continue.")
                .setView(pin)
                .setPositiveButton(ar() ? "مسح" : "Clear", null)
                .setNegativeButton(ar() ? "إلغاء" : "Cancel", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String savedPin = settings == null ? "0000" : settings.getString("pin", "0000");
            if (!savedPin.equals(pin.getText().toString())) {
                pin.setError(ar() ? "الرمز غير صحيح" : "Wrong PIN");
                return;
            }
            if (which == 0) store.clearActive();
            else if (which == 1) store.clearHistory();
            else store.clearAll();
            liveDraft = null;
            lateAlertAt.clear();
            dialog.dismiss();
            renderBoard(true);
            Toast.makeText(this, ar() ? "تم المسح" : "Cleared", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private String formatQty(double value) {
''',
    'history formatter and clear methods'
)

activity.write_text(text, encoding='utf-8')

login = Path('app/src/main/java/sa/techlight/customerdisplay/KitchenLoginActivityV3.java')
login_text = login.read_text(encoding='utf-8')
login_text = login_text.replace('logo.setImageResource(R.drawable.techlight_mark);', 'logo.setImageResource(R.drawable.techlight_t_logo);')
login_text = login_text.replace('        if (brandLoader != null) brandLoader.load(BRAND_LOGO, logo, null);\n', '')
login_text = login_text.replace('0xFF1769E0', '0xFF7432E0')
login.write_text(login_text, encoding='utf-8')

print('TechPro Kitchen V4 finalizer applied successfully')
