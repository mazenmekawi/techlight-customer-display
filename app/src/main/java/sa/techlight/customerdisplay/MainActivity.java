package sa.techlight.customerdisplay;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.util.*;

public final class MainActivity extends Activity implements TechProClient.Listener {
    private static final int CAMERA_REQ=501;
    private static final int SETTINGS_REQ=77;
    private LinearLayout root,orderList,body;
    private TextView status,total,title,footer,statusDot,emptyTitle,emptySub;
    private ImageView logo;
    private TechProClient client;
    private AbleSignController able;
    private SharedPreferences ui;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable idleTask=()->{ if(able!=null&&!able.openPlayer()) showToast("AbleSign غير مثبت على الجهاز"); };
    private int accent=Color.rgb(91,42,134);

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        able=new AbleSignController(this);
        ui=getSharedPreferences("ui",0);
        buildUi();
        restoreOrPair();
    }

    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int sp,int c){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(c);t.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);t.setPadding(dp(8),dp(6),dp(8),dp(6));return t;}
    private GradientDrawable round(int color,float radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp((int)radius));return g;}
    private GradientDrawable strokeBg(int color,int strokeColor,float radius){GradientDrawable g=round(color,radius);g.setStroke(dp(1),strokeColor);return g;}
    private Button action(String label,boolean primary){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setMinHeight(dp(54));b.setPadding(dp(18),0,dp(18),0);b.setTextColor(primary?Color.WHITE:accent);b.setBackground(primary?round(accent,18):strokeBg(Color.WHITE,0xFFE4E1E8,18));return b;
    }

    private void buildUi(){
        try{accent=Color.parseColor(ui.getString("color","#5B2A86"));}catch(Exception ignored){accent=Color.rgb(91,42,134);}
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(26),dp(18),dp(26),dp(14));
        GradientDrawable page=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFFF7F5FA,0xFFFFFFFF});root.setBackground(page);

        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(4),0,dp(4),dp(10));
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.HORIZONTAL);brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView techIcon=new ImageView(this);techIcon.setImageResource(R.drawable.ic_techlight);brand.addView(techIcon,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout brandText=new LinearLayout(this);brandText.setOrientation(LinearLayout.VERTICAL);brandText.setPadding(dp(10),0,0,0);
        TextView company=text("ضوء التقنية",19,0xFF222127);company.setTypeface(Typeface.DEFAULT,Typeface.BOLD);company.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);brandText.addView(company);
        TextView product=text("شاشة العميل الذكية",12,0xFF85818B);product.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);brandText.addView(product);brand.addView(brandText);
        top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));

        LinearLayout live=new LinearLayout(this);live.setOrientation(LinearLayout.HORIZONTAL);live.setGravity(Gravity.CENTER);live.setPadding(dp(14),dp(7),dp(14),dp(7));live.setBackground(round(0xFFF1EDF6,18));
        statusDot=text("●",12,accent);statusDot.setPadding(0,0,dp(5),0);live.addView(statusDot);status=text("جاهز",13,0xFF514C58);status.setGravity(Gravity.CENTER);status.setPadding(0,0,0,0);live.addView(status);top.addView(live);

        Button settingsBtn=action("⚙",false);settingsBtn.setTextSize(21);settingsBtn.setMinWidth(dp(56));settingsBtn.setPadding(0,0,0,0);settingsBtn.setOnClickListener(v->startActivityForResult(new Intent(this,SettingsActivity.class),SETTINGS_REQ));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(58),dp(52));sp.setMargins(dp(10),0,0,0);top.addView(settingsBtn,sp);
        root.addView(top);

        title=text(ui.getString("welcome","أهلًا وسهلًا بك"),29,0xFF242128);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.RIGHT);title.setPadding(dp(8),dp(4),dp(8),dp(8));root.addView(title);

        body=new LinearLayout(this);body.setOrientation(LinearLayout.HORIZONTAL);body.setGravity(Gravity.CENTER);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        buildTemplate();

        footer=text(ui.getString("footer","نسعد بخدمتكم دائمًا"),13,0xFF8A858F);footer.setGravity(Gravity.CENTER);footer.setPadding(dp(8),dp(8),dp(8),0);root.addView(footer);
        setContentView(root);
        animateEntrance();
    }

    private void animateEntrance(){
        root.setAlpha(0f);root.setTranslationY(dp(10));root.animate().alpha(1f).translationY(0).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
        title.setAlpha(0f);title.animate().alpha(1f).setStartDelay(180).setDuration(450).start();
    }

    private void buildTemplate(){
        body.removeAllViews();int template=ui.getInt("template",0);
        LinearLayout orderCard=new LinearLayout(this);orderCard.setOrientation(LinearLayout.VERTICAL);orderCard.setPadding(dp(22),dp(18),dp(22),dp(18));orderCard.setBackground(strokeBg(Color.WHITE,0xFFEAE7ED,24));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView orderLabel=text("تفاصيل الطلب",18,0xFF353039);orderLabel.setTypeface(Typeface.DEFAULT,Typeface.BOLD);header.addView(orderLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView liveBadge=text("مباشر",12,accent);liveBadge.setGravity(Gravity.CENTER);liveBadge.setBackground(round(0xFFF3EDF8,16));liveBadge.setPadding(dp(11),dp(5),dp(11),dp(5));header.addView(liveBadge);orderCard.addView(header);
        View divider=new View(this);divider.setBackgroundColor(0xFFF0EDF2);LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(-1,dp(1));dpv.setMargins(0,dp(12),0,dp(8));orderCard.addView(divider,dpv);
        orderList=new LinearLayout(this);orderList.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(orderList);orderCard.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout summary=new LinearLayout(this);summary.setOrientation(LinearLayout.VERTICAL);summary.setGravity(Gravity.CENTER);summary.setPadding(dp(26),dp(26),dp(26),dp(26));
        GradientDrawable sumBg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{lighten(accent,0.90f),0xFFFFFFFF});sumBg.setCornerRadius(dp(24));summary.setBackground(sumBg);
        logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);String u=ui.getString("logo",null);if(u!=null)try{logo.setImageURI(Uri.parse(u));}catch(Exception ignored){logo.setImageResource(R.drawable.ic_techlight);}else logo.setImageResource(R.drawable.ic_techlight);summary.addView(logo,new LinearLayout.LayoutParams(dp(150),dp(100)));
        TextView totalLabel=text("إجمالي طلبك",17,0xFF605968);totalLabel.setGravity(Gravity.CENTER);summary.addView(totalLabel);
        total=text("0.00 ر.س",45,accent);total.setTypeface(Typeface.DEFAULT,Typeface.BOLD);total.setGravity(Gravity.CENTER);total.setPadding(0,dp(4),0,dp(8));summary.addView(total);
        TextView safe=text("يتم تحديث الطلب فورًا من الكاشير",13,0xFF8D8792);safe.setGravity(Gravity.CENTER);summary.addView(safe);

        if(template==1){body.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(-1,0,2);a.setMargins(0,0,0,dp(12));body.addView(orderCard,a);body.addView(summary,new LinearLayout.LayoutParams(-1,0,1));}
        else if(template==2){body.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams s=new LinearLayout.LayoutParams(0,-1,1);s.setMargins(0,0,dp(12),0);body.addView(summary,s);body.addView(orderCard,new LinearLayout.LayoutParams(0,-1,2));}
        else {LinearLayout.LayoutParams o=new LinearLayout.LayoutParams(0,-1,3);o.setMargins(0,0,dp(14),0);body.addView(orderCard,o);body.addView(summary,new LinearLayout.LayoutParams(0,-1,2));}
        showEmptyOrder("بانتظار أول طلب","سيظهر الطلب هنا مباشرة عند إضافة صنف من الكاشير");
    }

    private int lighten(int color,float factor){int r=Color.red(color),g=Color.green(color),b=Color.blue(color);r=(int)(r+(255-r)*factor);g=(int)(g+(255-g)*factor);b=(int)(b+(255-b)*factor);return Color.rgb(Math.min(255,r),Math.min(255,g),Math.min(255,b));}

    private void showEmptyOrder(String heading,String sub){
        orderList.removeAllViews();LinearLayout empty=new LinearLayout(this);empty.setOrientation(LinearLayout.VERTICAL);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(30),dp(28),dp(30),dp(28));
        TextView icon=text("✦",36,accent);icon.setGravity(Gravity.CENTER);empty.addView(icon);
        emptyTitle=text(heading,22,0xFF38323B);emptyTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);emptyTitle.setGravity(Gravity.CENTER);empty.addView(emptyTitle);
        emptySub=text(sub,14,0xFF8B858F);emptySub.setGravity(Gravity.CENTER);empty.addView(emptySub);
        orderList.addView(empty,new LinearLayout.LayoutParams(-1,-1));
        pulse(icon);
    }

    private void pulse(View v){AlphaAnimation a=new AlphaAnimation(0.35f,1f);a.setDuration(900);a.setRepeatMode(Animation.REVERSE);a.setRepeatCount(Animation.INFINITE);v.startAnimation(a);}

    private void restoreOrPair(){SharedPreferences p=getSharedPreferences("pair",0);String ip=p.getString("ip",null);int port=p.getInt("port",4040);if(ip!=null){connect(ip,port);return;}showPairingPanel();}

    private void showPairingPanel(){
        handler.removeCallbacks(idleTask);orderList.removeAllViews();
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setGravity(Gravity.CENTER);wrap.setPadding(dp(26),dp(20),dp(26),dp(20));
        TextView scanIcon=text("⌗",42,accent);scanIcon.setGravity(Gravity.CENTER);wrap.addView(scanIcon);pulse(scanIcon);
        TextView h=text("اربط شاشة العميل",24,0xFF302B33);h.setTypeface(Typeface.DEFAULT,Typeface.BOLD);h.setGravity(Gravity.CENTER);wrap.addView(h);
        TextView d=text("افتح Tech Pro على جهاز الكاشير واعرض QR الاقتران، ثم امسحه هنا.",15,0xFF77717C);d.setGravity(Gravity.CENTER);d.setPadding(dp(20),dp(4),dp(20),dp(18));wrap.addView(d);
        Button scan=action("مسح QR بالكاميرا",true);scan.setOnClickListener(v->prepareCamera());wrap.addView(scan,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView alt=text("أو",12,0xFF9A949E);alt.setGravity(Gravity.CENTER);wrap.addView(alt);
        Button manual=action("إدخال IP والمنفذ يدويًا",false);manual.setOnClickListener(v->manualPair());wrap.addView(manual,new LinearLayout.LayoutParams(-1,dp(58)));
        orderList.addView(wrap,new LinearLayout.LayoutParams(-1,-1));setConnectionState("غير مرتبط",false);
    }

    private void prepareCamera(){
        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)){
            new AlertDialog.Builder(this).setTitle("لا توجد كاميرا في هذا الجهاز").setMessage("هذا الجهاز لا يحتوي على كاميرا. استخدم الإدخال اليدوي للـ IP والمنفذ، أو استخدم جهازًا يحتوي على كاميرا لمسح QR.").setPositiveButton("إدخال يدوي",(d,w)->manualPair()).setNegativeButton("إلغاء",null).show();return;
        }
        if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_REQ);return;}
        launchScanner();
    }

    private void launchScanner(){
        IntentIntegrator in=new IntentIntegrator(this);in.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);in.setPrompt("وجّه الكاميرا إلى QR الاقتران في Tech Pro");in.setBeepEnabled(true);in.setOrientationLocked(false);in.setCameraId(0);in.initiateScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==CAMERA_REQ){if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)launchScanner();else new AlertDialog.Builder(this).setTitle("صلاحية الكاميرا مطلوبة").setMessage("فعّل صلاحية الكاميرا حتى يستطيع التطبيق مسح QR.").setPositiveButton("فتح إعدادات التطبيق",(d,w)->{Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));startActivity(i);}).setNegativeButton("إلغاء",null).show();}}

    private void manualPair(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),0,dp(24),0);
        EditText ip=new EditText(this);ip.setHint("IP مثال: 192.168.100.23");ip.setSingleLine(true);form.addView(ip);
        EditText port=new EditText(this);port.setHint("Port مثال: 4040");port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);port.setSingleLine(true);port.setText("4040");form.addView(port);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("إدخال بيانات Tech Pro").setView(form).setPositiveButton("ربط",null).setNegativeButton("إلغاء",null).create();dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String ipv=ip.getText().toString().trim();String pv=port.getText().toString().trim();if(ipv.isEmpty()){ip.setError("اكتب IP");return;}try{int p=Integer.parseInt(pv);savePair(ipv,p);dialog.dismiss();}catch(Exception e){port.setError("منفذ غير صحيح");}}));dialog.show();
    }

    private void pairText(String raw){try{PairingParser.PairingInfo pi=PairingParser.parse(raw);savePair(pi.ip,pi.port);}catch(Exception e){showToast("QR غير صحيح أو لا يخص Tech Pro");showPairingPanel();}}
    private void savePair(String ip,int port){getSharedPreferences("pair",0).edit().putString("ip",ip).putInt("port",port).apply();connect(ip,port);}

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);IntentResult qr=IntentIntegrator.parseActivityResult(request,result,data);if(qr!=null){if(qr.getContents()!=null)pairText(qr.getContents());return;}if(request==SETTINGS_REQ){buildUi();restoreOrPair();}
    }

    private void connect(String ip,int port){setConnectionState("جارٍ الاتصال",false);showEmptyOrder("جارٍ الاتصال بـ Tech Pro",ip+":"+port);if(client!=null)client.stop();client=new TechProClient(ip,port,this);client.start();}
    private void setConnectionState(String text,boolean ok){status.setText(text);statusDot.setTextColor(ok?0xFF159A63:accent);}
    private void showToast(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}

    @Override public void onConnected(){runOnUiThread(()->{setConnectionState("متصل",true);showEmptyOrder("متصل وجاهز","بانتظار إضافة أصناف من جهاز الكاشير");scheduleIdle(5000);});}
    @Override public void onDisconnected(String reason){runOnUiThread(()->{setConnectionState("إعادة الاتصال",false);if(orderList.getChildCount()==0)showEmptyOrder("جارٍ إعادة الاتصال","تأكد أن الجهازين على نفس شبكة الواي فاي");});}
    @Override public void onRaw(String raw){}

    @Override public void onOrder(OrderState o){runOnUiThread(()->{
        handler.removeCallbacks(idleTask);orderList.removeAllViews();
        if(o.items==null||o.items.isEmpty()){showEmptyOrder("طلب جديد","بانتظار إضافة الأصناف");}
        else{
            for(OrderState.Item i:o.items){
                LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(round(0xFFF9F8FA,14));
                TextView qty=text("× "+i.qty,15,accent);qty.setTypeface(Typeface.DEFAULT,Typeface.BOLD);qty.setGravity(Gravity.CENTER);qty.setBackground(round(lighten(accent,0.92f),12));qty.setPadding(dp(10),dp(5),dp(10),dp(5));
                TextView name=text(i.name,20,0xFF2D2930);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);name.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
                TextView price=text(String.format(Locale.US,"%.2f ر.س",i.total()),19,0xFF57515B);price.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
                row.addView(price,new LinearLayout.LayoutParams(dp(170),-2));row.addView(name,new LinearLayout.LayoutParams(0,-2,1));row.addView(qty);
                LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(4),0,dp(4));orderList.addView(row,rp);row.setAlpha(0f);row.setTranslationX(dp(20));row.animate().alpha(1f).translationX(0).setDuration(250).start();
            }
        }
        total.setText(String.format(Locale.US,"%.2f ر.س",o.total));total.setScaleX(0.94f);total.setScaleY(0.94f);total.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        if(o.completed){setConnectionState(ui.getString("thanks","شكرًا لزيارتكم"),true);scheduleIdle(7000);}else setConnectionState("الطلب مباشر",true);
    });}

    private void scheduleIdle(long ms){handler.removeCallbacks(idleTask);handler.postDelayed(idleTask,ms);}
    @Override protected void onDestroy(){if(client!=null)client.stop();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
