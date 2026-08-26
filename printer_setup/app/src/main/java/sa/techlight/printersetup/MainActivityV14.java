package sa.techlight.printersetup;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivityV14 extends Activity {
    private static final String USB_PERMISSION = "sa.techlight.printersetup.USB_PERMISSION_V14";
    private static final String PREFS = "pp9000eu_v14";
    private static final int ACTION_SETUP = 1;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UsbManager usb;
    private SharedPreferences prefs;
    private boolean ar = true;
    private int pendingAction = 0;

    private TextView usbText, tabletText, printerText, progressText, statusTitle, statusText;
    private Button setupBtn, verifyBtn, resetBtn, langBtn;
    private LinearLayout statusCard;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                int a = pendingAction;
                pendingAction = 0;
                refreshUi();
                if (!granted) {
                    error(tr("تم رفض إذن USB", "USB permission denied"), tr("اسمح للتطبيق بالوصول للطابعة مرة واحدة لكتابة إعدادات الشبكة.", "Allow USB access once so the app can write network settings."));
                    return;
                }
                if (a == ACTION_SETUP) startSetup();
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                refreshUi();
                if (hasPending()) {
                    progressText.setText(tr("✓ تم إرسال IP\n✓ تم إيقاف الطابعة\n… شغّل الطابعة وانتظر التحقق من LAN", "✓ IP sent\n✓ Printer powered off\n… Turn it on and wait for LAN verification"));
                    neutral(tr("بانتظار تشغيل الطابعة", "Waiting for printer"), tr("بعد هذه النقطة لا نحتاج USB للتحقق. التحقق يتم من LAN فقط.", "USB is no longer required for verification. Verification uses LAN only."));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                refreshUi();
                if (hasPending()) verifyLater();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ar = !"en".equals(prefs.getString("lang", "ar"));
        usb = (UsbManager)getSystemService(Context.USB_SERVICE);
        buildUi();
        IntentFilter f = new IntentFilter();
        f.addAction(USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
        refreshUi();
        if (hasPending()) verifyLater();
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasPending()) verifyLater();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(0xFFF7F7FB);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        sc.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("T", 32, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(0xFF6D28D9, 15));
        top.addView(mark, lp(dp(54), dp(54), 0));
        TextView brand = text("TECHLIGHT\nضوء التقنية", 16, 0xFF2A2148, true);
        LinearLayout.LayoutParams brandLp = lp(0, -2, 1); brandLp.setMargins(dp(12),0,dp(12),0); top.addView(brand, brandLp);
        langBtn = secondary(ar ? "English" : "العربية");
        langBtn.setOnClickListener(v -> { prefs.edit().putString("lang", ar ? "en" : "ar").apply(); recreate(); });
        top.addView(langBtn, lp(dp(100), dp(44), 0));
        root.addView(top, lp(-1, dp(62), 0));

        TextView title = text(tr("إعداد شبكة ProPOS PP9000EU", "ProPOS PP9000EU Network Setup"), 25, 0xFF211B34, true);
        LinearLayout.LayoutParams titleLp=lp(-1,-2,0); titleLp.topMargin=dp(20); root.addView(title,titleLp);
        TextView sub = text(tr("يأخذ نفس شبكة التابلت، لكن يعطي الطابعة IP مختلفًا حتى لا يحدث تعارض.", "Uses the tablet's subnet, but gives the printer a different IP to avoid an IP conflict."), 14, 0xFF6B6678, false);
        LinearLayout.LayoutParams subLp=lp(-1,-2,0); subLp.topMargin=dp(6); root.addView(sub,subLp);

        LinearLayout card = card();
        LinearLayout.LayoutParams cardLp=lp(-1,-2,0); cardLp.topMargin=dp(18); root.addView(card,cardLp);
        card.addView(label(tr("USB للطابعة", "Printer USB"))); usbText=value("—"); card.addView(usbText);
        card.addView(divider());
        card.addView(label(tr("IP التابلت", "Tablet IP"))); tabletText=value("—"); card.addView(tabletText);
        card.addView(divider());
        card.addView(label(tr("IP الطابعة", "Printer IP"))); printerText=value(tr("لم يتم التعيين بعد", "Not assigned yet")); printerText.setTextColor(0xFF5B21B6); printerText.setTextSize(20); card.addView(printerText);
        card.addView(divider());
        card.addView(label(tr("الحالة", "Progress"))); progressText=text("—",14,0xFF514B5E,true); card.addView(progressText);

        statusCard = card();
        LinearLayout.LayoutParams statusLp=lp(-1,-2,0); statusLp.topMargin=dp(14); root.addView(statusCard,statusLp);
        statusTitle=text(tr("جاهز", "Ready"),16,0xFF211B34,true); statusText=text("",14,0xFF655F70,false);
        statusCard.addView(statusTitle); statusCard.addView(statusText);

        setupBtn = primary(tr("نسخ الشبكة وضبط IP للطابعة", "Use Tablet Network & Set Printer IP"));
        LinearLayout.LayoutParams btnLp=lp(-1,dp(60),0); btnLp.topMargin=dp(17); root.addView(setupBtn,btnLp);
        setupBtn.setOnClickListener(v -> beginSetupAction());

        verifyBtn = secondary(tr("تحقق من IP عبر LAN", "Verify IP over LAN"));
        LinearLayout.LayoutParams verifyLp=lp(-1,dp(50),0); verifyLp.topMargin=dp(10); root.addView(verifyBtn,verifyLp);
        verifyBtn.setOnClickListener(v -> verifyPending());

        resetBtn = secondary(tr("بدء من جديد", "Start Over"));
        LinearLayout.LayoutParams resetLp=lp(-1,dp(48),0); resetLp.topMargin=dp(10); root.addView(resetBtn,resetLp);
        resetBtn.setOnClickListener(v -> {
            prefs.edit().clear().putString("lang", ar ? "ar" : "en").apply();
            refreshUi();
            neutral(tr("تم مسح المحاولة السابقة", "Previous attempt cleared"), tr("يمكنك الآن ضبط الطابعة من جديد.", "You can configure the printer again."));
        });

        TextView foot=text("Techlight • ضوء التقنية • PP9000EU • v1.4",11,0xFF8A8490,false); foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footLp=lp(-1,-2,0); footLp.topMargin=dp(18); root.addView(foot,footLp);
        setContentView(sc);
    }

    private void refreshUi() {
        UsbDevice d = findPrinter();
        if (d == null) usbText.setText(tr("غير متصلة", "Not connected"));
        else if (usb.hasPermission(d)) usbText.setText(tr("متصلة ✓", "Connected ✓") + "  VID:PID " + String.format(Locale.US,"%04X:%04X",d.getVendorId(),d.getProductId()));
        else usbText.setText(tr("متصلة — تحتاج إذن مرة واحدة", "Connected — permission needed once"));

        NetInfo n = netInfo();
        if (n == null) tabletText.setText(tr("لا توجد شبكة IPv4", "No IPv4 network"));
        else tabletText.setText(n.ip.getHostAddress()+"  •  GW "+n.gw.getHostAddress()+"  /"+n.prefix);

        String verified=prefs.getString("verified_ip",null);
        String pending=prefs.getString("pending_ip",null);
        String tablet=prefs.getString("tablet_ip",null);
        if(verified!=null){
            printerText.setText(verified+" ✓");
            progressText.setText(tr("✓ إعدادات الشبكة\n✓ إعادة تشغيل الطابعة\n✓ تم التحقق عبر LAN:9100", "✓ Network settings\n✓ Printer restart\n✓ Verified over LAN:9100"));
            setupBtn.setText(tr("تم تغيير IP بنجاح", "IP Change Complete"));
        }else if(pending!=null){
            printerText.setText((tablet==null?"":tablet+"  →  ")+pending);
            progressText.setText(tr("✓ تم إرسال الإعدادات\n… أطفئ الطابعة 5 ثوانٍ وشغّلها\n… التحقق التالي LAN فقط", "✓ Settings sent\n… Power off for 5 seconds, then on\n… Next verification is LAN only"));
            setupBtn.setText(tr("بانتظار التحقق من LAN", "Waiting for LAN Verification"));
        }else{
            printerText.setText(tr("سيتم اختيار أقرب IP فاضي بعد IP التابلت", "The nearest free IP after the tablet IP will be selected"));
            progressText.setText(tr("1) يقرأ شبكة التابلت\n2) يختار IP مختلفًا وفاضيًا\n3) يكتبه للطابعة عبر USB\n4) بعد إعادة التشغيل يتحقق عبر LAN", "1) Read tablet network\n2) Pick a different free IP\n3) Write it over USB\n4) After restart, verify over LAN"));
            setupBtn.setText(tr("نسخ الشبكة وضبط IP للطابعة", "Use Tablet Network & Set Printer IP"));
        }
    }

    private void beginSetupAction() {
        if (hasPending()) { verifyPending(); return; }
        UsbDevice d=findPrinter();
        if(d==null){error(tr("الطابعة غير متصلة", "Printer not connected"),tr("وصّل USB فقط لمرحلة كتابة الإعدادات.", "Connect USB for the configuration-write stage."));return;}
        if(!usb.hasPermission(d)){
            pendingAction=ACTION_SETUP;
            Intent x=new Intent(USB_PERMISSION).setPackage(getPackageName());
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            usb.requestPermission(d,PendingIntent.getBroadcast(this,0,x,flags));
            return;
        }
        startSetup();
    }

    private void startSetup() {
        busy(true);
        neutral(tr("جاري قراءة شبكة التابلت…", "Reading tablet network…"), tr("لن يتم نسخ نفس IP حرفيًا؛ سيتم استخدام نفس الشبكة مع عنوان مختلف.", "The exact tablet IP will not be copied; the same subnet will be used with a different address."));
        worker.execute(() -> {
            NetInfo n=netInfo();
            if(n==null){fail(tr("تعذر قراءة شبكة التابلت.","Could not read the tablet network."));return;}
            Inet4Address target=chooseNearestFree(n);
            if(target==null){fail(tr("لم أجد IP فاضيًا قريبًا من IP التابلت.","No nearby free IP was found."));return;}
            Inet4Address mask;
            try{mask=longToIp(maskForPrefix(n.prefix));}catch(Exception e){fail(tr("تعذر حساب Subnet Mask.","Could not calculate subnet mask."));return;}
            UsbDevice d=findPrinter();
            if(d==null||!usb.hasPermission(d)){fail(tr("اتصال USB غير متاح قبل كتابة الإعدادات.","USB is unavailable before writing settings."));return;}

            String tabletIp=n.ip.getHostAddress();
            String targetIp=target.getHostAddress();
            postNeutral(tr("سيتم ضبط الطابعة", "Printer address selected"), tabletIp+"  →  "+targetIp);

            boolean ok=true;
            ok &= sendCommand(d, dhcpOff(), 1800); sleep(350);
            ok &= sendCommand(d, address((byte)0x46, mask), 1800); sleep(350);
            ok &= sendCommand(d, address((byte)0x45, n.gw), 1800); sleep(350);
            ok &= sendCommand(d, address((byte)0x50, target), 2200); sleep(700);
            ok &= sendCommand(d, address((byte)0x50, target), 2200);

            if(!ok){fail(tr("تعذر إكمال كتابة إعدادات الشبكة عبر USB.","Could not finish writing the network settings over USB."));return;}
            prefs.edit().putString("tablet_ip",tabletIp).putString("pending_ip",targetIp).remove("verified_ip").apply();
            runOnUiThread(() -> {
                busy(false);refreshUi();
                warning(tr("تم إرسال IP للطابعة", "Printer IP sent"), tr("IP التابلت: ","Tablet IP: ")+tabletIp+"\n"+tr("IP الطابعة: ","Printer IP: ")+targetIp+tr("\n\nالآن أطفئ الطابعة 5 ثوانٍ ثم شغّلها. من هذه اللحظة التحقق يتم عبر LAN فقط ولن نعتبر فصل USB خطأ.","\n\nNow power the printer off for 5 seconds, then on. From this point verification uses LAN only; USB disconnect is not an error."));
            });
        });
    }

    private boolean sendCommand(UsbDevice d, byte[] command, int timeout) {
        try(UsbSession s=openUsb(d)){
            if(s==null)return false;
            int r=s.connection.bulkTransfer(s.out,command,command.length,timeout);
            return r==command.length;
        }catch(Exception e){return false;}
    }

    private void verifyLater(){worker.execute(()->{sleep(3000);verifyPendingInternal(false);});}

    private void verifyPending(){
        if(!hasPending()){
            String verified=prefs.getString("verified_ip",null);
            if(verified!=null)success(tr("IP مؤكد", "IP verified"),verified+"  •  LAN:9100 ✓");
            else neutral(tr("لا يوجد IP بانتظار التحقق", "No pending IP"),tr("اضغط ضبط IP للطابعة أولًا.","Set the printer IP first."));
            return;
        }
        busy(true);
        neutral(tr("جاري التحقق عبر LAN فقط…", "Verifying over LAN only…"),tr("USB غير مطلوب في هذه المرحلة.","USB is not required at this stage."));
        worker.execute(()->verifyPendingInternal(true));
    }

    private void verifyPendingInternal(boolean fromButton){
        String ip=prefs.getString("pending_ip",null);
        if(ip==null){runOnUiThread(()->busy(false));return;}
        boolean ok=waitPort(ip,9100,fromButton?25000:10000);
        if(ok){
            prefs.edit().putString("verified_ip",ip).remove("pending_ip").apply();
            runOnUiThread(()->{busy(false);refreshUi();success(tr("تم تغيير IP بنجاح ✓", "IP changed successfully ✓"),tr("IP الطابعة المؤكد: ","Verified printer IP: ")+ip+"\nLAN:9100 ✓");});
        }else if(fromButton){
            runOnUiThread(()->{busy(false);refreshUi();warning(tr("الطابعة لم تظهر على LAN بعد", "Printer not visible on LAN yet"),tr("لا يوجد فشل USB هنا. المطلوب فقط التأكد أن الطابعة أُعيد تشغيلها وأن كابل LAN موصول.\nIP الجاري اختباره: ","There is no USB failure at this stage. Make sure the printer was restarted and LAN is connected.\nIP being checked: ")+ip);});
        }
    }

    private UsbDevice findPrinter(){
        if(usb==null)return null;UsbDevice best=null;int scoreBest=-999;
        for(UsbDevice d:usb.getDeviceList().values()){
            int score=0;if(d.getVendorId()==0x0483&&d.getProductId()==0x5743)score+=500;
            for(int i=0;i<d.getInterfaceCount();i++){
                UsbInterface f=d.getInterface(i);if(f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER)score+=100;
                for(int e=0;e<f.getEndpointCount();e++){UsbEndpoint ep=f.getEndpoint(e);if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK&&ep.getDirection()==UsbConstants.USB_DIR_OUT)score+=10;}
            }
            if(score>scoreBest){scoreBest=score;best=d;}
        }
        return scoreBest>=10?best:null;
    }

    private UsbSession openUsb(UsbDevice d){
        UsbInterface chosen=null;UsbEndpoint out=null;
        for(int i=0;i<d.getInterfaceCount();i++){
            UsbInterface f=d.getInterface(i);UsbEndpoint candidate=null;
            for(int e=0;e<f.getEndpointCount();e++){UsbEndpoint ep=f.getEndpoint(e);if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK&&ep.getDirection()==UsbConstants.USB_DIR_OUT)candidate=ep;}
            if(candidate!=null){chosen=f;out=candidate;if(f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER)break;}
        }
        if(chosen==null||out==null)return null;UsbDeviceConnection c=usb.openDevice(d);if(c==null)return null;if(!c.claimInterface(chosen,true)){c.close();return null;}return new UsbSession(c,chosen,out);
    }

    private static final class UsbSession implements Closeable{
        final UsbDeviceConnection connection;final UsbInterface intf;final UsbEndpoint out;
        UsbSession(UsbDeviceConnection c,UsbInterface f,UsbEndpoint o){connection=c;intf=f;out=o;}
        @Override public void close(){try{connection.releaseInterface(intf);}catch(Exception ignored){}connection.close();}
    }

    private NetInfo netInfo(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return null;Network nw=cm.getActiveNetwork();if(nw==null)return null;LinkProperties lp=cm.getLinkProperties(nw);if(lp==null)return null;
        Inet4Address ip=null,gw=null;int prefix=24;
        for(LinkAddress a:lp.getLinkAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()){ip=(Inet4Address)a.getAddress();prefix=a.getPrefixLength();break;}
        if(ip==null)return null;for(RouteInfo r:lp.getRoutes())if(r.isDefaultRoute()&&r.getGateway() instanceof Inet4Address){gw=(Inet4Address)r.getGateway();break;}
        if(gw==null)try{long network=ipToLong(ip)&maskForPrefix(prefix);gw=longToIp(network+1);}catch(Exception ignored){}
        return gw==null?null:new NetInfo(ip,gw,prefix);
    }
    private static final class NetInfo{final Inet4Address ip,gw;final int prefix;NetInfo(Inet4Address i,Inet4Address g,int p){ip=i;gw=g;prefix=p;}}

    private Inet4Address chooseNearestFree(NetInfo n){
        try{
            long ip=ipToLong(n.ip),mask=maskForPrefix(n.prefix),network=ip&mask,broadcast=network|(~mask&0xFFFFFFFFL),gw=ipToLong(n.gw);
            int checked=0;
            for(long v=ip+1;v<broadcast&&checked<150;v++,checked++){if(v==gw)continue;Inet4Address a=longToIp(v);if(!inUse(a))return a;}
            for(long v=network+2;v<ip&&checked<300;v++,checked++){if(v==gw)continue;Inet4Address a=longToIp(v);if(!inUse(a))return a;}
        }catch(Exception ignored){}
        return null;
    }

    private boolean inUse(Inet4Address a){
        String h=a.getHostAddress();if(portOpen(h,9100,120)||portOpen(h,80,100))return true;try{return a.isReachable(120);}catch(IOException e){return false;}
    }
    private static boolean portOpen(String host,int port,int timeout){try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),timeout);return true;}catch(IOException e){return false;}}
    private static boolean waitPort(String host,int port,int total){long end=System.currentTimeMillis()+total;while(System.currentTimeMillis()<end){if(portOpen(host,port,500))return true;sleep(800);}return false;}

    private static byte[] dhcpOff(){return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,(byte)0xFA,0x00,0x5A};}
    private static byte[] address(byte selector,Inet4Address a){byte[]b=a.getAddress();return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,selector,b[0],b[1],b[2],b[3]};}
    private static long ipToLong(Inet4Address a){byte[]b=a.getAddress();return((long)(b[0]&255)<<24)|((long)(b[1]&255)<<16)|((long)(b[2]&255)<<8)|(long)(b[3]&255);}
    private static Inet4Address longToIp(long v)throws Exception{return(Inet4Address)InetAddress.getByAddress(new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v});}
    private static long maskForPrefix(int p){if(p<=0)return 0;if(p>=32)return 0xFFFFFFFFL;return(0xFFFFFFFFL<<(32-p))&0xFFFFFFFFL;}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private boolean hasPending(){return prefs.getString("pending_ip",null)!=null;}

    private void busy(boolean b){runOnUiThread(()->{setupBtn.setEnabled(!b);verifyBtn.setEnabled(!b);resetBtn.setEnabled(!b);langBtn.setEnabled(!b);});}
    private void fail(String d){runOnUiThread(()->{busy(false);error(tr("تعذر ضبط IP", "Could not set IP"),d);});}
    private void postNeutral(String t,String d){runOnUiThread(()->neutral(t,d));}
    private void neutral(String t,String d){showStatus(0,t,d);}
    private void success(String t,String d){showStatus(1,t,d);}
    private void error(String t,String d){showStatus(2,t,d);}
    private void warning(String t,String d){showStatus(3,t,d);}
    private void showStatus(int kind,String t,String d){runOnUiThread(()->{statusTitle.setText(t);statusText.setText(d);int bgc=kind==1?0xFFE8F7EF:kind==2?0xFFFCEBED:kind==3?0xFFFFF5DD:0xFFF0EFF4;int fg=kind==1?0xFF168A55:kind==2?0xFFC73B45:kind==3?0xFF9A6700:0xFF211B34;statusCard.setBackground(round(bgc,18));statusTitle.setTextColor(fg);});}

    private String tr(String arText,String enText){return ar?arText:enText;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int w,int h,float weight){return new LinearLayout.LayoutParams(w,h,weight);}
    private GradientDrawable round(int c,int r){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));return g;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.12f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private TextView label(String s){TextView v=text(s,13,0xFF777181,false);LinearLayout.LayoutParams p=lp(-1,-2,0);p.bottomMargin=dp(5);v.setLayoutParams(p);return v;}
    private TextView value(String s){return text(s,17,0xFF211B34,true);}
    private View divider(){View v=new View(this);v.setBackgroundColor(0xFFE7E4EE);LinearLayout.LayoutParams p=lp(-1,dp(1),0);p.topMargin=dp(15);p.bottomMargin=dp(15);v.setLayoutParams(p);return v;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(16),dp(18),dp(16));c.setBackground(round(Color.WHITE,20));return c;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setTextSize(17);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF7C3AED,0xFF5B21B6});g.setCornerRadius(dp(16));b.setBackground(g);return b;}
    private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(0xFF4C1D95);b.setAllCaps(false);b.setBackground(round(0xFFF2EEFB,14));return b;}
}
