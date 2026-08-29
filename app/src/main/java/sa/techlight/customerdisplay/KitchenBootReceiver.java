package sa.techlight.customerdisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Best-effort auto-start for Android TV / signage devices after reboot. */
public final class KitchenBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) return;
        try {
            Intent launch = new Intent(context, KitchenActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launch);
        } catch (Throwable ignored) { }
    }
}
