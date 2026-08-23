package sa.techlight.customerdisplay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SplashActivity extends Activity {
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER); root.setPadding(40,40,40,40);
        GradientDrawable gd=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(48,20,76),Color.rgb(102,55,148)}); root.setBackground(gd);
        ImageView icon=new ImageView(this); icon.setImageResource(sa.techlight.customerdisplay.R.drawable.ic_techlight); root.addView(icon,new LinearLayout.LayoutParams(132,132));
        TextView ar=new TextView(this); ar.setText("ضوء التقنية"); ar.setTextColor(Color.WHITE); ar.setTextSize(38); ar.setTypeface(Typeface.DEFAULT,Typeface.BOLD); ar.setGravity(Gravity.CENTER); ar.setPadding(0,26,0,6); root.addView(ar);
        TextView en=new TextView(this); en.setText("TechLight Customer Display"); en.setTextColor(Color.rgb(225,213,239)); en.setTextSize(18); en.setGravity(Gravity.CENTER); root.addView(en);
        TextView sub=new TextView(this); sub.setText("شاشة عميل ذكية • طلبات مباشرة • محتوى إعلاني"); sub.setTextColor(Color.WHITE); sub.setTextSize(15); sub.setGravity(Gravity.CENTER); sub.setPadding(0,20,0,0); root.addView(sub);
        setContentView(root);
        new Handler(Looper.getMainLooper()).postDelayed(()->{ startActivity(new Intent(this,MainActivity.class)); finish(); },2200);
    }
}
