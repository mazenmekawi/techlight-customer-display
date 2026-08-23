package sa.techlight.customerdisplay;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * Explicit scanner activity so QR capture does not depend on manifest discovery
 * or on the device advertising a camera feature flag.
 */
public final class QrCaptureActivity extends CaptureActivity {
}
