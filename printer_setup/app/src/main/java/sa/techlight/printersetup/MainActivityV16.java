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
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivityV16 extends Activity {
    private static final String USB_PERMISSION = "sa.techlight.printersetup.USB_PERMISSION_V16";
    private static final String PREFS = "pp9000eu_v16";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private UsbManager usb;
    private SharedPreferences prefs;
    private boolean ar = true;
    private boolean manualMode = false;
    private int pendingAction = 0;

    private LinearLayout root, guideScreen, setupScreen, manualPanel, statusCard;
    private TextView guideUsb, guideNetwork, tabletText, printerText, prefixText, statusTitle, statusText;
    private CheckBox lanConfirm;
    private EditText lastOctet;
    private Button continueBtn, autoBtn, manualBtn, setBtn, verifyBtn, changeAgainBtn, guideBtn, langBtn;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                int actionCode = pendingAction;
                pendingAction = 0;
                refreshAll();
                if (!granted) {
                    showError(tr("تم رفض إذن USB", "USB permission denied"), tr("اسمح للتطبيق بالوصول للطابعة حتى يقدر يكتب IP.", "Allow USB access so the app can write the printer IP."));
                } else if (actionCode == 1) {
                    startSetIp();
                }
                return;
            }
            refreshAll();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action) && hasPending()) {
                showWarning(tr("USB انفصل — هذا طبيعي بعد الإرسال", "USB disconnected — this is OK after sending"), tr("أعد تشغيل الطابعة واترك كابل LAN موصولاً. التحقق الآن عبر الشبكة فقط.", "Restart the printer and keep LAN connected. Verification is now network-only."));
                verifyLater();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) && hasPending()) {
                verifyLater();
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
        refreshAll();
        showGuide();
        if (hasPending()) verifyLater();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAll();
        if (hasPending()) verifyLater();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF6F6FA);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(30));
        root.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        buildHeader();
        buildGuideScreen();
        buildSetupScreen();
        setContentView(scroll);
    }

    private void buildHeader() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.techlight_brand_transparent);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        top.addView(logo, lp(dp(176), dp(60), 0));

        Space spacer = new Space(this);
        top.addView(spacer, lp(0, 1, 1));

        langBtn = secondary(ar ? "English" : "العربية");
        langBtn.setOnClickListener(v -> {
            prefs.edit().putString("lang", ar ? "en" : "ar").apply();
            recreate();
        });
        top.addView(langBtn, lp(dp(100), dp(44), 0));
        root.addView(top, lp(-1, dp(64), 0));

        TextView domain = text("techlight.sa", 12, 0xFF7659A8, true);
        domain.setGravity(ar ? Gravity.RIGHT : Gravity.LEFT);
        root.addView(domain, lp(-1, -2, 0));
    }

    private void buildGuideScreen() {
        guideScreen = new LinearLayout(this);
        guideScreen.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gp = lp(-1, -2, 0);
        gp.topMargin = dp(18);
        root.addView(guideScreen, gp);

        guideScreen.addView(text(tr("قبل ما تبدأ", "Before you start"), 28, 0xFF1F1732, true));
        TextView intro = text(tr("3 توصيلات بسيطة فقط. تأكد منها ثم التطبيق يكمل الباقي.", "Only 3 simple connections. Confirm them and the app handles the rest."), 14, 0xFF6C6674, false);
        LinearLayout.LayoutParams ip = lp(-1,-2,0); ip.topMargin=dp(6); guideScreen.addView(intro,ip);

        guideScreen.addView(guideCard(R.drawable.guide_printer, tr("1. شغّل الطابعة", "1. Power on the printer"), tr("شغّل ProPOS PP9000EU ووصل كابل USB بين الطابعة والتابلت.", "Power on the ProPOS PP9000EU and connect its USB cable to the tablet.")));
        guideScreen.addView(guideCard(R.drawable.guide_router, tr("2. وصّل الطابعة بالراوتر", "2. Connect printer to router"), tr("وصل كابل LAN من منفذ الشبكة في الطابعة إلى نفس الراوتر المستخدم بالمحل.", "Connect an Ethernet/LAN cable from the printer to the same router used at the location.")));
        guideScreen.addView(guideCard(R.drawable.guide_cable, tr("3. وصّل التابلت بنفس الشبكة", "3. Put tablet on the same network"), tr("اتصل من التابلت بواي فاي نفس الراوتر. لا تستخدم شبكة جوال أثناء الإعداد.", "Connect the tablet to that router's Wi-Fi. Avoid mobile data during setup.")));

        LinearLayout ready = card();
        LinearLayout.LayoutParams rp=lp(-1,-2,0);rp.topMargin=dp(14);guideScreen.addView(ready,rp);
        ready.addView(text(tr("فحص الجاهزية", "Ready check"),17,0xFF241936,true));
        guideUsb = statusLine(); ready.addView(guideUsb);
        guideNetwork = statusLine(); ready.addView(guideNetwork);
        lanConfirm = new CheckBox(this);
        lanConfirm.setText(tr("تم توصيل كابل LAN بين الطابعة والراوتر", "LAN cable is connected between printer and router"));
        lanConfirm.setTextSize(14); lanConfirm.setTextColor(0xFF3F384A); lanConfirm.setPadding(0,dp(8),0,0);
        ready.addView(lanConfirm);

        continueBtn = primary(tr("تحقق وابدأ الإعداد", "Check & Start Setup"));
        LinearLayout.LayoutParams cp=lp(-1,dp(60),0);cp.topMargin=dp(16);guideScreen.addView(continueBtn,cp);
        continueBtn.setOnClickListener(v -> validateGuideAndContinue());
    }

    private void buildSetupScreen() {
        setupScreen = new LinearLayout(this);
        setupScreen.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sp=lp(-1,-2,0);sp.topMargin=dp(18);root.addView(setupScreen,sp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(tr("اختيار IP للطابعة", "Choose printer IP"),26,0xFF1F1732,true);
        titleRow.addView(title,lp(0,-2,1));
        guideBtn=secondary(tr("الإرشادات", "Guide"));
        guideBtn.setOnClickListener(v->showGuide());
        titleRow.addView(guideBtn,lp(dp(105),dp(44),0));
        setupScreen.addView(titleRow);

        TextView hint=text(tr("الوضع التلقائي هو الأسرع. اليدوي يسمح لك بكتابة الرقم الأخير فقط.", "Automatic is fastest. Manual lets you enter only the final IP number."),14,0xFF6C6674,false);
        LinearLayout.LayoutParams hp=lp(-1,-2,0);hp.topMargin=dp(6);setupScreen.addView(hint,hp);

        LinearLayout net=card();LinearLayout.LayoutParams np=lp(-1,-2,0);np.topMargin=dp(16);setupScreen.addView(net,np);
        net.addView(label(tr("شبكة التابلت", "Tablet network"))); tabletText=value("—"); net.addView(tabletText);
        net.addView(divider());
        net.addView(label(tr("IP الطابعة", "Printer IP"))); printerText=value("—"); printerText.setTextSize(20); printerText.setTextColor(0xFF6D28D9); net.addView(printerText);

        LinearLayout modes=new LinearLayout(this);modes.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams mp=lp(-1,-2,0);mp.topMargin=dp(14);setupScreen.addView(modes,mp);
        autoBtn=modeButton(tr("تلقائي\nموصى به", "Automatic\nRecommended"));
        manualBtn=modeButton(tr("يدوي\nالرقم الأخير", "Manual\nLast number"));
        modes.addView(autoBtn,lp(0,dp(68),1));Space gap=new Space(this);modes.addView(gap,lp(dp(10),1,0));modes.addView(manualBtn,lp(0,dp(68),1));
        autoBtn.setOnClickListener(v->setManualMode(false));
        manualBtn.setOnClickListener(v->setManualMode(true));

        manualPanel=card();LinearLayout.LayoutParams mpp=lp(-1,-2,0);mpp.topMargin=dp(12);setupScreen.addView(manualPanel,mpp);
        manualPanel.addView(text(tr("اكتب الرقم الأخير فقط", "Enter only the last number"),15,0xFF241936,true));
        LinearLayout inputRow=new LinearLayout(this);inputRow.setOrientation(LinearLayout.HORIZONTAL);inputRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams irp=lp(-1,-2,0);irp.topMargin=dp(10);manualPanel.addView(inputRow,irp);
        prefixText=text("192.168.1.",22,0xFF4B4555,true);inputRow.addView(prefixText,lp(0,dp(54),1));
        lastOctet=new EditText(this);lastOctet.setTextSize(22);lastOctet.setGravity(Gravity.CENTER);lastOctet.setSingleLine(true);lastOctet.setInputType(InputType.TYPE_CLASS_NUMBER);lastOctet.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});lastOctet.setHint("50");lastOctet.setBackground(round(0xFFF1EEF8,14));inputRow.addView(lastOctet,lp(dp(90),dp(54),0));
        TextView safe=text(tr("قبل الإرسال سنفحص الرقم أكثر من مرة. إذا ظهر مستخدمًا لن نغير الطابعة وسنقترح رقمًا آخر.", "Before sending, the app checks the address multiple times. If it looks used, nothing is changed and another number is suggested."),12,0xFF746D7B,false);LinearLayout.LayoutParams sfp=lp(-1,-2,0);sfp.topMargin=dp(8);manualPanel.addView(safe,sfp);

        statusCard=card();LinearLayout.LayoutParams stp=lp(-1,-2,0);stp.topMargin=dp(14);setupScreen.addView(statusCard,stp);
        statusTitle=text(tr("جاهز", "Ready"),16,0xFF211B34,true);statusText=text("",14,0xFF655F70,false);statusCard.addView(statusTitle);statusCard.addView(statusText);

        setBtn=primary(tr("اختيار IP آمن وضبط الطابعة", "Choose Safe IP & Configure"));LinearLayout.LayoutParams sbp=lp(-1,dp(60),0);sbp.topMargin=dp(16);setupScreen.addView(setBtn,sbp);setBtn.setOnClickListener(v->beginSetupAction());
        verifyBtn=secondary(tr("تحقق من IP عبر LAN", "Verify IP over LAN"));LinearLayout.LayoutParams vbp=lp(-1,dp(50),0);vbp.topMargin=dp(10);setupScreen.addView(verifyBtn,vbp);verifyBtn.setOnClickListener(v->verifyPending(true));
        changeAgainBtn=secondary(tr("تغيير IP مرة أخرى", "Change IP Again"));LinearLayout.LayoutParams cap=lp(-1,dp(50),0);cap.topMargin=dp(10);setupScreen.addView(changeAgainBtn,cap);changeAgainBtn.setOnClickListener(v->resetForAnotherIp());

        TextView foot=text("ضوء التقنية  •  techlight.sa  •  PP9000EU  •  v1.6",11,0xFF8A8490,false);foot.setGravity(Gravity.CENTER);LinearLayout.LayoutParams fp=lp(-1,-2,0);fp.topMargin=dp(18);setupScreen.addView(foot,fp);
        setManualMode(false);
    }

    private LinearLayout guideCard(int iconRes, String title, String body) {
        LinearLayout c=card();c.setOrientation(LinearLayout.HORIZONTAL);c.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams p=lp(-1,-2,0);p.topMargin=dp(12);c.setLayoutParams(p);
        ImageView image=new ImageView(this);image.setImageResource(iconRes);image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);c.addView(image,lp(dp(92),dp(92),0));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams cp=lp(0,-2,1);cp.setMargins(dp(14),0,dp(4),0);c.addView(copy,cp);
        copy.addView(text(title,16,0xFF241936,true));TextView b=text(body,13,0xFF6B6573,false);LinearLayout.LayoutParams bp=lp(-1,-2,0);bp.topMargin=dp(5);copy.addView(b,bp);
        return c;
    }

    private TextView statusLine(){TextView t=text("—",14,0xFF4E4758,true);LinearLayout.LayoutParams p=lp(-1,-2,0);p.topMargin=dp(10);t.setLayoutParams(p);return t;}

    private void showGuide(){guideScreen.setVisibility(View.VISIBLE);setupScreen.setVisibility(View.GONE);refreshGuide();}
    private void showSetup(){guideScreen.setVisibility(View.GONE);setupScreen.setVisibility(View.VISIBLE);refreshAll();}

    private void validateGuideAndContinue(){
        refreshGuide();
        UsbDevice d=findPrinter();NetInfo n=netInfo();
        if(d==null){showGuideError(tr("وصّل USB بالطابعة أولاً.","Connect the printer by USB first."));return;}
        if(n==null){showGuideError(tr("وصّل التابلت بواي فاي الراوتر أولاً.","Connect the tablet to the router Wi-Fi first."));return;}
        if(!lanConfirm.isChecked()){showGuideError(tr("أكد توصيل كابل LAN بين الطابعة والراوتر.","Confirm the LAN cable between printer and router."));return;}
        showSetup();
        showNeutral(tr("جاهز للإعداد ✓", "Ready to configure ✓"), tr("اختر تلقائي أو يدوي ثم اضغط ضبط الطابعة.","Choose automatic or manual, then configure the printer."));
    }

    private void showGuideError(String msg){
        continueBtn.setText(tr("راجع التوصيلات وحاول مرة أخرى","Check connections and try again"));
        guideNetwork.postDelayed(()->continueBtn.setText(tr("تحقق وابدأ الإعداد","Check & Start Setup")),1800);
        guideUsb.setContentDescription(msg);
    }

    private void refreshGuide(){
        UsbDevice d=findPrinter();NetInfo n=netInfo();
        guideUsb.setText(d==null?tr("○ USB: غير متصل بالطابعة","○ USB: printer not connected"):tr("✓ USB: الطابعة متصلة","✓ USB: printer connected"));
        guideUsb.setTextColor(d==null?0xFFC73B45:0xFF168A55);
        guideNetwork.setText(n==null?tr("○ الشبكة: التابلت غير متصل","○ Network: tablet not connected"):tr("✓ الشبكة: ","✓ Network: ")+n.ip.getHostAddress()+"  •  GW "+n.gw.getHostAddress());
        guideNetwork.setTextColor(n==null?0xFFC73B45:0xFF168A55);
    }

    private void setManualMode(boolean manual){
        manualMode=manual;
        manualPanel.setVisibility(manual?View.VISIBLE:View.GONE);
        autoBtn.setBackground(round(manual?0xFFF1EEF8:0xFFEDE5FF,16));
        manualBtn.setBackground(round(manual?0xFFEDE5FF:0xFFF1EEF8,16));
        autoBtn.setTextColor(manual?0xFF655B73:0xFF5B21B6);manualBtn.setTextColor(manual?0xFF5B21B6:0xFF655B73);
        setBtn.setText(manual?tr("فحص الرقم وضبط الطابعة","Check Number & Configure"):tr("اختيار IP آمن وضبط الطابعة","Choose Safe IP & Configure"));
        updatePrefix();
    }

    private void updatePrefix(){NetInfo n=netInfo();if(n==null){prefixText.setText("—");return;}byte[]b=n.ip.getAddress();prefixText.setText((b[0]&255)+"."+(b[1]&255)+"."+(b[2]&255)+".");}

    private void refreshAll(){
        refreshGuide();updatePrefix();NetInfo n=netInfo();
        if(n==null)tabletText.setText(tr("لا توجد شبكة IPv4","No IPv4 network"));else tabletText.setText(n.ip.getHostAddress()+"  •  GW "+n.gw.getHostAddress()+"  /"+n.prefix);
        String verified=prefs.getString("verified_ip",null);String pending=prefs.getString("pending_ip",null);
        if(verified!=null){printerText.setText(verified+" ✓");changeAgainBtn.setVisibility(View.VISIBLE);verifyBtn.setVisibility(View.GONE);setBtn.setVisibility(View.GONE);}
        else if(pending!=null){printerText.setText(pending+tr(" — بانتظار التحقق"," — waiting for verification"));changeAgainBtn.setVisibility(View.VISIBLE);verifyBtn.setVisibility(View.VISIBLE);setBtn.setVisibility(View.GONE);}
        else{printerText.setText(tr("لم يتم التعيين بعد","Not assigned yet"));changeAgainBtn.setVisibility(View.GONE);verifyBtn.setVisibility(View.GONE);setBtn.setVisibility(View.VISIBLE);}
    }

    private void resetForAnotherIp(){
        String lang=prefs.getString("lang",ar?"ar":"en");prefs.edit().clear().putString("lang",lang).apply();lastOctet.setText("");setManualMode(false);refreshAll();showNeutral(tr("جاهز لتغيير جديد","Ready for a new IP"),tr("اختر تلقائي أو يدوي. لن يتم استخدام IP ظاهر كمستخدم.","Choose automatic or manual. An address that appears in use will not be used."));
    }

    private void beginSetupAction(){
        if(hasPending()){verifyPending(true);return;}
        UsbDevice d=findPrinter();if(d==null){showError(tr("USB غير متصل","USB not connected"),tr("وصّل الطابعة بالتابلت عبر USB.","Connect the printer to the tablet over USB."));return;}
        if(!usb.hasPermission(d)){pendingAction=1;Intent x=new Intent(USB_PERMISSION).setPackage(getPackageName());int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;usb.requestPermission(d,PendingIntent.getBroadcast(this,0,x,flags));return;}
        startSetIp();
    }

    private void startSetIp(){
        busy(true);showNeutral(tr("جاري فحص IP…","Checking IP…"),tr("لن نرسل أي شيء للطابعة قبل التأكد أن العنوان لا يبدو مستخدمًا.","Nothing is sent to the printer until the address appears unused."));
        worker.execute(()->{
            NetInfo n=netInfo();if(n==null){fail(tr("تعذر قراءة شبكة التابلت.","Could not read the tablet network."));return;}
            Inet4Address target;
            if(manualMode){target=manualTarget(n);if(target==null)return;}else{target=chooseNearestFree(n);if(target==null){fail(tr("لم أجد IP متاحًا قريبًا. جرّب الوضع اليدوي.","No nearby free IP was found. Try manual mode."));return;}}
            String targetIp=target.getHostAddress();String tabletIp=n.ip.getHostAddress();
            if(inUseStrong(target)){Inet4Address suggestion=chooseNearestFree(n);String s=suggestion==null?"":suggestion.getHostAddress();runOnUiThread(()->{busy(false);if(suggestion!=null){byte[]b=suggestion.getAddress();lastOctet.setText(String.valueOf(b[3]&255));setManualMode(true);}showWarning(tr("هذا IP يبدو مستخدمًا","This IP appears to be in use"),tr("لم نغير أي شيء في الطابعة.","Nothing was changed on the printer.")+(s.isEmpty()?"":tr("\nاقترحنا لك: ","\nSuggested: ")+s));});return;}
            UsbDevice d=findPrinter();if(d==null||!usb.hasPermission(d)){fail(tr("فُقد اتصال USB قبل إرسال IP.","USB was lost before sending the IP."));return;}
            showNeutral(tr("جاري إرسال IP للطابعة…","Sending IP to printer…"),tabletIp+"  →  "+targetIp);
            boolean sent=sendIpWithRetry(d,target,targetIp);if(!sent){fail(tr("فشل إرسال أمر Set IP عبر USB.","Set IP command failed over USB."));return;}
            prefs.edit().putString("tablet_ip",tabletIp).putString("pending_ip",targetIp).remove("verified_ip").apply();
            if(networkResponds(targetIp,5000)){markVerified(targetIp);return;}
            runOnUiThread(()->{busy(false);refreshAll();showWarning(tr("تم إرسال IP — أعد تشغيل الطابعة","IP sent — restart printer"),tr("IP الجديد: ","New IP: ")+targetIp+tr("\nأطفئ الطابعة 5 ثوانٍ ثم شغّلها. التحقق بعدها عبر LAN فقط.","\nPower the printer off for 5 seconds, then on. Verification is LAN-only after that."));});
        });
    }

    private Inet4Address manualTarget(NetInfo n){
        String raw=lastOctet.getText().toString().trim();int last;
        try{last=Integer.parseInt(raw);}catch(Exception e){runOnUiThread(()->{busy(false);showError(tr("أدخل رقمًا صحيحًا","Enter a valid number"),tr("اكتب الرقم الأخير من 2 إلى 254.","Enter the last number from 2 to 254."));});return null;}
        if(last<2||last>254){runOnUiThread(()->{busy(false);showError(tr("الرقم غير صالح","Invalid number"),tr("استخدم رقمًا من 2 إلى 254.","Use a number from 2 to 254."));});return null;}
        try{byte[]b=n.ip.getAddress();int me=b[3]&255;int gw=n.gw.getAddress()[3]&255;if(last==me||last==gw){runOnUiThread(()->{busy(false);showError(tr("لا يمكن استخدام هذا الرقم","This number cannot be used"),tr("هذا الرقم خاص بالتابلت أو الراوتر. اختر رقمًا آخر.","This number belongs to the tablet or router. Choose another."));});return null;}return(Inet4Address)InetAddress.getByAddress(new byte[]{b[0],b[1],b[2],(byte)last});}catch(Exception e){return null;}
    }

    private boolean sendIpWithRetry(UsbDevice device,Inet4Address target,String targetIp){byte[]cmd=setIpCommand(target);for(int attempt=1;attempt<=3;attempt++){try(UsbSession s=openUsb(device)){if(s!=null){int r=s.connection.bulkTransfer(s.out,cmd,cmd.length,2500);if(r==cmd.length)return true;}}catch(Exception ignored){}if(networkResponds(targetIp,1200))return true;sleep(500);}return networkResponds(targetIp,2200);}
    private static byte[] setIpCommand(Inet4Address a){byte[]b=a.getAddress();return new byte[]{0x1F,0x1B,0x1F,(byte)0x91,0x00,0x49,0x50,b[0],b[1],b[2],b[3]};}

    private void verifyLater(){worker.execute(()->{sleep(3000);verifyPendingInternal(false);});}
    private void verifyPending(boolean fromButton){if(!hasPending()){String v=prefs.getString("verified_ip",null);if(v!=null)showSuccess(tr("IP مؤكد","IP verified"),v+"  •  LAN ✓");else showNeutral(tr("لا يوجد IP بانتظار التحقق","No pending IP"),tr("اضبط IP أولاً.","Set an IP first."));return;}if(fromButton){busy(true);showNeutral(tr("جاري التحقق عبر LAN…","Verifying over LAN…"),tr("USB غير مطلوب الآن.","USB is not required now."));}worker.execute(()->verifyPendingInternal(fromButton));}
    private void verifyPendingInternal(boolean fromButton){String ip=prefs.getString("pending_ip",null);if(ip==null){runOnUiThread(()->busy(false));return;}boolean ok=networkResponds(ip,fromButton?25000:9000);if(ok)markVerified(ip);else if(fromButton)runOnUiThread(()->{busy(false);refreshAll();showWarning(tr("الطابعة لم تظهر على الشبكة بعد","Printer not visible on the network yet"),tr("أعد تشغيل الطابعة وتأكد أن LAN موصول بنفس الراوتر.\nIP الجاري اختباره: ","Restart the printer and confirm LAN is on the same router.\nIP being checked: ")+ip);});}
    private void markVerified(String ip){prefs.edit().putString("verified_ip",ip).remove("pending_ip").apply();runOnUiThread(()->{busy(false);refreshAll();showSuccess(tr("تم تغيير IP بنجاح ✓","IP changed successfully ✓"),tr("IP الطابعة: ","Printer IP: ")+ip+"\nLAN ✓");});}

    private UsbDevice findPrinter(){if(usb==null)return null;UsbDevice best=null;int bestScore=-999;for(UsbDevice d:usb.getDeviceList().values()){int score=0;if(d.getVendorId()==0x0483&&d.getProductId()==0x5743)score+=500;for(int i=0;i<d.getInterfaceCount();i++){UsbInterface f=d.getInterface(i);if(f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER)score+=100;for(int e=0;e<f.getEndpointCount();e++){UsbEndpoint ep=f.getEndpoint(e);if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK&&ep.getDirection()==UsbConstants.USB_DIR_OUT)score+=10;}}if(score>bestScore){bestScore=score;best=d;}}return bestScore>=10?best:null;}
    private UsbSession openUsb(UsbDevice d){UsbInterface chosen=null;UsbEndpoint out=null;for(int i=0;i<d.getInterfaceCount();i++){UsbInterface f=d.getInterface(i);UsbEndpoint candidate=null;for(int e=0;e<f.getEndpointCount();e++){UsbEndpoint ep=f.getEndpoint(e);if(ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK&&ep.getDirection()==UsbConstants.USB_DIR_OUT)candidate=ep;}if(candidate!=null){chosen=f;out=candidate;if(f.getInterfaceClass()==UsbConstants.USB_CLASS_PRINTER)break;}}if(chosen==null||out==null)return null;UsbDeviceConnection c=usb.openDevice(d);if(c==null)return null;if(!c.claimInterface(chosen,true)){c.close();return null;}return new UsbSession(c,chosen,out);}
    private static final class UsbSession implements Closeable{final UsbDeviceConnection connection;final UsbInterface intf;final UsbEndpoint out;UsbSession(UsbDeviceConnection c,UsbInterface f,UsbEndpoint o){connection=c;intf=f;out=o;}@Override public void close(){try{connection.releaseInterface(intf);}catch(Exception ignored){}connection.close();}}

    private NetInfo netInfo(){ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return null;Network nw=cm.getActiveNetwork();if(nw==null)return null;LinkProperties lp=cm.getLinkProperties(nw);if(lp==null)return null;Inet4Address ip=null,gw=null;int prefix=24;for(LinkAddress a:lp.getLinkAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()){ip=(Inet4Address)a.getAddress();prefix=a.getPrefixLength();break;}if(ip==null)return null;for(RouteInfo r:lp.getRoutes())if(r.isDefaultRoute()&&r.getGateway() instanceof Inet4Address){gw=(Inet4Address)r.getGateway();break;}if(gw==null)try{long network=ipToLong(ip)&maskForPrefix(prefix);gw=longToIp(network+1);}catch(Exception ignored){}return gw==null?null:new NetInfo(ip,gw,prefix);}
    private static final class NetInfo{final Inet4Address ip,gw;final int prefix;NetInfo(Inet4Address i,Inet4Address g,int p){ip=i;gw=g;prefix=p;}}

    private Inet4Address chooseNearestFree(NetInfo n){try{long ip=ipToLong(n.ip),mask=maskForPrefix(n.prefix),network=ip&mask,broadcast=network|(~mask&0xFFFFFFFFL),gw=ipToLong(n.gw);int checked=0;for(long v=ip+1;v<broadcast&&checked<120;v++,checked++){if(v==gw)continue;Inet4Address a=longToIp(v);if(!inUseStrong(a))return a;}for(long v=network+2;v<ip&&checked<260;v++,checked++){if(v==gw)continue;Inet4Address a=longToIp(v);if(!inUseStrong(a))return a;}}catch(Exception ignored){}return null;}
    private boolean inUseStrong(Inet4Address a){for(int round=0;round<2;round++){String h=a.getHostAddress();if(portOpen(h,9100,180)||portOpen(h,80,150)||portOpen(h,515,150)||portOpen(h,631,150))return true;try{if(a.isReachable(220))return true;}catch(IOException ignored){}sleep(120);}return false;}
    private boolean networkResponds(String host,int totalMs){long end=System.currentTimeMillis()+totalMs;while(System.currentTimeMillis()<end){if(portOpen(host,9100,350)||portOpen(host,80,250)||portOpen(host,515,250)||portOpen(host,631,250))return true;sleep(500);}return false;}
    private static boolean portOpen(String host,int port,int timeout){try(Socket s=new Socket()){s.connect(new InetSocketAddress(host,port),timeout);return true;}catch(IOException e){return false;}}
    private static long ipToLong(Inet4Address a){byte[]b=a.getAddress();return((long)(b[0]&255)<<24)|((long)(b[1]&255)<<16)|((long)(b[2]&255)<<8)|(long)(b[3]&255);}
    private static Inet4Address longToIp(long v)throws Exception{return(Inet4Address)InetAddress.getByAddress(new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v});}
    private static long maskForPrefix(int p){if(p<=0)return 0;if(p>=32)return 0xFFFFFFFFL;return(0xFFFFFFFFL<<(32-p))&0xFFFFFFFFL;}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private boolean hasPending(){return prefs.getString("pending_ip",null)!=null;}

    private void busy(boolean b){runOnUiThread(()->{setBtn.setEnabled(!b);verifyBtn.setEnabled(!b);changeAgainBtn.setEnabled(!b);autoBtn.setEnabled(!b);manualBtn.setEnabled(!b);guideBtn.setEnabled(!b);langBtn.setEnabled(!b);});}
    private void fail(String d){runOnUiThread(()->{busy(false);showError(tr("تعذر ضبط IP","Could not set IP"),d);});}
    private void showNeutral(String t,String d){showStatus(0,t,d);}private void showSuccess(String t,String d){showStatus(1,t,d);}private void showError(String t,String d){showStatus(2,t,d);}private void showWarning(String t,String d){showStatus(3,t,d);}
    private void showStatus(int kind,String t,String d){runOnUiThread(()->{if(statusCard==null||statusCard.getVisibility()!=View.VISIBLE)return;statusTitle.setText(t);statusText.setText(d);int bgc=kind==1?0xFFE8F7EF:kind==2?0xFFFCEBED:kind==3?0xFFFFF5DD:0xFFF0EFF4;int fg=kind==1?0xFF168A55:kind==2?0xFFC73B45:kind==3?0xFF9A6700:0xFF211B34;statusCard.setBackground(round(bgc,18));statusTitle.setTextColor(fg);});}

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
    private Button modeButton(String s){Button b=secondary(s);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return b;}
}
