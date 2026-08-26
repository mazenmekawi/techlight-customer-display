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

import java.io.ByteArrayOutputStream;
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
    private static final String USB_PERMISSION_ACTION = "sa.techlight.printersetup.USB_PERMISSION";
    private static final String PREFS = "printer_setup";
    private static final int ACTION_NONE = 0;
    private static final int ACTION_SETUP = 1;
    private static final int ACTION_TEST = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private UsbManager usbManager;
    private boolean arabic;
    private int pendingAction = ACTION_NONE;

    private TextView usbValue;
    private TextView networkValue;
    private TextView printerIpValue;
    private TextView statusTitle;
    private TextView statusDetail;
    private LinearLayout statusCard;
    private Button setupButton;
    private Button testButton;
    private Button refreshButton;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION_ACTION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                refreshSnapshot();
                int requested = pendingAction;
                pendingAction = ACTION_NONE;
                if (!granted) {
                    showStatus(2, tr("تعذر الوصول للطابعة عبر USB", "USB access was denied"),
                            tr("اسمح بالوصول للطابعة ثم حاول مرة أخرى.", "Allow USB access and try again."));
                    return;
                }
                if (requested == ACTION_SETUP) startSetup();
                if (requested == ACTION_TEST) startTest();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
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
        registerUsbReceiver();
        refreshSnapshot();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 247, 251));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(28));
        root.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(brandRow, lp(-1, dp(76), 0));

        TextView mark = new TextView(this);
        mark.setText("T");
        mark.setGravity(Gravity.CENTER);
        mark.setTextColor(Color.WHITE);
        mark.setTextSize(38);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setBackground(roundRect(0xFF6D28D9, 18));
        brandRow.addView(mark, lp(dp(64), dp(64), 0));

        TextView wordmark = new TextView(this);
        wordmark.setText("TECHLIGHT\nضوء التقنية");
        wordmark.setTextColor(0xFF2A2148);
        wordmark.setTextSize(18);
        wordmark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        wordmark.setGravity(arabic ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams wordLp = lp(0, -2, 1);
        wordLp.setMargins(dp(12), 0, dp(12), 0);
        brandRow.addView(wordmark, wordLp);

        Button lang = secondaryButton(arabic ? "English" : "العربية");
        lang.setOnClickListener(v -> {
            prefs.edit().putString("lang", arabic ? "en" : "ar").apply();
            recreate();
        });
        brandRow.addView(lang, lp(dp(98), dp(46), 0));

        TextView title = text(tr("إعداد شبكة الطابعة", "Printer Network Setup"), 29, 0xFF211B34, true);
        LinearLayout.LayoutParams titleLp = lp(-1, -2, 0); titleLp.topMargin = dp(25);
        root.addView(title, titleLp);

        TextView subtitle = text(tr("وصّل الطابعة بالتابلت عبر USB، ثم اضبط IP بضغطة واحدة.",
                "Connect the printer by USB, then set its IP with one tap."), 16, 0xFF6B6678, false);
        LinearLayout.LayoutParams subLp = lp(-1, -2, 0); subLp.topMargin = dp(7);
        root.addView(subtitle, subLp);

        LinearLayout infoCard = card();
        LinearLayout.LayoutParams cardLp = lp(-1, -2, 0); cardLp.topMargin = dp(22);
        root.addView(infoCard, cardLp);

        infoCard.addView(label(tr("طابعة USB", "USB Printer")));
        usbValue = value(tr("بانتظار الطابعة…", "Waiting for printer…"));
        infoCard.addView(usbValue);
        infoCard.addView(divider());
        infoCard.addView(label(tr("شبكة التابلت", "Tablet Network")));
        networkValue = value(tr("لا توجد شبكة نشطة", "No active network"));
        infoCard.addView(networkValue);
        infoCard.addView(divider());
        infoCard.addView(label(tr("IP الطابعة", "Printer IP")));
        printerIpValue = value(prefs.getString("last_ip", tr("لم يتم الإعداد بعد", "Not configured yet")));
        printerIpValue.setTextColor(0xFF5B21B6);
        printerIpValue.setTextSize(21);
        infoCard.addView(printerIpValue);

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusCard.setBackground(roundRect(0xFFF0EFF4, 18));
        LinearLayout.LayoutParams statusLp = lp(-1, -2, 0); statusLp.topMargin = dp(15);
        root.addView(statusCard, statusLp);
        statusTitle = text(tr("جاهز", "Ready"), 16, 0xFF211B34, true);
        statusDetail = text(tr("وصّل الطابعة بالتابلت عبر USB.", "Connect the printer to the tablet by USB."), 14, 0xFF6B6678, false);
        statusCard.addView(statusTitle);
        LinearLayout.LayoutParams detailLp = lp(-1, -2, 0); detailLp.topMargin = dp(4);
        statusCard.addView(statusDetail, detailLp);

        setupButton = primaryButton(tr("إعداد الطابعة تلقائيًا", "Auto Setup Printer"));
        LinearLayout.LayoutParams setupLp = lp(-1, dp(62), 0); setupLp.topMargin = dp(20);
        root.addView(setupButton, setupLp);
        setupButton.setOnClickListener(v -> ensureUsb(ACTION_SETUP));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = lp(-1, -2, 0); actionsLp.topMargin = dp(11);
        root.addView(actions, actionsLp);
        testButton = secondaryButton(tr("اختبار الطباعة", "Test Print"));
        refreshButton = secondaryButton(tr("تحديث", "Refresh"));
        actions.addView(testButton, lp(0, dp(52), 1));
        Space gap = new Space(this); actions.addView(gap, lp(dp(10), 1, 0));
        actions.addView(refreshButton, lp(0, dp(52), 1));
        testButton.setOnClickListener(v -> ensureUsb(ACTION_TEST));
        refreshButton.setOnClickListener(v -> refreshSnapshot());

        TextView note = text(tr("نسخة تجريبية • بروتوكول POS-80 لإعداد Ethernet عبر USB",
                "Beta • POS-80 USB Ethernet configuration profile"), 12, 0xFF777181, false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = lp(-1, -2, 0); noteLp.topMargin = dp(19);
        root.addView(note, noteLp);

        setContentView(scroll);
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(USB_PERMISSION_ACTION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, filter);
    }

    private void refreshSnapshot() {
        UsbDevice device = findPrinter();
        if (device == null) usbValue.setText(tr("غير متصلة", "Not connected"));
        else if (!usbManager.hasPermission(device)) usbValue.setText(tr("متصلة — تحتاج إذن USB", "Connected — USB permission required"));
        else usbValue.setText(tr("متصلة: ", "Connected: ") + deviceName(device));

        NetInfo net = activeNetwork();
        if (net == null) networkValue.setText(tr("لا توجد شبكة IPv4 نشطة", "No active IPv4 network"));
        else networkValue.setText(net.ip.getHostAddress() + "  •  " + (net.gateway == null ? "—" : net.gateway.getHostAddress()));

        String last = prefs.getString("last_ip", null);
        printerIpValue.setText(last == null ? tr("لم يتم الإعداد بعد", "Not configured yet") : last);

        if (device == null) showStatus(0, tr("جاهز", "Ready"), tr("وصّل الطابعة بالتابلت عبر USB.", "Connect the printer by USB."));
        else if (net == null) showStatus(2, tr("لا توجد شبكة", "No network"), tr("وصّل التابلت بالواي فاي أو Ethernet أولًا.", "Connect the tablet to Wi-Fi or Ethernet first."));
        else showStatus(0, tr("جاهز", "Ready"), tr("اضغط إعداد الطابعة تلقائيًا.", "Tap Auto Setup Printer."));
    }

    private void ensureUsb(int action) {
        UsbDevice device = findPrinter();
        if (device == null) {
            showStatus(2, tr("الطابعة غير متصلة", "Printer not connected"), tr("وصّل USB ثم اضغط تحديث.", "Connect USB, then tap Refresh."));
            return;
        }
        if (!usbManager.hasPermission(device)) {
            pendingAction = action;
            Intent intent = new Intent(USB_PERMISSION_ACTION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags));
            return;
        }
        if (action == ACTION_SETUP) startSetup();
        else startTest();
    }

    private void startSetup() {
        busy(true);
        showStatus(0, tr("جاري الإعداد…", "Configuring…"), tr("جاري اختيار IP متاح.", "Selecting an available IP."));
        executor.execute(() -> {
            NetInfo net = activeNetwork();
            if (net == null || net.gateway == null) { finishError(tr("تعذر قراءة شبكة التابلت.", "Could not read the tablet network.")); return; }
            Inet4Address target = chooseFree(net);
            if (target == null) { finishError(tr("تعذر اختيار IP متاح.", "Could not choose an available IP.")); return; }
            Inet4Address mask;
            try { mask = intToIp(maskForPrefix(net.prefix)); }
            catch (Exception e) { finishError(tr("تعذر حساب Subnet Mask.", "Could not calculate subnet mask.")); return; }

            postStatus(0, tr("جاري الإعداد…", "Configuring…"), tr("جاري إرسال IP للطابعة عبر USB.", "Sending network settings over USB."));
            UsbDevice device = findPrinter();
            if (device == null || !usbManager.hasPermission(device)) { finishError(tr("فُقد اتصال USB.", "USB connection was lost.")); return; }

            boolean sent = false;
            try (UsbSession session = openUsb(device)) {
                if (session != null) sent = session.write(configCommand(target, mask, net.gateway), 3500);
            } catch (Exception ignored) {}
            if (!sent) { finishError(tr("لم تقبل الطابعة أمر الشبكة عبر USB.", "The printer did not accept the USB network command.")); return; }

            String ip = target.getHostAddress();
            postStatus(0, tr("جاري التحقق…", "Verifying…"), tr("جاري البحث عن الطابعة على ", "Checking printer at ") + ip);
            boolean verified = waitPort(ip, 9100, 12000);
            prefs.edit().putString("last_ip", ip).apply();
            runOnUiThread(() -> {
                busy(false);
                printerIpValue.setText(ip);
                if (verified) showStatus(1, tr("تم إعداد الطابعة بنجاح", "Printer configured successfully"), tr("IP الطابعة: ", "Printer IP: ") + ip);
                else showStatus(0, tr("تم إرسال الإعدادات", "Settings sent"), tr("تم تعيين ", "Assigned ") + ip + tr(" — تأكد من كابل LAN ثم اضغط تحديث.", " — check the LAN cable, then tap Refresh."));
            });
        });
    }

    private void startTest() {
        busy(true);
        executor.execute(() -> {
            UsbDevice device = findPrinter();
            boolean ok = false;
            if (device != null && usbManager.hasPermission(device)) {
                try (UsbSession session = openUsb(device)) {
                    if (session != null) ok = session.testPrint();
                } catch (Exception ignored) {}
            }
            boolean result = ok;
            runOnUiThread(() -> {
                busy(false);
                if (result) showStatus(1, tr("تم الاختبار", "Test complete"), tr("تم إرسال صفحة الاختبار للطابعة.", "Test page sent to printer."));
                else showStatus(2, tr("فشل الاختبار", "Test failed"), tr("تعذر إرسال صفحة الاختبار.", "Could not send the test page."));
            });
        });
    }

    private UsbDevice findPrinter() {
        if (usbManager == null) return null;
        UsbDevice best = null; int bestScore = -1000;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            int score = 0;
            try {
                String n = ((d.getProductName() == null ? "" : d.getProductName()) + " " + (d.getManufacturerName() == null ? "" : d.getManufacturerName())).toLowerCase(Locale.US);
                if (n.contains("printer") || n.contains("pos") || n.contains("thermal") || n.contains("propos") || n.contains("xprinter")) score += 30;
            } catch (Exception ignored) {}
            for (int i=0;i<d.getInterfaceCount();i++) {
                UsbInterface f=d.getInterface(i);
                if (f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER) score+=100;
                if (f.getInterfaceClass()==UsbConstants.USB_CLASS_MASS_STORAGE) score-=100;
                for(int e=0;e<f.getEndpointCount();e++) {
                    UsbEndpoint ep=f.getEndpoint(e);
                    if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK && ep.getDirection()==UsbConstants.USB_DIR_OUT) score+=10;
                }
            }
            if(score>bestScore){bestScore=score;best=d;}
        }
        return bestScore>=10 ? best : null;
    }

    private String deviceName(UsbDevice d) {
        try { if (d.getProductName()!=null && !d.getProductName().trim().isEmpty()) return d.getProductName(); }
        catch (Exception ignored) {}
        return String.format(Locale.US,"USB %04X:%04X",d.getVendorId(),d.getProductId());
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
            byte[] text="TECHLIGHT PRINTER TEST\nUSB communication OK\n\n".getBytes(StandardCharsets.US_ASCII);
            byte[] p=new byte[text.length+7]; p[0]=0x1B;p[1]=0x40;System.arraycopy(text,0,p,2,text.length);
            int o=2+text.length;p[o]=0x0A;p[o+1]=0x0A;p[o+2]=0x1D;p[o+3]=0x56;p[o+4]=0x00;
            return write(p,2500);
        }
        @Override public void close(){try{c.releaseInterface(f);}catch(Exception ignored){}c.close();}
    }

    private NetInfo activeNetwork() {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE); if(cm==null)return null;
        Network n=cm.getActiveNetwork(); if(n==null)return null; LinkProperties lp=cm.getLinkProperties(n); if(lp==null)return null;
        Inet4Address ip=null,gw=null; int prefix=24;
        for(LinkAddress a:lp.getLinkAddresses()) if(a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress()){ip=(Inet4Address)a.getAddress();prefix=a.getPrefixLength();break;}
        if(ip==null)return null;
        for(RouteInfo r:lp.getRoutes()) if(r.isDefaultRoute() && r.getGateway() instanceof Inet4Address){gw=(Inet4Address)r.getGateway();break;}
        if(gw==null) try{gw=intToIp((ipToInt(ip)&maskForPrefix(prefix))+1);}catch(Exception ignored){}
        return new NetInfo(ip,gw,prefix);
    }

    private static final class NetInfo { final Inet4Address ip,gateway; final int prefix; NetInfo(Inet4Address i,Inet4Address g,int p){ip=i;gateway=g;prefix=p;} }

    private Inet4Address chooseFree(NetInfo n) {
        try {
            int ip=ipToInt(n.ip), mask=maskForPrefix(n.prefix), network=ip&mask, broadcast=network|~mask, gateway=ipToInt(n.gateway);
            int attempts=0;
            for(int c=broadcast-20;c>network+2 && attempts<80;c--,attempts++) {
                if(c==ip||c==gateway)continue; Inet4Address a=intToIp(c); if(!likelyInUse(a))return a;
            }
        }catch(Exception ignored){}
        return null;
    }

    private boolean likelyInUse(Inet4Address a) {
        try{if(a.isReachable(130))return true;}catch(IOException ignored){}
        int[] ports={9100,80,443,515,631};for(int p:ports)if(portOpen(a.getHostAddress(),p,100))return true;return false;
    }

    private static boolean portOpen(String host,int port,int timeout){try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),timeout);return true;}catch(IOException e){return false;}}
    private static boolean waitPort(String host,int port,int total){long end=System.currentTimeMillis()+total;while(System.currentTimeMillis()<end){if(portOpen(host,port,450))return true;try{Thread.sleep(700);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}return false;}

    private static int ipToInt(Inet4Address a){byte[]b=a.getAddress();return((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);}
    private static Inet4Address intToIp(int v)throws Exception{return(Inet4Address)InetAddress.getByAddress(new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v});}
    private static int maskForPrefix(int p){if(p<=0)return 0;if(p>=32)return -1;return(int)(0xFFFFFFFFL<<(32-p));}

    private static byte[] configCommand(Inet4Address ip,Inet4Address mask,Inet4Address gateway) {
        ByteArrayOutputStream o=new ByteArrayOutputStream();
        try {
            o.write(new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,(byte)0xFA,0x00,0x5A});
            o.write(addrCommand((byte)0x51,mask));
            o.write(addrCommand((byte)0x52,gateway));
            o.write(addrCommand((byte)0x50,ip));
        }catch(IOException ignored){}
        return o.toByteArray();
    }
    private static byte[] addrCommand(byte selector,Inet4Address a){byte[]b=a.getAddress();return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,selector,b[0],b[1],b[2],b[3]};}

    private void busy(boolean b){runOnUiThread(()->{setupButton.setEnabled(!b);testButton.setEnabled(!b);refreshButton.setEnabled(!b);});}
    private void finishError(String msg){runOnUiThread(()->{busy(false);showStatus(2,tr("تعذر إكمال الإعداد","Setup failed"),msg);});}
    private void postStatus(int kind,String title,String detail){runOnUiThread(()->showStatus(kind,title,detail));}
    private void showStatus(int kind,String title,String detail){statusTitle.setText(title);statusDetail.setText(detail);int bg=kind==1?0xFFE8F7EF:kind==2?0xFFFCEBED:0xFFF0EFF4;int fg=kind==1?0xFF168A55:kind==2?0xFFC73B45:0xFF211B34;statusCard.setBackground(roundRect(bg,18));statusTitle.setTextColor(fg);}

    private String tr(String ar,String en){return arabic?ar:en;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int w,int h,float weight){return new LinearLayout.LayoutParams(w,h,weight);}
    private GradientDrawable roundRect(int color,int radiusDp){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radiusDp));return d;}
    private TextView text(String s,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.12f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private TextView label(String s){TextView v=text(s,13,0xFF777181,false);LinearLayout.LayoutParams p=lp(-1,-2,0);p.bottomMargin=dp(5);v.setLayoutParams(p);return v;}
    private TextView value(String s){return text(s,17,0xFF211B34,true);}
    private View divider(){View v=new View(this);v.setBackgroundColor(0xFFE7E4EE);LinearLayout.LayoutParams p=lp(-1,dp(1),0);p.topMargin=dp(16);p.bottomMargin=dp(16);v.setLayoutParams(p);return v;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(18),dp(16),dp(18),dp(16));c.setBackground(roundRect(Color.WHITE,20));return c;}
    private Button primaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(18);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF7C3AED,0xFF5B21B6});g.setCornerRadius(dp(16));b.setBackground(g);return b;}
    private Button secondaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(0xFF4C1D95);b.setAllCaps(false);b.setBackground(roundRect(0xFFF2EEFB,14));return b;}

    @Override protected void onDestroy(){try{unregisterReceiver(usbReceiver);}catch(Exception ignored){}executor.shutdownNow();super.onDestroy();}
}
