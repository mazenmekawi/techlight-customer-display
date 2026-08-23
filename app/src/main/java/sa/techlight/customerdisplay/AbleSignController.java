package sa.techlight.customerdisplay;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

public final class AbleSignController {
    public static final String PACKAGE = "tv.ablesign.app";
    private final Context context;
    public AbleSignController(Context context){ this.context = context; }
    public boolean isInstalled(){
        return context.getPackageManager().getLaunchIntentForPackage(PACKAGE) != null;
    }
    public boolean openPlayer(){
        PackageManager pm = context.getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(PACKAGE);
        if(i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(i);
        return true;
    }
}
