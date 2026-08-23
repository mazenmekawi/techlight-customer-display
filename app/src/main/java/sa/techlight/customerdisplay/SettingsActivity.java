package sa.techlight.customerdisplay;

import android.app.*;import android.content.*;import android.graphics.Color;import android.net.Uri;import android.os.Bundle;import android.view.*;import android.widget.*;

public class SettingsActivity extends Activity {
  SharedPreferences p; EditText welcome,thanks,footer,color; Spinner template; ImageView logo; static final int PICK=42;
  @Override public void onCreate(Bundle b){super.onCreate(b);p=getSharedPreferences("ui",0);showPassword();}
  void showPassword(){ final EditText e=new EditText(this); e.setInputType(2); e.setHint("0000"); new AlertDialog.Builder(this).setTitle("رمز إعدادات ضوء التقنية").setView(e).setCancelable(false).setNegativeButton("إلغاء",(d,w)->finish()).setPositiveButton("دخول",null).setOnShowListener(d->{AlertDialog a=(AlertDialog)d;a.getButton(-1).setOnClickListener(v->{if("0000".equals(e.getText().toString())){a.dismiss();build();}else e.setError("الرمز غير صحيح");});}).show(); }
  TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(17);t.setTextColor(Color.DKGRAY);t.setPadding(0,16,0,6);return t;}
  EditText input(String val){EditText e=new EditText(this);e.setText(val);e.setTextSize(17);e.setSingleLine(true);return e;}
  void build(){
    ScrollView sv=new ScrollView(this); LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(40,26,40,40);r.setBackgroundColor(Color.WHITE);sv.addView(r);
    TextView h=label("إعدادات شاشة العميل");h.setTextSize(28);h.setTextColor(Color.rgb(91,42,134));r.addView(h);
    r.addView(label("التصميم"));template=new Spinner(this);String[] a={"تصميم 1 — حديث","تصميم 2 — كلاسيكي","تصميم 3 — بسيط"};template.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,a));template.setSelection(p.getInt("template",0));r.addView(template);
    r.addView(label("لون الهوية — مثال #5B2A86"));color=input(p.getString("color","#5B2A86"));r.addView(color);
    LinearLayout presets=new LinearLayout(this);String[] cs={"#5B2A86","#111827","#0F766E","#B42318","#1D4ED8"};for(String c:cs){Button b=new Button(this);b.setText("●");b.setTextColor(Color.parseColor(c));b.setOnClickListener(v->color.setText(c));presets.addView(b,new LinearLayout.LayoutParams(0,60,1));}r.addView(presets);
    r.addView(label("نص الترحيب"));welcome=input(p.getString("welcome","أهلًا وسهلًا بك"));r.addView(welcome);
    r.addView(label("نص الشكر بعد الدفع"));thanks=input(p.getString("thanks","شكرًا لزيارتكم"));r.addView(thanks);
    r.addView(label("النص السفلي"));footer=input(p.getString("footer","نسعد بخدمتكم دائمًا"));r.addView(footer);
    r.addView(label("شعار المطعم"));logo=new ImageView(this);logo.setAdjustViewBounds(true);logo.setMaxHeight(150);String u=p.getString("logo",null);if(u!=null)try{logo.setImageURI(Uri.parse(u));}catch(Exception ignored){}r.addView(logo,new LinearLayout.LayoutParams(-1,150));Button pick=new Button(this);pick.setText("اختيار شعار المطعم");pick.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);});r.addView(pick);
    Button save=new Button(this);save.setText("حفظ الإعدادات");save.setOnClickListener(v->{String c=color.getText().toString().trim();try{Color.parseColor(c);}catch(Exception e){color.setError("لون غير صحيح");return;}p.edit().putInt("template",template.getSelectedItemPosition()).putString("color",c).putString("welcome",welcome.getText().toString()).putString("thanks",thanks.getText().toString()).putString("footer",footer.getText().toString()).apply();setResult(RESULT_OK);finish();});r.addView(save);
    Button pair=new Button(this);pair.setText("إلغاء ربط Tech Pro وإعادة الاقتران");pair.setOnClickListener(v->{getSharedPreferences("pair",0).edit().clear().apply();Toast.makeText(this,"تم مسح الاقتران",Toast.LENGTH_SHORT).show();});r.addView(pair);
    setContentView(sv);
  }
  @Override protected void onActivityResult(int q,int c,Intent d){super.onActivityResult(q,c,d);if(q==PICK&&c==RESULT_OK&&d!=null){Uri u=d.getData();if(u!=null){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}p.edit().putString("logo",u.toString()).apply();logo.setImageURI(u);}}}
}
