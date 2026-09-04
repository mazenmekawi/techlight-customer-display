package sa.techlight.customerdisplay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CameraPreview;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.ArrayList;
import java.util.List;

/** One-time camera pairing flow for the kitchen display. */
public final class KitchenQrPairingActivity extends Activity {
    private static final int CAMERA_REQUEST = 931;

    private DecoratedBarcodeView scanner;
    private TextView status;
    private TextView torchButton;
    private boolean torchOn;
    private boolean decoding;
    private boolean stateListenerAttached;
    private boolean arabic = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences settings = getSharedPreferences("kitchen_settings_v3", MODE_PRIVATE);
        arabic = !"en".equalsIgnoreCase(settings.getString("language", "ar"));
        buildUi();
        startWhenAllowed();
    }

    private void buildUi() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(0xFF080A0E);

        scanner = new DecoratedBarcodeView(this);
        ArrayList<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        formats.add(BarcodeFormat.DATA_MATRIX);
        formats.add(BarcodeFormat.AZTEC);
        formats.add(BarcodeFormat.CODE_128);
        scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(formats));
        scanner.setStatusText("");
        shell.addView(scanner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        header.setPadding(dp(14), dp(10), dp(14), dp(10));
        header.setBackgroundColor(0xD9000000);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.techlight_brand_white_transparent);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(48)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(arabic ? "مسح QR لربط شاشة المطبخ" : "Scan QR to pair the kitchen display", 18, Color.WHITE, true);
        status = text(arabic ? "وجّه الكاميرا إلى QR الموجود في TechPro" : "Point the camera at the QR shown in TechPro", 12, 0xFFD1D6DE, false);
        copy.addView(title);
        copy.addView(status);
        header.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        shell.addView(header, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(72), Gravity.TOP));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        controls.setPadding(dp(14), dp(10), dp(14), dp(14));
        controls.setBackgroundColor(0xD9000000);

        TextView close = button(arabic ? "إغلاق" : "Close");
        close.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        controls.addView(close, new LinearLayout.LayoutParams(0, dp(50), 1));

        torchButton = button(arabic ? "الإضاءة" : "Torch");
        torchButton.setOnClickListener(v -> toggleTorch());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(50), 1);
        tp.setMargins(dp(10), 0, dp(10), 0);
        controls.addView(torchButton, tp);

        TextView retry = button(arabic ? "إعادة المسح" : "Scan again");
        retry.setOnClickListener(v -> armScanner());
        controls.addView(retry, new LinearLayout.LayoutParams(0, dp(50), 1));

        shell.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(74), Gravity.BOTTOM));
        setContentView(shell);
    }

    private void startWhenAllowed() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        armScanner();
    }

    private void armScanner() {
        if (scanner == null || isFinishing()) return;
        decoding = true;
        status.setTextColor(0xFFD1D6DE);
        status.setText(arabic ? "جاهز للمسح…" : "Ready to scan…");
        scanner.decodeSingle(new BarcodeCallback() {
            @Override public void barcodeResult(BarcodeResult result) {
                if (!decoding || result == null || result.getText() == null) return;
                decoding = false;
                handleCode(result.getText());
            }
            @Override public void possibleResultPoints(List<ResultPoint> resultPoints) { }
        });
        if (!stateListenerAttached) {
            stateListenerAttached = true;
            scanner.getBarcodeView().addStateListener(new CameraPreview.StateListener() {
                @Override public void previewSized() { }
                @Override public void previewStarted() {
                    runOnUiThread(() -> {
                        if (status != null) {
                            status.setTextColor(0xFFD1D6DE);
                            status.setText(arabic ? "امسح QR الآن" : "Scan the QR now");
                        }
                    });
                }
                @Override public void previewStopped() { }
                @Override public void cameraClosed() { }
                @Override public void cameraError(Exception error) {
                    runOnUiThread(() -> showError(arabic ? "خطأ في كاميرا الجهاز" : "Camera error"));
                }
            });
        }
        try { scanner.resume(); }
        catch (Throwable error) { showError(arabic ? "تعذر تشغيل الكاميرا" : "Could not start camera"); }
    }

    private void handleCode(String raw) {
        try {
            PairingParser.PairingInfo info = PairingParser.parse(raw);
            getSharedPreferences("kitchen_pair", MODE_PRIVATE).edit()
                    .putString("ip", info.ip)
                    .putInt("port", info.port)
                    .putBoolean("pro_first_pairing_prompted", true)
                    .apply();
            Intent result = new Intent();
            result.putExtra("ip", info.ip);
            result.putExtra("port", info.port);
            setResult(RESULT_OK, result);
            Toast.makeText(this,
                    arabic ? "تم حفظ الربط بنجاح" : "Pairing saved successfully",
                    Toast.LENGTH_SHORT).show();
            finish();
        } catch (Throwable error) {
            showError(arabic ? "QR غير صحيح أو لا يحتوي بيانات اتصال TechPro" : "This QR does not contain a valid TechPro connection");
            getWindow().getDecorView().postDelayed(this::armScanner, 900L);
        }
    }

    private void toggleTorch() {
        if (scanner == null) return;
        try {
            torchOn = !torchOn;
            if (torchOn) scanner.setTorchOn(); else scanner.setTorchOff();
            torchButton.setText(torchOn
                    ? (arabic ? "إطفاء الإضاءة" : "Torch off")
                    : (arabic ? "الإضاءة" : "Torch"));
        } catch (Throwable error) {
            torchOn = false;
            showError(arabic ? "الإضاءة غير متاحة في هذا الجهاز" : "Torch is unavailable on this device");
        }
    }

    private void showError(String message) {
        if (status != null) {
            status.setText(message);
            status.setTextColor(0xFFFF7B86);
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) armScanner();
        else showError(arabic ? "صلاحية الكاميرا مطلوبة لمسح QR" : "Camera permission is required to scan QR");
    }

    @Override protected void onResume() {
        super.onResume();
        if (scanner != null && (Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)) {
            try { scanner.resume(); } catch (Throwable ignored) { }
        }
    }

    @Override protected void onPause() {
        try { if (scanner != null) scanner.pause(); } catch (Throwable ignored) { }
        super.onPause();
    }

    @Override protected void onDestroy() {
        try { if (scanner != null) { scanner.setTorchOff(); scanner.pause(); } } catch (Throwable ignored) { }
        scanner = null;
        super.onDestroy();
    }

    private TextView button(String value) {
        TextView view = text(value, 14, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setClickable(true);
        view.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF1B2028);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), 0xFF3B4553);
        view.setBackground(background);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity((arabic ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
