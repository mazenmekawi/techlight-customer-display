package sa.techlight.customerdisplay;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class MainActivity extends Activity implements TechProClient.Listener {
    private LinearLayout root, list; private TextView status, total;
    private TechProClient client; private AbleSignController able;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable idleTask = () -> { if(able!=null) able.openPlayer(); };

    @Override public void onCreate(Bundle b){ super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        able=new AbleSignController(this); buildUi(); restoreOrPair();
    }
    private TextView text(String s,int sp){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.DKGRAY); t.setPadding(16,12,16,12); return t; }
    private void buildUi(){
        root=new LinearLayout(this); root.setOrientation(LinearLayout.HORIZONTAL); root.setPadding(28,22,28,22); root.setBackgroundColor(Color.WHITE);
        LinearLayout left=new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL); left.setLayoutParams(new LinearLayout.LayoutParams(0,-1,3));
        TextView title=text("طلبك",32); title.setTextColor(Color.rgb(92,45,145)); left.addView(title);
        status=text("بانتظار الاقتران…",16); left.addView(status);
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); ScrollView sv=new ScrollView(this); sv.addView(list); left.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout right=new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL); right.setGravity(Gravity.CENTER); right.setPadding(28,28,28,28); right.setBackgroundColor(Color.rgb(246,246,246)); right.setLayoutParams(new LinearLayout.LayoutParams(0,-1,2));
        right.addView(text("ضوء التقنية",28)); total=text("0.00 ر.س",40); total.setTextColor(Color.rgb(92,45,145)); right.addView(total);
        TextView hint=text("تظهر إعلانات AbleSign تلقائيًا وقت الخمول",15); right.addView(hint);
        root.addView(left); root.addView(right); setContentView(root);
    }
    private void restoreOrPair(){
        SharedPreferences p=getSharedPreferences("pair",0); String ip=p.getString("ip",null); int port=p.getInt("port",4040);
        if(ip!=null){ connect(ip,port); return; }
        final EditText input=new EditText(this); input.setSingleLine(false); input.setHint("الصق محتوى QR مثل {\"type\":\"pos_pair\",\"ip\":\"192.168.100.23\",\"port\":4040}");
        new android.app.AlertDialog.Builder(this).setTitle("ربط Tech Pro").setView(input).setCancelable(false)
          .setPositiveButton("ربط",(d,w)->{
            try{ PairingParser.PairingInfo pi=PairingParser.parse(input.getText().toString()); p.edit().putString("ip",pi.ip).putInt("port",pi.port).apply(); connect(pi.ip,pi.port); }
            catch(Exception e){ status.setText("كود الاقتران غير صحيح"); restoreOrPair(); }
          }).show();
    }
    private void connect(String ip,int port){ status.setText("الاتصال بـ Tech Pro: "+ip+":"+port); client=new TechProClient(ip,port,this); client.start(); }
    @Override public void onConnected(){ status.setText("متصل بـ Tech Pro"); scheduleIdle(5000); }
    @Override public void onDisconnected(String reason){ status.setText("إعادة الاتصال… "+reason); }
    @Override public void onRaw(String raw){ }
    @Override public void onOrder(OrderState o){
        handler.removeCallbacks(idleTask); list.removeAllViews();
        for(OrderState.Item i:o.items){
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(4,4,4,4);
            TextView n=text(i.name+"  × "+i.qty,22); TextView p=text(String.format(Locale.US,"%.2f",i.total()),22); p.setGravity(Gravity.END);
            row.addView(n,new LinearLayout.LayoutParams(0,-2,1)); row.addView(p,new LinearLayout.LayoutParams(180,-2)); list.addView(row);
        }
        total.setText(String.format(Locale.US,"%.2f ر.س", o.total));
        status.setText(o.completed?"تم إتمام الطلب — شكرًا لزيارتكم":"طلبك الحالي");
        if(o.completed) scheduleIdle(7000);
    }
    private void scheduleIdle(long ms){ handler.removeCallbacks(idleTask); handler.postDelayed(idleTask,ms); }
    @Override protected void onDestroy(){ if(client!=null) client.stop(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
