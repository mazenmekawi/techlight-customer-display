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

public class MainActivity extends Activity {
    private static final String USB_PERMISSION = "sa.techlight.printersetup.USB_PERMISSION";
    private static final String PREFS = "pp9000eu_setup";
    private static final int ACTION_SETUP = 1;
    private static final int ACTION_TEST = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private UsbManager usbManager;
    private boolean arabic = true;
    private int pendingAction = 0;

    private TextView usbValue, netValue, newIpValue, statusTitle, statusDetail;
    private LinearLayout statusCard;
    private Button setupButton, testButton, refreshButton;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                int requested = pendingAction;
                pendingAction = 0;
                refreshSnapshot();
                if (!granted) {
                    showStatus(2, tr("تم رفض إذن USB", "USB permission denied"), tr("اسمح للتطبيق بالوصول إلى الطابعة.", "Allow the app to access the printer."));
                    return;
                }
                if (requested == ACTION_SETUP) startSetup();
                if (requested == ACTION_TEST) startTestPrint();
            } else {
                refreshSnapshot();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        arabic = !"en".equals(prefs.getString("lang", "ar"));
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        registerUsb();
        refreshSnapshot();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF7F7FB);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(top, lp(-1, dp(74), 0));

        TextView mark = new TextView(this);
        mark.setText("T");
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(Color.WHITE);
        mark.setTextSize(36);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setBackground(roundRect(0xFF6D28D9, 16));
        top.addView(mark, lp(dp(58), dp(58), 0));

        TextView brand = text("TECHLIGHT\nضوء التقنية", 17, 0xFF2A2148, true);
        LinearLayout.LayoutParams brandLp = lp(0, -2, 1);
        brandLp.setMargins(dp(12), 0, dp(12), 0);
        top.addView(brand, brandLp);

        Button lang = secondaryButton(arabic ? "English" : "العربية");
        lang.setOnClickListener(v -> {
            prefs.edit().putString("lang", arabic ? "en" : "ar").apply();
            recreate();
        });
        top.addView(lang, lp(dp(100), dp(46), 0));

        TextView title = text(tr("إعداد ProPOS PP9000EU", "ProPOS PP9000EU Setup"), 27, 0xFF211B34, true);
        LinearLayout.LayoutParams tlp = lp(-1, -2, 0); tlp.topMargin = dp(20); root.addView(title, tlp);
        TextView sub = text(tr("وصّل USB وكابل الشبكة، ثم اضغط زر واحد لتغيير IP والتحقق منه.", "Connect USB and LAN, then use one button to change and verify the IP."), 15, 0xFF6B6678, false);
        LinearLayout.LayoutParams slp = lp(-1, -2, 0); slp.topMargin = dp(6); root.addView(sub, slp);

        LinearLayout card = card();
        LinearLayout.LayoutParams clp = lp(-1, -2, 0); clp.topMargin = dp(20); root.addView(card, clp);
        card.addView(label(tr("اتصال USB", "USB Connection")));
        usbValue = value("—"); card.addView(usbValue);
        card.addView(divider());
        card.addView(label(tr("شبكة التابلت", "Tablet Network")));
        netValue = value("—"); card.addView(netValue);
        card.addView(divider());
        card.addView(label(tr("IP الطابعة المؤكد", "Verified Printer IP")));
        newIpValue = value(tr("لم يتم التحقق بعد", "Not verified yet"));
        newIpValue.setTextColor(0xFF5B21B6); newIpValue.setTextSize(21); card.addView(newIpValue);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams stlp = lp(-1, -2, 0); stlp.topMargin = dp(14); root.addView(statusCard, stlp);
        statusTitle = text(tr("جاهز", "Ready"), 16, 0xFF211B34, true);
        statusDetail = text("", 14, 0xFF6B6678, false);
        statusCard.addView(statusTitle); statusCard.addView(statusDetail);

        setupButton = primaryButton(tr("تغيير IP والتحقق", "Change IP & Verify"));
        LinearLayout.LayoutParams blp = lp(-1, dp(62), 0); blp.topMargin = dp(18); root.addView(setupButton, blp);
        setupButton.setOnClickListener(v -> ensureUsb(ACTION_SETUP));

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = lp(-1, -2, 0); alp.topMargin = dp(10); root.addView(actions, alp);
        testButton = secondaryButton(tr("اختبار USB", "USB Test"));
        refreshButton = secondaryButton(tr("تحديث", "Refresh"));
        actions.addView(testButton, lp(0, dp(50), 1));
        Space gap = new Space(this); actions.addView(gap, lp(dp(10), 1, 0));
        actions.addView(refreshButton, lp(0, dp(50), 1));
        testButton.setOnClickListener(v -> ensureUsb(ACTION_TEST));
        refreshButton.setOnClickListener(v -> refreshSnapshot());

        TextView footer = text("Techlight • ضوء التقنية • PP9000EU", 12, 0xFF85808F, false);
        footer.setGravity(Gravity.CENTER); LinearLayout.LayoutParams flp = lp(-1, -2, 0); flp.topMargin = dp(18); root.addView(footer, flp);
        setContentView(scroll);
    }

    private void registerUsb() {
        IntentFilter f = new IntentFilter();
        f.addAction(USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);
    }

    private void refreshSnapshot() {
        UsbDevice d = findPrinter();
        if (d == null) usbValue.setText(tr("غير متصلة", "Not connected"));
        else if (!usbManager.hasPermission(d)) usbValue.setText(tr("متصلة — تحتاج إذن USB", "Connected — permission required"));
        else usbValue.setText(tr("متصلة: ", "Connected: ") + deviceName(d));

        NetInfo n = activeNetwork();
        if (n == null) netValue.setText(tr("لا توجد شبكة IPv4", "No IPv4 network"));
        else netValue.setText(n.ip.getHostAddress() + "  •  GW " + n.gateway.getHostAddress());

        String verified = prefs.getString("verified_ip", null);
        newIpValue.setText(verified == null ? tr("لم يتم التحقق بعد", "Not verified yet") : verified);

        if (d == null) showStatus(0, tr("جاهز", "Ready"), tr("وصّل الطابعة بالتابلت عبر USB.", "Connect the printer to the tablet by USB."));
        else if (n == null) showStatus(2, tr("لا توجد شبكة", "No network"), tr("وصّل التابلت بالواي فاي أو Ethernet.", "Connect the tablet to Wi-Fi or Ethernet."));
        else showStatus(0, tr("جاهز للتغيير", "Ready to change"), tr("تأكد أن كابل LAN موصول بالطابعة ثم اضغط تغيير IP والتحقق.", "Make sure LAN is connected, then tap Change IP & Verify."));
    }

    private void ensureUsb(int action) {
        UsbDevice d = findPrinter();
        if (d == null) { showStatus(2, tr("الطابعة غير متصلة", "Printer not connected"), tr("وصّل USB ثم اضغط تحديث.", "Connect USB then tap Refresh.")); return; }
        if (!usbManager.hasPermission(d)) {
            pendingAction = action;
            Intent i = new Intent(USB_PERMISSION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            usbManager.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
            return;
        }
        if (action == ACTION_SETUP) startSetup(); else startTestPrint();
    }

    private void startSetup() {
        setBusy(true);
        showStatus(0, tr("جاري تجهيز الشبكة…", "Preparing network…"), tr("جاري اختيار IP غير مستخدم.", "Selecting an unused IP."));
        executor.execute(() -> {
            NetInfo n = activeNetwork();
            if (n == null) { fail(tr("تعذر قراءة شبكة التابلت.", "Could not read tablet network.")); return; }
            Inet4Address target = chooseFree(n);
            if (target == null) { fail(tr("لم أجد IP متاحًا.", "No free IP was found.")); return; }
            Inet4Address mask;
            try { mask = intToIp(maskForPrefix(n.prefix)); }
            catch (Exception e) { fail(tr("تعذر حساب Subnet Mask.", "Could not calculate subnet mask.")); return; }

            String ip = target.getHostAddress();
            postStatus(0, tr("جاري إرسال الإعدادات…", "Sending settings…"), tr("سيتم تعيين IP: ", "Target IP: ") + ip);

            UsbDevice d = findPrinter();
            if (d == null || !usbManager.hasPermission(d)) { fail(tr("فُقد اتصال USB.", "USB connection was lost.")); return; }

            boolean sent = false;
            try (UsbSession s = openUsb(d)) {
                if (s != null) {
                    sent = s.write(new byte[]{0x1B,0x40}, 1500);
                    sleep(180);
                    sent &= s.write(dhcpOffCommand(), 1800);
                    sleep(450);
                    sent &= s.write(addressCommand((byte)0x51, mask), 1800);
                    sleep(350);
                    sent &= s.write(addressCommand((byte)0x52, n.gateway), 1800);
                    sleep(350);
                    sent &= s.write(addressCommand((byte)0x50, target), 2200);
                    sleep(1400);
                    sent &= s.write(addressCommand((byte)0x50, target), 2200);
                }
            } catch (Exception ignored) { sent = false; }

            if (!sent) { fail(tr("تعذر إرسال أمر تغيير IP للطابعة.", "Could not send the IP command to the printer.")); return; }

            postStatus(0, tr("جاري التحقق من التغيير…", "Verifying the change…"), tr("لن يظهر نجاح إلا إذا استجابت الطابعة على ", "Success is shown only if the printer responds at ") + ip + ":9100");
            boolean verified = waitForPrinter(ip, 28000);

            if (!verified) {
                try (UsbSession s = openUsb(d)) {
                    if (s != null) {
                        s.write(addressCommand((byte)0x50, target), 2200);
                        sleep(1200);
                    }
                } catch (Exception ignored) {}
                verified = waitForPrinter(ip, 16000);
            }

            if (verified) {
                prefs.edit().putString("verified_ip", ip).apply();
                runOnUiThread(() -> {
                    setBusy(false);
                    newIpValue.setText(ip);
                    showStatus(1, tr("تم تغيير IP بنجاح", "IP changed successfully"), tr("IP الجديد: ", "New IP: ") + ip + tr(" — تم التحقق من الطابعة على الشبكة.", " — printer verified on the network."));
                });
            } else {
                runOnUiThread(() -> {
                    setBusy(false);
                    showStatus(2, tr("لم يتم تأكيد تغيير IP", "IP change not confirmed"), tr("الطابعة لم تستجب على IP الجديد ", "The printer did not respond at the new IP ") + ip + tr(". لم يتم حفظه كتغيير ناجح.", ". It was not saved as a successful change."));
                });
            }
        });
    }

    private void startTestPrint() {
        setBusy(true);
        executor.execute(() -> {
            UsbDevice d = findPrinter(); boolean ok = false;
            if (d != null && usbManager.hasPermission(d)) {
                try (UsbSession s = openUsb(d)) { if (s != null) ok = s.testPrint(); } catch (Exception ignored) {}
            }
            boolean result = ok;
            runOnUiThread(() -> {
                setBusy(false);
                if (result) showStatus(1, tr("USB يعمل", "USB is working"), tr("تم إرسال اختبار للطابعة.", "A test was sent to the printer."));
                else showStatus(2, tr("فشل اختبار USB", "USB test failed"), tr("تحقق من الكابل وإذن USB.", "Check the cable and USB permission."));
            });
        });
    }

    private UsbDevice findPrinter() {
        if (usbManager == null) return null;
        UsbDevice best = null; int bestScore = -999;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            int score = 0;
            try {
                String name = ((d.getProductName()==null?"":d.getProductName()) + " " + (d.getManufacturerName()==null?"":d.getManufacturerName())).toLowerCase(Locale.US);
                if (name.contains("propos") || name.contains("printer") || name.contains("thermal") || name.contains("pos") || name.contains("xprinter")) score += 40;
            } catch (Exception ignored) {}
            for (int i=0;i<d.getInterfaceCount();i++) {
                UsbInterface f=d.getInterface(i);
                if (f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER) score += 100;
                if (f.getInterfaceClass()==UsbConstants.USB_CLASS_MASS_STORAGE) score -= 100;
                for (int e=0;e<f.getEndpointCount();e++) {
                    UsbEndpoint ep=f.getEndpoint(e);
                    if (ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection()==UsbConstants.USB_DIR_OUT) score += 10;
                }
            }
            if (score > bestScore) { bestScore=score; best=d; }
        }
        return bestScore >= 10 ? best : null;
    }

    private String deviceName(UsbDevice d) {
        try { if (d.getProductName()!=null && !d.getProductName().trim().isEmpty()) return d.getProductName(); } catch (Exception ignored) {}
        return String.format(Locale.US, "USB %04X:%04X", d.getVendorId(), d.getProductId());
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
        UsbDeviceConnection c=usbManager.openDevice(d); if(c==null)return null;
        if(!c.claimInterface(chosen,true)){c.close();return null;}
        return new UsbSession(c,chosen,out);
    }

    private static final class UsbSession implements Closeable {
        final UsbDeviceConnection c; final UsbInterface f; final UsbEndpoint out;
        UsbSession(UsbDeviceConnection c,UsbInterface f,UsbEndpoint out){this.c=c;this.f=f;this.out=out;}
        boolean write(byte[] data,int timeout){return c.bulkTransfer(out,data,data.length,timeout)==data.length;}
        boolean testPrint(){
            byte[] txt="TECHLIGHT - PP9000EU USB OK\n\n".getBytes(StandardCharsets.US_ASCII);
            byte[] b=new byte[txt.length+7]; b[0]=0x1B;b[1]=0x40;System.arraycopy(txt,0,b,2,txt.length);
            int x=2+txt.length;b[x]=0x0A;b[x+1]=0x0A;b[x+2]=0x1D;b[x+3]=0x56;b[x+4]=0x00;
            return write(b,2500);
        }
        @Override public void close(){try{c.releaseInterface(f);}catch(Exception ignored){}c.close();}
    }

    private NetInfo activeNetwork() {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE); if(cm==null)return null;
        Network net=cm.getActiveNetwork(); if(net==null)return null; LinkProperties lp=cm.getLinkProperties(net); if(lp==null)return null;
        Inet4Address ip=null,gw=null; int prefix=24;
        for(LinkAddress a:lp.getLinkAddresses()) if(a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress()){ip=(Inet4Address)a.getAddress();prefix=a.getPrefixLength();break;}
        if(ip==null)return null;
        for(RouteInfo r:lp.getRoutes()) if(r.isDefaultRoute() && r.getGateway() instanceof Inet4Address){gw=(Inet4Address)r.getGateway();break;}
        if(gw==null) try{gw=intToIp((ipToInt(ip)&maskForPrefix(prefix))+1);}catch(Exception ignored){}
        if(gw==null)return null;
        return new NetInfo(ip,gw,prefix);
    }

    private static final class NetInfo { final Inet4Address ip,gateway; final int prefix; NetInfo(Inet4Address i,Inet4Address g,int p){ip=i;gateway=g;prefix=p;} }

    private Inet4Address chooseFree(NetInfo n) {
        try {
            int ip=ipToInt(n.ip), mask=maskForPrefix(n.prefix), network=ip&mask, broadcast=network|~mask, gateway=ipToInt(n.gateway);
            int base = (network & 0xFFFFFF00) | 220;
            if (n.prefix <= 24 && base > network && base < broadcast && base != ip && base != gateway) {
                for (int last=220; last>=180; last--) {
                    int c=(network & 0xFFFFFF00)|last;
                    if(c<=network||c>=broadcast||c==ip||c==gateway)continue;
                    Inet4Address a=intToIp(c); if(!likelyInUse(a))return a;
                }
            }
            for(int c=broadcast-10, attempts=0;c>network+2 && attempts<100;c--,attempts++){
                if(c==ip||c==gateway)continue;Inet4Address a=intToIp(c);if(!likelyInUse(a))return a;
            }
        } catch(Exception ignored) {}
        return null;
    }

    private boolean likelyInUse(Inet4Address a) {
        try{if(a.isReachable(120))return true;}catch(IOException ignored){}
        int[] ports={9100,80,443,515,631};for(int p:ports)if(portOpen(a.getHostAddress(),p,100))return true;return false;
    }

    private static boolean waitForPrinter(String host,int totalMs){
        long end=System.currentTimeMillis()+totalMs;
        while(System.currentTimeMillis()<end){
            if(portOpen(host,9100,650))return true;
            sleep(800);
        }
        return false;
    }

    private static boolean portOpen(String host,int port,int timeout){try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),timeout);return true;}catch(IOException e){return false;}}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}

    private static byte[] dhcpOffCommand(){return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,(byte)0xFA,0x00,0x5A};}
    private static byte[] addressCommand(byte selector,Inet4Address a){byte[]b=a.getAddress();return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,selector,b[0],b[1],b[2],b[3]};}

    private static int ipToInt(Inet4Address a){byte[]b=a.getAddress();return((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);}
    private static Inet4Address intToIp(int v)throws Exception{return(Inet4Address)InetAddress.getByAddress(new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v});}
    private static int maskForPrefix(int p){if(p<=0)return 0;if(p>=32)return -1;return(int)(0xFFFFFFFFL<<(32-p));}

    private void fail(String detail){runOnUiThread(()->{setBusy(false);showStatus(2,tr("فشل تغيير IP","IP change failed"),detail);});}
    private void setBusy(boolean b){runOnUiThread(()->{setupButton.setEnabled(!b);testButton.setEnabled(!b);refreshButton.setEnabled(!b);});}
    private void postStatus(int kind,String title,String detail){runOnUiThread(()->showStatus(kind,title,detail));}
    private void showStatus(int kind,String title,String detail){
        statusTitle.setText(title);statusDetail.setText(detail);
        int bg=kind==1?0xFFE8F7EF:kind==2?0xFFFCEBED:0xFFF0EFF4;
        int fg=kind==1?0xFF168A55:kind==2?0xFFC73B45:0xFF211B34;
        statusCard.setBackground(roundRect(bg,18));statusTitle.setTextColor(fg);
    }

    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(17),dp(15),dp(17),dp(15));l.setBackground(roundRect(Color.WHITE,20));l.setElevation(dp(2));return l;}
    private TextView label(String s){return text(s,12,0xFF888290,true);}
    private TextView value(String s){TextView t=text(s,16,0xFF282235,true);LinearLayout.LayoutParams p=lp(-1,-2,0);p.topMargin=dp(4);t.setLayoutParams(p);return t;}
    private View divider(){View v=new View(this);v.setBackgroundColor(0xFFE9E7ED);LinearLayout.LayoutParams p=lp(-1,1,0);p.topMargin=dp(13);p.bottomMargin=dp(13);v.setLayoutParams(p);return v;}
    private TextView text(String s,float size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);t.setGravity(arabic?Gravity.RIGHT:Gravity.LEFT);return t;}
    private Button primaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(17);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(roundRect(0xFF6D28D9,17));return b;}
    private Button secondaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(0xFF4D4367);b.setAllCaps(false);b.setBackground(roundRect(0xFFEDEAF4,14));return b;}
    private String tr(String ar,String en){return arabic?ar:en;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int w,int h,float weight){return new LinearLayout.LayoutParams(w,h,weight);}
    private GradientDrawable roundRect(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
}
