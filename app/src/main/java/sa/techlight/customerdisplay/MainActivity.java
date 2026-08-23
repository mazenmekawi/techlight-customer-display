package sa.techlight.customerdisplay;

import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.net.Uri;import android.os.*;import android.view.*;import android.widget.*;import com.google.zxing.integration.android.IntentIntegrator;import com.google.zxing.integration.android.IntentResult;import java.util.*;

public final class MainActivity extends Activity implements TechProClient.Listener {
    private LinearLayout root,list,content; private TextView status,total,title,footer; private ImageView logo; private TechProClient client; private AbleSignController able; private SharedPreferences ui;
    private final Handler handler=new Handler(Looper.getMainLooper()); private final Runnable idleTask=()->{if(able!=null&&!able.openPlayer()) Toast.makeText(this,"AbleSign غير مثبت",Toast.LENGTH_LONG).show();};
    int accent=Color.rgb(91,42,134);
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(5894);able=new AbleSignController(this);ui=getSharedPreferences("ui",0);buildUi();restoreOrPair();}
    TextView text(String s,int sp,int c){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(c);t.setPadding(14,10,14,10);return t;}
    Button btn(String s){Button b=new Button(this);b.setText(s);b.setTextSize(16);b.setAllCaps(false);return b;}
    GradientDrawable bg(int color,float radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);return g;}
    void buildUi(){
        try{accent=Color.parseColor(ui.getString("color","#5B2A86"));}catch(Exception ignored){}
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(247,247,249));root.setPadding(24,18,24,18);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);title=text(ui.getString("welcome","أهلًا وسهلًا بك"),28,accent);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button settings=btn("⚙ الإعدادات");settings.setOnClickListener(v->startActivityForResult(new Intent(this,SettingsActivity.class),77));top.addView(settings);root.addView(top);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.HORIZONTAL);content.setPadding(0,12,0,12);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        buildTemplate();
        footer=text(ui.getString("footer","نسعد بخدمتكم دائمًا"),14,Color.GRAY);footer.setGravity(Gravity.CENTER);root.addView(footer);
        setContentView(root);
    }
    void buildTemplate(){
        content.removeAllViews();int template=ui.getInt("template",0);
        LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);left.setPadding(22,18,22,18);left.setBackground(bg(Color.WHITE,24));
        status=text("جاهز للربط مع Tech Pro",15,Color.GRAY);left.addView(status);
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);ScrollView sv=new ScrollView(this);sv.addView(list);left.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setGravity(Gravity.CENTER);right.setPadding(22,22,22,22);right.setBackground(bg(Color.rgb(242,238,247),24));
        logo=new ImageView(this);logo.setAdjustViewBounds(true);logo.setMaxHeight(120);String u=ui.getString("logo",null);if(u!=null)try{logo.setImageURI(Uri.parse(u));}catch(Exception ignored){}else logo.setImageResource(R.drawable.ic_techlight);right.addView(logo,new LinearLayout.LayoutParams(140,110));
        TextView brand=text("إجمالي الطلب",18,Color.DKGRAY);brand.setGravity(Gravity.CENTER);right.addView(brand);total=text("0.00 ر.س",42,accent);total.setTypeface(Typeface.DEFAULT,Typeface.BOLD);total.setGravity(Gravity.CENTER);right.addView(total);
        TextView ad=text("الإعلانات تعمل تلقائيًا وقت عدم وجود طلب",14,Color.GRAY);ad.setGravity(Gravity.CENTER);right.addView(ad);
        if(template==1){content.setOrientation(LinearLayout.VERTICAL);content.addView(right,new LinearLayout.LayoutParams(-1,190));content.addView(left,new LinearLayout.LayoutParams(-1,0,1));}
        else if(template==2){left.setPadding(44,26,44,26);content.addView(left,new LinearLayout.LayoutParams(0,-1,4));content.addView(right,new LinearLayout.LayoutParams(0,-1,1));}
        else {content.addView(left,new LinearLayout.LayoutParams(0,-1,3));content.addView(right,new LinearLayout.LayoutParams(0,-1,2));}
    }
    void restoreOrPair(){SharedPreferences p=getSharedPreferences("pair",0);String ip=p.getString("ip",null);int port=p.getInt("port",4040);if(ip!=null){connect(ip,port);return;}showPairingPanel();}
    void showPairingPanel(){
        list.removeAllViews();LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(30,30,30,30);
        TextView h=text("ربط شاشة العميل",26,accent);h.setGravity(Gravity.CENTER);box.addView(h);TextView d=text("امسح QR الظاهر في Tech Pro أو أدخل بيانات الربط يدويًا",16,Color.DKGRAY);d.setGravity(Gravity.CENTER);box.addView(d);
        Button scan=btn("مسح QR بالكاميرا");scan.setOnClickListener(v->scanQr());box.addView(scan,new LinearLayout.LayoutParams(-1,64));Button manual=btn("إدخال يدوي");manual.setOnClickListener(v->manualPair());box.addView(manual,new LinearLayout.LayoutParams(-1,64));list.addView(box);status.setText("غير مرتبط");
    }
    void scanQr(){IntentIntegrator in=new IntentIntegrator(this);in.setPrompt("وجّه الكاميرا إلى QR الخاص بـ Tech Pro");in.setBeepEnabled(true);in.setOrientationLocked(false);in.initiateScan();}
    void manualPair(){final EditText e=new EditText(this);e.setHint("{\"type\":\"pos_pair\",\"ip\":\"192.168.100.23\",\"port\":4040}");new AlertDialog.Builder(this).setTitle("ربط Tech Pro").setView(e).setPositiveButton("ربط",(d,w)->pairText(e.getText().toString())).setNegativeButton("إلغاء",null).show();}
    void pairText(String raw){try{PairingParser.PairingInfo pi=PairingParser.parse(raw);getSharedPreferences("pair",0).edit().putString("ip",pi.ip).putInt("port",pi.port).apply();connect(pi.ip,pi.port);}catch(Exception e){Toast.makeText(this,"QR غير صحيح",Toast.LENGTH_LONG).show();showPairingPanel();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);IntentResult qr=IntentIntegrator.parseActivityResult(request,result,data);if(qr!=null){if(qr.getContents()!=null)pairText(qr.getContents());return;}if(request==77){buildUi();restoreOrPair();}}
    void connect(String ip,int port){status.setText("جارٍ الاتصال بـ "+ip+":"+port);if(client!=null)client.stop();client=new TechProClient(ip,port,this);client.start();}
    @Override public void onConnected(){runOnUiThread(()->{status.setText("متصل بـ Tech Pro");scheduleIdle(5000);});}
    @Override public void onDisconnected(String reason){runOnUiThread(()->status.setText("إعادة الاتصال…"));}
    @Override public void onRaw(String raw){}
    @Override public void onOrder(OrderState o){runOnUiThread(()->{handler.removeCallbacks(idleTask);list.removeAllViews();for(OrderState.Item i:o.items){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(8,8,8,8);TextView n=text(i.name+"  × "+i.qty,22,Color.rgb(35,35,40));TextView p=text(String.format(Locale.US,"%.2f ر.س",i.total()),22,Color.DKGRAY);p.setGravity(Gravity.END);row.addView(n,new LinearLayout.LayoutParams(0,-2,1));row.addView(p,new LinearLayout.LayoutParams(210,-2));list.addView(row);}total.setText(String.format(Locale.US,"%.2f ر.س",o.total));status.setText(o.completed?ui.getString("thanks","شكرًا لزيارتكم"):"طلبك الحالي");if(o.completed)scheduleIdle(7000);});}
    void scheduleIdle(long ms){handler.removeCallbacks(idleTask);handler.postDelayed(idleTask,ms);}
    @Override protected void onDestroy(){if(client!=null)client.stop();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
