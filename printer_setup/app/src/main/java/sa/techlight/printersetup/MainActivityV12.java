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
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivityV12 extends Activity {
    private static final String USB_PERMISSION = "sa.techlight.printersetup.USB_PERMISSION_V12";
    private static final int ACTION_SETUP = 1;
    private static final int ACTION_TEST = 2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private UsbManager usb;
    private SharedPreferences prefs;
    private boolean ar;
    private int pendingAction;
    private TextView usbText, identityText, networkText, ipText, diagText, statusTitle, statusText;
    private Button setupBtn, testBtn, refreshBtn;
    private LinearLayout statusCard;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (USB_PERMISSION.equals(i.getAction())) {
                int action = pendingAction;
                pendingAction = 0;
                boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                refresh();
                if (!granted) {
                    result(false, tr("تم رفض إذن USB", "USB permission denied"), tr("اسمح للتطبيق بالوصول للطابعة.", "Allow USB access to the printer."));
                    return;
                }
                if (action == ACTION_SETUP) beginSetup(); else if (action == ACTION_TEST) beginTest();
            } else refresh();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pp9000eu_v12", MODE_PRIVATE);
        ar = !"en".equals(prefs.getString("lang", "ar"));
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        IntentFilter f = new IntentFilter();
        f.addAction(USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, f);
        refresh();
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

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("T", 32, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(bg(0xFF6D28D9, 15));
        brand.addView(mark, lp(dp(54), dp(54), 0));
        TextView name = text("TECHLIGHT\nضوء التقنية", 16, 0xFF2A2148, true);
        LinearLayout.LayoutParams np = lp(0, -2, 1); np.setMargins(dp(12),0,dp(12),0); brand.addView(name,np);
        Button lang = secondary(ar ? "English" : "العربية");
        lang.setOnClickListener(v -> { prefs.edit().putString("lang", ar ? "en" : "ar").apply(); recreate(); });
        brand.addView(lang, lp(dp(100), dp(44), 0));
        root.addView(brand, lp(-1, dp(62), 0));

        TextView title = text(tr("إعداد ProPOS PP9000EU", "ProPOS PP9000EU Setup"), 26, 0xFF211B34, true);
        LinearLayout.LayoutParams tp=lp(-1,-2,0); tp.topMargin=dp(20); root.addView(title,tp);
        TextView sub = text(tr("USB + LAN ثم ضغطة واحدة. النجاح لا يظهر إلا بعد العثور على الطابعة فعليًا على الشبكة.", "Connect USB + LAN, then one tap. Success is shown only after the printer is actually found on the network."),14,0xFF6B6678,false);
        LinearLayout.LayoutParams sp=lp(-1,-2,0); sp.topMargin=dp(6); root.addView(sub,sp);

        LinearLayout info = card();
        LinearLayout.LayoutParams ip=lp(-1,-2,0); ip.topMargin=dp(18); root.addView(info,ip);
        info.addView(label(tr("اتصال USB", "USB Connection"))); usbText=value("—"); info.addView(usbText);
        info.addView(divider());
        info.addView(label(tr("هوية الطابعة", "Printer Identity"))); identityText=text("—",12,0xFF514B5E,false); identityText.setTypeface(Typeface.MONOSPACE); info.addView(identityText);
        info.addView(divider());
        info.addView(label(tr("شبكة التابلت", "Tablet Network"))); networkText=value("—"); info.addView(networkText);
        info.addView(divider());
        info.addView(label(tr("IP الطابعة المؤكد", "Verified Printer IP"))); ipText=value(tr("لم يتم التحقق بعد", "Not verified yet")); ipText.setTextColor(0xFF5B21B6); ipText.setTextSize(20); info.addView(ipText);
        info.addView(divider());
        info.addView(label(tr("التشخيص", "Diagnostics"))); diagText=text(diag(false,false,false),13,0xFF514B5E,false); diagText.setTypeface(Typeface.MONOSPACE); info.addView(diagText);

        statusCard = card();
        LinearLayout.LayoutParams stp=lp(-1,-2,0); stp.topMargin=dp(14); root.addView(statusCard,stp);
        statusTitle=text(tr("جاهز", "Ready"),16,0xFF211B34,true); statusText=text("",14,0xFF655F70,false); statusCard.addView(statusTitle); statusCard.addView(statusText);

        setupBtn=primary(tr("تغيير IP والتحقق", "Change IP & Verify"));
        LinearLayout.LayoutParams bp=lp(-1,dp(60),0); bp.topMargin=dp(17); root.addView(setupBtn,bp); setupBtn.setOnClickListener(v->ensureUsb(ACTION_SETUP));
        LinearLayout actions=new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL); LinearLayout.LayoutParams ap=lp(-1,-2,0); ap.topMargin=dp(10); root.addView(actions,ap);
        testBtn=secondary(tr("اختبار USB", "USB Test")); refreshBtn=secondary(tr("تحديث", "Refresh"));
        actions.addView(testBtn,lp(0,dp(50),1)); TextView gap=new TextView(this); actions.addView(gap,lp(dp(10),1,0)); actions.addView(refreshBtn,lp(0,dp(50),1));
        testBtn.setOnClickListener(v->ensureUsb(ACTION_TEST)); refreshBtn.setOnClickListener(v->refresh());
        TextView foot=text("Techlight • ضوء التقنية • PP9000EU • v1.2",11,0xFF8A8490,false); foot.setGravity(Gravity.CENTER); LinearLayout.LayoutParams fp=lp(-1,-2,0); fp.topMargin=dp(18); root.addView(foot,fp);
        setContentView(sc);
    }

    private void refresh() {
        UsbDevice d=findPrinter();
        if(d==null){usbText.setText(tr("غير متصلة","Not connected"));identityText.setText("—");setDiag(false,false,false);} else {
            usbText.setText(usb.hasPermission(d)?tr("متصلة ✓","Connected ✓"):tr("متصلة — تحتاج إذن USB","Connected — permission required"));
            identityText.setText(identity(d,null));
        }
        NetInfo n=netInfo();
        networkText.setText(n==null?tr("لا توجد شبكة IPv4","No IPv4 network"):n.ip.getHostAddress()+" • GW "+n.gw.getHostAddress()+" /"+n.prefix);
        String saved=prefs.getString("verified_ip",null); ipText.setText(saved==null?tr("لم يتم التحقق بعد","Not verified yet"):saved);
        if(d==null) resultNeutral(tr("وصّل USB بالطابعة.","Connect the printer by USB.")); else if(n==null) result(false,tr("لا توجد شبكة","No network"),tr("وصّل التابلت بالواي فاي أو Ethernet.","Connect the tablet to Wi-Fi or Ethernet.")); else resultNeutral(tr("وصّل LAN بالطابعة ثم اضغط تغيير IP والتحقق.","Connect LAN to the printer, then tap Change IP & Verify."));
    }

    private void ensureUsb(int action){
        UsbDevice d=findPrinter(); if(d==null){result(false,tr("الطابعة غير متصلة","Printer not connected"),tr("وصّل USB ثم اضغط تحديث.","Connect USB then tap Refresh."));return;}
        if(!usb.hasPermission(d)){
            pendingAction=action; Intent x=new Intent(USB_PERMISSION).setPackage(getPackageName()); int flags=PendingIntent.FLAG_UPDATE_CURRENT; if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            usb.requestPermission(d,PendingIntent.getBroadcast(this,0,x,flags)); return;
        }
        if(action==ACTION_SETUP)beginSetup();else beginTest();
    }

    private void beginSetup(){
        busy(true); prefs.edit().remove("verified_ip").apply(); ipText.setText(tr("جاري التحقق…","Verifying…")); setDiag(false,false,false);
        resultNeutral(tr("جاري فحص USB ثم الشبكة…","Checking USB then network…"));
        worker.execute(()->{
            NetInfo n=netInfo(); if(n==null){fail(false,false,false,tr("تعذر قراءة شبكة التابلت.","Could not read tablet network."));return;}
            Inet4Address target=chooseTarget(n); if(target==null){fail(false,false,false,tr("تعذر اختيار IP متاح.","Could not select a free IP."));return;}
            Inet4Address mask; try{mask=intToIp(maskForPrefix(n.prefix));}catch(Exception e){fail(false,false,false,tr("تعذر حساب Subnet Mask.","Could not calculate subnet mask."));return;}
            UsbDevice d=findPrinter(); if(d==null||!usb.hasPermission(d)){fail(false,false,false,tr("فُقد اتصال USB.","USB connection was lost."));return;}

            boolean usbOk=false; String devId=null;
            try(UsbSession s=openUsb(d)){if(s!=null){usbOk=true;devId=s.deviceId();}}
            catch(Exception ignored){}
            final String id=identity(d,devId); runOnUiThread(()->identityText.setText(id)); setDiag(usbOk,false,false);
            if(!usbOk){fail(false,false,false,tr("تعذر فتح واجهة USB للطابعة.","Could not open the printer USB interface."));return;}

            String wanted=target.getHostAddress();
            Set<String> before=scan9100(n);
            post(tr("USB يعمل ✓","USB works ✓"),tr("جاري تعيين ","Assigning ")+wanted);

            boolean sent=false;
            try(UsbSession s=openUsb(d)){
                if(s!=null){sent=s.write(new byte[]{0x1B,0x40},1500);sleep(180);sent&=s.write(sprtIp(target),2200);sleep(1600);sent&=s.write(sprtIp(target),2200);}
            }catch(Exception ignored){sent=false;}
            setDiag(true,sent,false);
            String verified=null;
            if(sent){post(tr("أمر الشبكة أُرسل ✓","Network command sent ✓"),tr("جاري البحث عن الطابعة على LAN…","Searching for the printer on LAN…"));if(wait9100(wanted,14000))verified=wanted;if(verified==null)verified=newHost(before,scan9100(n));}

            if(verified==null){
                post(tr("جاري تجربة بروتوكول احتياطي…","Trying fallback protocol…"),tr("USB يعمل لكن IP لم يظهر بعد.","USB works, but the new IP has not appeared yet."));
                boolean sent2=false;
                try(UsbSession s=openUsb(d)){
                    if(s!=null){sent2=s.write(new byte[]{0x1B,0x40},1500);sleep(180);sent2&=s.write(dhcpOff(),1800);sleep(450);sent2&=s.write(addr((byte)0x51,mask),1800);sleep(300);sent2&=s.write(addr((byte)0x52,n.gw),1800);sleep(300);sent2&=s.write(addr((byte)0x50,target),2200);sleep(1500);sent2&=s.write(addr((byte)0x50,target),2200);}
                }catch(Exception ignored){sent2=false;}
                sent=sent||sent2; setDiag(true,sent,false);
                if(sent2&&wait9100(wanted,22000))verified=wanted;if(verified==null)verified=newHost(before,scan9100(n));
            }

            if(verified!=null){
                final String found=verified; prefs.edit().putString("verified_ip",found).apply(); setDiag(true,true,true);
                runOnUiThread(()->{busy(false);ipText.setText(found);result(true,tr("تم تغيير IP بنجاح ✓","IP changed successfully ✓"),tr("IP المؤكد: ","Verified IP: ")+found+tr(" — الطابعة تستجيب على المنفذ 9100."," — printer responds on port 9100."));});
            }else{
                final boolean cmd=sent; setDiag(true,cmd,false);
                runOnUiThread(()->{busy(false);ipText.setText(tr("لم يتغير / غير مؤكد","Not changed / not verified"));result(false,tr("الطابعة لم تظهر على LAN","Printer not found on LAN"),tr("USB: ✓\nأمر الشبكة: ","USB: ✓\nNetwork command: ")+(cmd?"✓":"✗")+"\nLAN: ✗\n"+tr("IP المطلوب: ","Requested IP: ")+wanted+tr("\nصوّر هوية الطابعة والتشخيص الظاهر بالأعلى.","\nCapture the printer identity and diagnostics shown above."));});
            }
        });
    }

    private void beginTest(){
        busy(true); worker.execute(()->{UsbDevice d=findPrinter();boolean ok=false;String id=null;if(d!=null&&usb.hasPermission(d)){try(UsbSession s=openUsb(d)){if(s!=null){id=s.deviceId();ok=s.test();}}catch(Exception ignored){}}
            final boolean done=ok;final String deviceId=id;runOnUiThread(()->{busy(false);if(d!=null)identityText.setText(identity(d,deviceId));result(done,done?tr("USB يعمل ✓","USB works ✓"):tr("فشل USB","USB failed"),done?tr("تم إرسال ورقة اختبار للطابعة.","A test page was sent to the printer."):tr("تعذر إرسال البيانات للطابعة.","Could not send data to the printer."));});});
    }

    private UsbDevice findPrinter(){
        UsbDevice best=null;int bestScore=-999;if(usb==null)return null;
        for(UsbDevice d:usb.getDeviceList().values()){
            int score=0;try{String x=((d.getManufacturerName()==null?"":d.getManufacturerName())+" "+(d.getProductName()==null?"":d.getProductName())).toLowerCase(Locale.US);if(x.contains("propos")||x.contains("printer")||x.contains("thermal")||x.contains("pos"))score+=40;}catch(Exception ignored){}
            for(int i=0;i<d.getInterfaceCount();i++){UsbInterface f=d.getInterface(i);if(f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER)score+=100;for(int e=0;e<f.getEndpointCount();e++){UsbEndpoint ep=f.getEndpoint(e);if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK&&ep.getDirection()==UsbConstants.USB_DIR_OUT)score+=10;}}
            if(score>bestScore){bestScore=score;best=d;}
        }return bestScore>=10?best:null;
    }

    private UsbSession openUsb(UsbDevice d){
        UsbInterface chosen=null;UsbEndpoint out=null;
        for(int i=0;i<d.getInterfaceCount();i++){UsbInterface f=d.getInterface(i);UsbEndpoint o=null;for(int e=0;e<f.getEndpointCount();e++){UsbEndpoint ep=f.getEndpoint(e);if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK&&ep.getDirection()==UsbConstants.USB_DIR_OUT)o=ep;}if(o!=null){chosen=f;out=o;if(f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER)break;}}
        if(chosen==null||out==null)return null;UsbDeviceConnection c=usb.openDevice(d);if(c==null)return null;if(!c.claimInterface(chosen,true)){c.close();return null;}return new UsbSession(c,chosen,out);
    }

    private static final class UsbSession implements Closeable{
        final UsbDeviceConnection c;final UsbInterface f;final UsbEndpoint out;UsbSession(UsbDeviceConnection c,UsbInterface f,UsbEndpoint out){this.c=c;this.f=f;this.out=out;}
        boolean write(byte[] b,int timeout){return c.bulkTransfer(out,b,b.length,timeout)==b.length;}
        String deviceId(){try{byte[] b=new byte[1024];int r=c.controlTransfer(0xA1,0,0,f.getId(),b,b.length,1500);if(r<=2)return null;int declared=((b[0]&255)<<8)|(b[1]&255);int end=declared>2&&declared<=r?declared:r;String s=new String(b,2,end-2,StandardCharsets.US_ASCII).replace('\0',' ').trim();return s.isEmpty()?null:s;}catch(Exception e){return null;}}
        boolean test(){byte[] t="TECHLIGHT PP9000EU USB OK\n\n".getBytes(StandardCharsets.US_ASCII);byte[] b=new byte[t.length+7];b[0]=0x1B;b[1]=0x40;System.arraycopy(t,0,b,2,t.length);int p=2+t.length;b[p]=0x0A;b[p+1]=0x0A;b[p+2]=0x1D;b[p+3]=0x56;b[p+4]=0x00;return write(b,2500);}
        @Override public void close(){try{c.releaseInterface(f);}catch(Exception ignored){}c.close();}
    }

    private NetInfo netInfo(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return null;Network nw=cm.getActiveNetwork();if(nw==null)return null;LinkProperties lp=cm.getLinkProperties(nw);if(lp==null)return null;
        Inet4Address ip=null,gw=null;int prefix=24;for(LinkAddress a:lp.getLinkAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()){ip=(Inet4Address)a.getAddress();prefix=a.getPrefixLength();break;}if(ip==null)return null;
        for(RouteInfo r:lp.getRoutes())if(r.isDefaultRoute()&&r.getGateway() instanceof Inet4Address){gw=(Inet4Address)r.getGateway();break;}if(gw==null)try{gw=intToIp((ipToInt(ip)&maskForPrefix(prefix))+1);}catch(Exception ignored){}return gw==null?null:new NetInfo(ip,gw,prefix);
    }
    private static final class NetInfo{final Inet4Address ip,gw;final int prefix;NetInfo(Inet4Address ip,Inet4Address gw,int prefix){this.ip=ip;this.gw=gw;this.prefix=prefix;}}

    private Inet4Address chooseTarget(NetInfo n){
        try{int me=ipToInt(n.ip);int base=me&0xFFFFFF00;int gw=ipToInt(n.gw);for(int last=220;last>=180;last--){int v=base|last;if(v==me||v==gw)continue;Inet4Address a=intToIp(v);if(!inUse(a))return a;}for(int last=179;last>=100;last--){int v=base|last;if(v==me||v==gw)continue;Inet4Address a=intToIp(v);if(!inUse(a))return a;}}catch(Exception ignored){}return null;
    }
    private boolean inUse(Inet4Address a){try{if(a.isReachable(120))return true;}catch(IOException ignored){}return port(a.getHostAddress(),9100,100)||port(a.getHostAddress(),80,100);}
    private static boolean port(String host,int p,int timeout){try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,p),timeout);return true;}catch(IOException e){return false;}}
    private static boolean wait9100(String host,int total){long end=System.currentTimeMillis()+total;while(System.currentTimeMillis()<end){if(port(host,9100,600))return true;sleep(750);}return false;}
    private Set<String> scan9100(NetInfo n){Set<String> found=java.util.Collections.synchronizedSet(new HashSet<>());int base=ipToInt(n.ip)&0xFFFFFF00;ExecutorService pool=Executors.newFixedThreadPool(36);for(int last=1;last<=254;last++){final String h;try{h=intToIp(base|last).getHostAddress();}catch(Exception e){continue;}pool.execute(()->{if(port(h,9100,140))found.add(h);});}pool.shutdown();try{pool.awaitTermination(6,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}pool.shutdownNow();return new HashSet<>(found);}
    private static String newHost(Set<String> before,Set<String> after){for(String h:after)if(before==null||!before.contains(h))return h;return null;}

    private static byte[] sprtIp(Inet4Address a){byte[] b=a.getAddress();return new byte[]{0x1B,0x11,b[0],b[1],b[2],b[3]};}
    private static byte[] dhcpOff(){return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,(byte)0xFA,0x00,0x5A};}
    private static byte[] addr(byte selector,Inet4Address a){byte[] b=a.getAddress();return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,selector,b[0],b[1],b[2],b[3]};}
    private static int ipToInt(Inet4Address a){byte[] b=a.getAddress();return((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);}
    private static Inet4Address intToIp(int v)throws Exception{return(Inet4Address)InetAddress.getByAddress(new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v});}
    private static int maskForPrefix(int p){if(p<=0)return 0;if(p>=32)return-1;return(int)(0xFFFFFFFFL<<(32-p));}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}

    private String identity(UsbDevice d,String ieee){StringBuilder b=new StringBuilder();try{if(d.getManufacturerName()!=null)b.append(d.getManufacturerName()).append(" • ");}catch(Exception ignored){}try{if(d.getProductName()!=null)b.append(d.getProductName()).append(" • ");}catch(Exception ignored){}b.append(String.format(Locale.US,"VID:PID %04X:%04X",d.getVendorId(),d.getProductId()));if(ieee!=null&&!ieee.trim().isEmpty()){String s=ieee.replace('\n',' ').replace('\r',' ').trim();if(s.length()>180)s=s.substring(0,180)+"…";b.append("\n").append(s);}return b.toString();}
    private String diag(boolean u,boolean c,boolean l){return tr("USB: ","USB: ")+(u?"✓":"—")+"\n"+tr("أمر الشبكة: ","Network command: ")+(c?"✓":"—")+"\nLAN: "+(l?"✓":"—");}
    private void setDiag(boolean u,boolean c,boolean l){runOnUiThread(()->diagText.setText(diag(u,c,l)));}
    private void fail(boolean u,boolean c,boolean l,String msg){setDiag(u,c,l);runOnUiThread(()->{busy(false);ipText.setText(tr("غير مؤكد","Not verified"));result(false,tr("تعذر إكمال العملية","Operation failed"),msg);});}
    private void post(String title,String detail){runOnUiThread(()->resultNeutral(title+"\n"+detail));}
    private void busy(boolean on){runOnUiThread(()->{setupBtn.setEnabled(!on);testBtn.setEnabled(!on);refreshBtn.setEnabled(!on);});}
    private void resultNeutral(String detail){statusCard.setBackground(bg(0xFFF0EFF4,18));statusTitle.setText(tr("الحالة","Status"));statusTitle.setTextColor(0xFF211B34);statusText.setText(detail);}
    private void result(boolean ok,String title,String detail){statusCard.setBackground(bg(ok?0xFFE8F7EF:0xFFFCEBED,18));statusTitle.setText(title);statusTitle.setTextColor(ok?0xFF168A55:0xFFC73B45);statusText.setText(detail);}

    private String tr(String ar,String en){return this.ar?ar:en;}
    private LinearLayout card(){LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);x.setPadding(dp(16),dp(14),dp(16),dp(14));x.setBackground(bg(Color.WHITE,18));return x;}
    private TextView label(String s){return text(s,12,0xFF8A8490,true);}
    private TextView value(String s){TextView t=text(s,16,0xFF292334,true);LinearLayout.LayoutParams p=lp(-1,-2,0);p.topMargin=dp(4);t.setLayoutParams(p);return t;}
    private View divider(){View v=new View(this);v.setBackgroundColor(0xFFE8E5EC);LinearLayout.LayoutParams p=lp(-1,1,0);p.topMargin=dp(12);p.bottomMargin=dp(12);v.setLayoutParams(p);return v;}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);t.setGravity(ar?Gravity.RIGHT:Gravity.LEFT);return t;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(17);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(bg(0xFF6D28D9,16));return b;}
    private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFF4D4367);b.setTextSize(14);b.setBackground(bg(0xFFEDEAF4,14));return b;}
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int w,int h,float weight){return new LinearLayout.LayoutParams(w,h,weight);}
}
