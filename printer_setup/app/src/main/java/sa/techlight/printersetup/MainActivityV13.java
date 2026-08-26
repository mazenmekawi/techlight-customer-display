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
import android.widget.Space;
import android.widget.TextView;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivityV13 extends Activity {
    private static final String USB_PERMISSION = "sa.techlight.printersetup.USB_PERMISSION_V13";
    private static final String PREFS = "pp9000eu_v13";
    private static final int ACTION_SETUP = 1;
    private static final int ACTION_TEST = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private UsbManager usb;
    private boolean ar = true;
    private int pendingAction = 0;

    private TextView usbText, identityText, networkText, ipText, stepText, statusTitle, statusText;
    private Button mainBtn, testBtn, refreshBtn, resetBtn;
    private LinearLayout statusCard;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (USB_PERMISSION.equals(a)) {
                boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                int act = pendingAction;
                pendingAction = 0;
                refreshUi();
                if (!granted) {
                    showError(tr("تم رفض إذن USB", "USB permission denied"), tr("اسمح للتطبيق بالوصول إلى الطابعة ثم حاول مرة أخرى.", "Allow USB access to the printer, then try again."));
                    return;
                }
                if (act == ACTION_SETUP) startMainAction();
                else if (act == ACTION_TEST) startUsbTest();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                refreshUi();
                if (hasPendingIp()) {
                    prefs.edit().putBoolean("reboot_seen", true).apply();
                    setStep(tr("1) تم حفظ IP ✓\n2) تم إيقاف الطابعة ✓\n3) شغّل الطابعة الآن…", "1) IP saved ✓\n2) Printer powered off ✓\n3) Turn the printer on now…"));
                    showNeutral(tr("بانتظار تشغيل الطابعة", "Waiting for printer power-on"), tr("اترك كابل LAN وUSB موصولين وشغّل الطابعة.", "Keep LAN and USB connected and turn the printer on."));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                refreshUi();
                if (hasPendingIp()) {
                    prefs.edit().putBoolean("reboot_seen", true).apply();
                    setStep(tr("1) تم حفظ IP ✓\n2) إعادة التشغيل ✓\n3) جاري التحقق من LAN…", "1) IP saved ✓\n2) Restart detected ✓\n3) Verifying LAN…"));
                    verifyPendingSoon();
                }
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        if (hasPendingIp()) verifyPendingSoon();
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasPendingIp()) verifyPendingSoon();
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
        LinearLayout.LayoutParams tp = lp(-1,-2,0); tp.topMargin = dp(20); root.addView(title,tp);
        TextView sub = text(tr("مخصص لوحدة Xprinter/ST ذات VID:PID 0483:5743. بعد حفظ IP تحتاج الطابعة إعادة تشغيل لتفعيل LAN.", "For Xprinter/ST hardware VID:PID 0483:5743. The printer must restart after saving IP so LAN can activate."), 14, 0xFF6B6678, false);
        LinearLayout.LayoutParams sp = lp(-1,-2,0); sp.topMargin = dp(6); root.addView(sub,sp);

        LinearLayout info = card();
        LinearLayout.LayoutParams ip = lp(-1,-2,0); ip.topMargin = dp(18); root.addView(info,ip);
        info.addView(label(tr("اتصال USB", "USB Connection"))); usbText=value("—"); info.addView(usbText);
        info.addView(divider());
        info.addView(label(tr("هوية الطابعة", "Printer Identity"))); identityText=text("—",12,0xFF514B5E,false); identityText.setTypeface(Typeface.MONOSPACE); info.addView(identityText);
        info.addView(divider());
        info.addView(label(tr("شبكة التابلت", "Tablet Network"))); networkText=value("—"); info.addView(networkText);
        info.addView(divider());
        info.addView(label(tr("IP الطابعة", "Printer IP"))); ipText=value(tr("لم يتم الإعداد بعد", "Not configured yet")); ipText.setTextSize(20); ipText.setTextColor(0xFF5B21B6); info.addView(ipText);
        info.addView(divider());
        info.addView(label(tr("الخطوات", "Progress"))); stepText=text("—",14,0xFF514B5E,true); info.addView(stepText);

        statusCard = card();
        LinearLayout.LayoutParams stp=lp(-1,-2,0); stp.topMargin=dp(14); root.addView(statusCard,stp);
        statusTitle=text(tr("جاهز", "Ready"),16,0xFF211B34,true); statusText=text("",14,0xFF655F70,false); statusCard.addView(statusTitle); statusCard.addView(statusText);

        mainBtn = primary(tr("تعيين IP للطابعة", "Set Printer IP"));
        LinearLayout.LayoutParams bp=lp(-1,dp(60),0); bp.topMargin=dp(17); root.addView(mainBtn,bp);
        mainBtn.setOnClickListener(v -> ensureUsb(ACTION_SETUP));

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap=lp(-1,-2,0); ap.topMargin=dp(10); root.addView(actions,ap);
        testBtn=secondary(tr("اختبار USB", "USB Test")); refreshBtn=secondary(tr("تحقق الآن", "Verify Now"));
        actions.addView(testBtn,lp(0,dp(50),1)); Space gap=new Space(this); actions.addView(gap,lp(dp(10),1,0)); actions.addView(refreshBtn,lp(0,dp(50),1));
        testBtn.setOnClickListener(v -> ensureUsb(ACTION_TEST));
        refreshBtn.setOnClickListener(v -> { refreshUi(); if (hasPendingIp()) verifyPending(); });

        resetBtn = secondary(tr("بدء إعداد جديد", "Start New Setup"));
        LinearLayout.LayoutParams rp=lp(-1,dp(48),0); rp.topMargin=dp(10); root.addView(resetBtn,rp);
        resetBtn.setOnClickListener(v -> {
            prefs.edit().remove("pending_ip").remove("verified_ip").remove("reboot_seen").apply();
            refreshUi();
            showNeutral(tr("تم مسح المحاولة السابقة", "Previous attempt cleared"), tr("يمكنك الآن تعيين IP جديد.", "You can now assign a new IP."));
        });

        TextView foot=text("Techlight • ضوء التقنية • PP9000EU • v1.3",11,0xFF8A8490,false); foot.setGravity(Gravity.CENTER); LinearLayout.LayoutParams fp=lp(-1,-2,0); fp.topMargin=dp(18); root.addView(foot,fp);
        setContentView(sc);
    }

    private void refreshUi() {
        UsbDevice d = findPrinter();
        if (d == null) {
            usbText.setText(tr("غير متصلة", "Not connected"));
            identityText.setText("—");
        } else {
            usbText.setText(usb.hasPermission(d) ? tr("متصلة ✓", "Connected ✓") : tr("متصلة — تحتاج إذن USB", "Connected — permission required"));
            identityText.setText(identity(d));
        }
        NetInfo n = netInfo();
        networkText.setText(n==null?tr("لا توجد شبكة IPv4", "No IPv4 network"):n.ip.getHostAddress()+" • GW "+n.gw.getHostAddress()+" /"+n.prefix);
        String verified = prefs.getString("verified_ip", null);
        String pending = prefs.getString("pending_ip", null);
        if (verified != null) {
            ipText.setText(verified + " ✓");
            setStep(tr("1) حفظ الإعدادات ✓\n2) إعادة التشغيل ✓\n3) LAN مؤكد ✓", "1) Settings saved ✓\n2) Restart ✓\n3) LAN verified ✓"));
            mainBtn.setText(tr("تم الإعداد بنجاح", "Setup Complete"));
        } else if (pending != null) {
            ipText.setText(pending + tr(" — بانتظار إعادة التشغيل", " — waiting for restart"));
            setStep(tr("1) حفظ الإعدادات ✓\n2) أطفئ الطابعة 5 ثوانٍ ثم شغّلها\n3) سيبدأ التحقق تلقائيًا", "1) Settings saved ✓\n2) Power printer off for 5 seconds, then on\n3) Verification starts automatically"));
            mainBtn.setText(tr("تحقق بعد إعادة التشغيل", "Verify After Restart"));
        } else {
            ipText.setText(tr("لم يتم الإعداد بعد", "Not configured yet"));
            setStep(tr("1) وصّل USB + LAN\n2) اضغط تعيين IP\n3) أعد تشغيل الطابعة عند الطلب", "1) Connect USB + LAN\n2) Tap Set IP\n3) Restart printer when prompted"));
            mainBtn.setText(tr("تعيين IP للطابعة", "Set Printer IP"));
        }
    }

    private void ensureUsb(int action) {
        if (action == ACTION_SETUP && hasPendingIp()) { verifyPending(); return; }
        UsbDevice d = findPrinter();
        if (d == null) { showError(tr("الطابعة غير متصلة", "Printer not connected"), tr("وصّل USB بالطابعة ثم حاول مرة أخرى.", "Connect the printer by USB and try again.")); return; }
        if (!usb.hasPermission(d)) {
            pendingAction = action;
            Intent x = new Intent(USB_PERMISSION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT; if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            usb.requestPermission(d, PendingIntent.getBroadcast(this,0,x,flags));
            return;
        }
        if (action == ACTION_SETUP) startMainAction(); else startUsbTest();
    }

    private void startMainAction() {
        if (hasPendingIp()) { verifyPending(); return; }
        busy(true);
        showNeutral(tr("جاري تجهيز الإعدادات…", "Preparing settings…"), tr("سيتم اختيار IP متاح من نفس شبكة التابلت.", "A free IP will be selected from the tablet subnet."));
        worker.execute(() -> {
            NetInfo n = netInfo();
            if (n == null) { fail(tr("تعذر قراءة شبكة التابلت.", "Could not read tablet network.")); return; }
            Inet4Address target = chooseTarget(n);
            if (target == null) { fail(tr("تعذر اختيار IP متاح.", "Could not select a free IP.")); return; }
            Inet4Address mask;
            try { mask = intToIp(maskForPrefix(n.prefix)); } catch(Exception e) { fail(tr("تعذر حساب Subnet Mask.", "Could not calculate subnet mask.")); return; }

            UsbDevice d = findPrinter();
            if (d == null || !usb.hasPermission(d)) { fail(tr("فُقد اتصال USB.", "USB connection was lost.")); return; }
            boolean ok = false;
            try (UsbSession s = openUsb(d)) {
                if (s != null) {
                    ok = s.write(new byte[]{0x1B,0x40}, 1500);
                    sleep(180);
                    ok &= s.write(dhcpOff(), 1800);
                    sleep(300);
                    ok &= s.write(addr((byte)0x51, mask), 1800);
                    sleep(300);
                    ok &= s.write(addr((byte)0x52, n.gw), 1800);
                    sleep(300);
                    ok &= s.write(addr((byte)0x50, target), 2200);
                }
            } catch(Exception ignored) { ok = false; }
            if (!ok) { fail(tr("تعذر إرسال أوامر الشبكة عبر USB.", "Could not send network commands over USB.")); return; }

            String ip = target.getHostAddress();
            prefs.edit().putString("pending_ip", ip).putBoolean("reboot_seen", false).apply();
            runOnUiThread(() -> {
                busy(false);
                refreshUi();
                showWarning(tr("تم حفظ IP — أعد تشغيل الطابعة", "IP saved — restart printer"), tr("الـIP المطلوب: ", "Requested IP: ")+ip+tr("\nأطفئ الطابعة 5 ثوانٍ ثم شغّلها. التطبيق سيتحقق تلقائيًا بعد رجوعها.", "\nPower the printer off for 5 seconds, then on. The app will verify automatically when it returns."));
            });
        });
    }

    private void verifyPendingSoon() {
        worker.execute(() -> { sleep(2500); verifyPendingInternal(); });
    }

    private void verifyPending() {
        busy(true);
        showNeutral(tr("جاري التحقق من LAN…", "Verifying LAN…"), tr("أبحث عن الطابعة على IP المحفوظ.", "Checking the saved printer IP."));
        worker.execute(this::verifyPendingInternal);
    }

    private void verifyPendingInternal() {
        String ip = prefs.getString("pending_ip", null);
        if (ip == null) { runOnUiThread(() -> busy(false)); return; }
        boolean ok = waitPort(ip, 9100, 25000);
        if (ok) {
            prefs.edit().putString("verified_ip", ip).remove("pending_ip").putBoolean("reboot_seen", true).apply();
            runOnUiThread(() -> {
                busy(false);
                refreshUi();
                showSuccess(tr("تم تغيير IP بنجاح ✓", "IP changed successfully ✓"), tr("IP المؤكد: ", "Verified IP: ")+ip+tr("\nالطابعة تستجيب فعليًا على LAN عبر المنفذ 9100.", "\nThe printer is responding on LAN port 9100."));
            });
        } else {
            runOnUiThread(() -> {
                busy(false);
                refreshUi();
                showWarning(tr("بانتظار تفعيل LAN", "Waiting for LAN activation"), tr("لم تظهر الطابعة بعد على ", "Printer is not yet reachable at ")+ip+tr(".\nتأكد أنك أطفأت وشغلت الطابعة بعد حفظ الإعدادات وأن كابل LAN موصول.", ".\nMake sure the printer was power-cycled after saving settings and LAN is connected."));
            });
        }
    }

    private void startUsbTest() {
        busy(true);
        worker.execute(() -> {
            UsbDevice d = findPrinter(); boolean ok=false;
            if (d != null && usb.hasPermission(d)) {
                try(UsbSession s=openUsb(d)) { if(s!=null) ok=s.testPrint(); } catch(Exception ignored) {}
            }
            boolean result=ok;
            runOnUiThread(() -> { busy(false); if(result) showSuccess(tr("USB يعمل ✓", "USB works ✓"), tr("تم إرسال اختبار طباعة إلى PP9000EU.", "A test print was sent to the PP9000EU.")); else showError(tr("فشل اختبار USB", "USB test failed"), tr("تحقق من كابل USB وإذن التطبيق.", "Check the USB cable and app permission.")); });
        });
    }

    private UsbDevice findPrinter() {
        if (usb == null) return null;
        UsbDevice best=null; int bestScore=-999;
        for(UsbDevice d:usb.getDeviceList().values()) {
            int score=0;
            if(d.getVendorId()==0x0483 && d.getProductId()==0x5743) score+=500;
            try {
                String s=((d.getProductName()==null?"":d.getProductName())+" "+(d.getManufacturerName()==null?"":d.getManufacturerName())).toLowerCase(Locale.US);
                if(s.contains("printer")||s.contains("xprinter")||s.contains("propos")) score+=50;
            } catch(Exception ignored) {}
            for(int i=0;i<d.getInterfaceCount();i++) {
                UsbInterface f=d.getInterface(i);
                if(f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER) score+=100;
                for(int e=0;e<f.getEndpointCount();e++) {
                    UsbEndpoint ep=f.getEndpoint(e);
                    if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection()==UsbConstants.USB_DIR_OUT) score+=10;
                }
            }
            if(score>bestScore){bestScore=score;best=d;}
        }
        return bestScore>=10?best:null;
    }

    private String identity(UsbDevice d) {
        String p="printer",m="USB Printer Port";
        try { if(d.getManufacturerName()!=null&&!d.getManufacturerName().trim().isEmpty()) p=d.getManufacturerName(); } catch(Exception ignored) {}
        try { if(d.getProductName()!=null&&!d.getProductName().trim().isEmpty()) m=d.getProductName(); } catch(Exception ignored) {}
        return p+" • "+m+" • VID:PID "+String.format(Locale.US,"%04X:%04X",d.getVendorId(),d.getProductId());
    }

    private UsbSession openUsb(UsbDevice d) {
        UsbInterface chosen=null; UsbEndpoint out=null;
        for(int i=0;i<d.getInterfaceCount();i++) {
            UsbInterface f=d.getInterface(i); UsbEndpoint candidate=null;
            for(int e=0;e<f.getEndpointCount();e++) {
                UsbEndpoint ep=f.getEndpoint(e);
                if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection()==UsbConstants.USB_DIR_OUT) candidate=ep;
            }
            if(candidate!=null){chosen=f;out=candidate;if(f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER)break;}
        }
        if(chosen==null||out==null)return null;
        UsbDeviceConnection c=usb.openDevice(d); if(c==null)return null;
        if(!c.claimInterface(chosen,true)){c.close();return null;}
        return new UsbSession(c,chosen,out);
    }

    private static final class UsbSession implements Closeable {
        final UsbDeviceConnection c; final UsbInterface f; final UsbEndpoint out;
        UsbSession(UsbDeviceConnection c,UsbInterface f,UsbEndpoint out){this.c=c;this.f=f;this.out=out;}
        boolean write(byte[] data,int timeout){return c.bulkTransfer(out,data,data.length,timeout)==data.length;}
        boolean testPrint(){
            byte[] t="TECHLIGHT PP9000EU USB TEST\nUSB OK\n\n".getBytes(StandardCharsets.US_ASCII);
            byte[] d=new byte[t.length+7];d[0]=0x1B;d[1]=0x40;System.arraycopy(t,0,d,2,t.length);int o=2+t.length;d[o]=0x0A;d[o+1]=0x0A;d[o+2]=0x1D;d[o+3]=0x56;d[o+4]=0x00;return write(d,2500);
        }
        @Override public void close(){try{c.releaseInterface(f);}catch(Exception ignored){}c.close();}
    }

    private NetInfo netInfo() {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE); if(cm==null)return null;
        Network n=cm.getActiveNetwork(); if(n==null)return null; LinkProperties lp=cm.getLinkProperties(n); if(lp==null)return null;
        Inet4Address ip=null,gw=null;int prefix=24;
        for(LinkAddress a:lp.getLinkAddresses()) if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()){ip=(Inet4Address)a.getAddress();prefix=a.getPrefixLength();break;}
        if(ip==null)return null;
        for(RouteInfo r:lp.getRoutes()) if(r.isDefaultRoute()&&r.getGateway() instanceof Inet4Address){gw=(Inet4Address)r.getGateway();break;}
        if(gw==null) try{gw=intToIp((ipToInt(ip)&maskForPrefix(prefix))+1);}catch(Exception ignored){}
        return gw==null?null:new NetInfo(ip,gw,prefix);
    }

    private static final class NetInfo { final Inet4Address ip,gw; final int prefix; NetInfo(Inet4Address i,Inet4Address g,int p){ip=i;gw=g;prefix=p;} }

    private Inet4Address chooseTarget(NetInfo n) {
        try {
            int ip=ipToInt(n.ip), mask=maskForPrefix(n.prefix), network=ip&mask, broadcast=network|~mask, gw=ipToInt(n.gw);
            if(n.prefix==24) {
                int base=network; for(int host=220;host>=180;host--){int c=base+host;if(c==ip||c==gw)continue;Inet4Address a=intToIp(c);if(!inUse(a))return a;}
            }
            for(int c=broadcast-20;c>network+2;c--){if(c==ip||c==gw)continue;Inet4Address a=intToIp(c);if(!inUse(a))return a;}
        }catch(Exception ignored){}
        return null;
    }

    private boolean inUse(Inet4Address a) {
        if(portOpen(a.getHostAddress(),9100,120))return true;
        if(portOpen(a.getHostAddress(),80,100))return true;
        try{return a.isReachable(120);}catch(IOException e){return false;}
    }

    private static boolean portOpen(String host,int port,int timeout){try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),timeout);return true;}catch(IOException e){return false;}}
    private static boolean waitPort(String host,int port,int total){long end=System.currentTimeMillis()+total;while(System.currentTimeMillis()<end){if(portOpen(host,port,500))return true;sleep(800);}return false;}

    private static byte[] dhcpOff(){return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,(byte)0xFA,0x00,0x5A};}
    private static byte[] addr(byte selector,Inet4Address a){byte[]b=a.getAddress();return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,selector,b[0],b[1],b[2],b[3]};}
    private static int ipToInt(Inet4Address a){byte[]b=a.getAddress();return((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);}
    private static Inet4Address intToIp(int v)throws Exception{return(Inet4Address)InetAddress.getByAddress(new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v});}
    private static int maskForPrefix(int p){if(p<=0)return 0;if(p>=32)return-1;return(int)(0xFFFFFFFFL<<(32-p));}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private boolean hasPendingIp(){return prefs.getString("pending_ip",null)!=null;}

    private void busy(boolean b){runOnUiThread(()->{mainBtn.setEnabled(!b);testBtn.setEnabled(!b);refreshBtn.setEnabled(!b);resetBtn.setEnabled(!b);});}
    private void fail(String s){runOnUiThread(()->{busy(false);showError(tr("تعذر إكمال الإعداد","Setup failed"),s);});}
    private void setStep(String s){runOnUiThread(()->stepText.setText(s));}
    private void showNeutral(String t,String d){showStatus(0,t,d);}
    private void showWarning(String t,String d){showStatus(3,t,d);}
    private void showSuccess(String t,String d){showStatus(1,t,d);}
    private void showError(String t,String d){showStatus(2,t,d);}
    private void showStatus(int kind,String t,String d){runOnUiThread(()->{statusTitle.setText(t);statusText.setText(d);int bgc=kind==1?0xFFE8F7EF:kind==2?0xFFFCEBED:kind==3?0xFFFFF5DD:0xFFF0EFF4;int fg=kind==1?0xFF168A55:kind==2?0xFFC73B45:kind==3?0xFF9A6700:0xFF211B34;statusCard.setBackground(bg(bgc,18));statusTitle.setTextColor(fg);});}

    private String tr(String a,String e){return ar?a:e;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int w,int h,float weight){return new LinearLayout.LayoutParams(w,h,weight);}
    private GradientDrawable bg(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.12f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private TextView label(String s){TextView v=text(s,13,0xFF777181,false);LinearLayout.LayoutParams p=lp(-1,-2,0);p.bottomMargin=dp(5);v.setLayoutParams(p);return v;}
    private TextView value(String s){return text(s,17,0xFF211B34,true);}
    private View divider(){View v=new View(this);v.setBackgroundColor(0xFFE7E4EE);LinearLayout.LayoutParams p=lp(-1,dp(1),0);p.topMargin=dp(15);p.bottomMargin=dp(15);v.setLayoutParams(p);return v;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(16),dp(18),dp(16));c.setBackground(bg(Color.WHITE,20));return c;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setTextSize(18);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF7C3AED,0xFF5B21B6});g.setCornerRadius(dp(16));b.setBackground(g);return b;}
    private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(0xFF4C1D95);b.setAllCaps(false);b.setBackground(bg(0xFFF2EEFB,14));return b;}
}
