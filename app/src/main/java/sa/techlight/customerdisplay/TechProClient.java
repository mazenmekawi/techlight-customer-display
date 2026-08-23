package sa.techlight.customerdisplay;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class TechProClient {
    public interface Listener {
        void onConnected(); void onDisconnected(String reason);
        void onOrder(OrderState order); void onRaw(String raw);
    }
    private final String host; private final int port; private final Listener listener;
    private volatile boolean running; private Socket socket; private Thread thread;
    private final Handler main = new Handler(Looper.getMainLooper());

    public TechProClient(String host, int port, Listener listener){
        this.host=host; this.port=port; this.listener=listener;
    }
    public void start(){
        if(running) return; running=true;
        thread = new Thread(this::loop, "TechProDisplay"); thread.start();
    }
    public void stop(){ running=false; try{ if(socket!=null) socket.close(); }catch(Exception ignored){} }
    private void loop(){
        while(running){
            try{
                socket = new Socket(); socket.connect(new InetSocketAddress(host,port),3000);
                socket.setKeepAlive(true); socket.setSoTimeout(0);
                main.post(listener::onConnected);
                BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while(running && (line=br.readLine())!=null){
                    final String raw=line; main.post(() -> listener.onRaw(raw));
                    OrderState o=parseOrder(raw); if(o!=null) main.post(() -> listener.onOrder(o));
                }
                throw new EOFException("connection closed");
            } catch(Exception e){
                final String msg=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());
                main.post(() -> listener.onDisconnected(msg));
                try{ Thread.sleep(2000); }catch(InterruptedException ignored){}
            }
        }
    }
    private OrderState parseOrder(String raw){
        try{
            JSONObject root = new JSONObject(raw);
            JSONObject data = root.optJSONObject("order"); if(data==null) data=root;
            JSONArray arr = data.optJSONArray("items"); if(arr==null) return null;
            OrderState out=new OrderState();
            for(int i=0;i<arr.length();i++){
                JSONObject x=arr.getJSONObject(i); OrderState.Item item=new OrderState.Item();
                item.name=x.optString("name", x.optString("product_name","Item"));
                item.qty=x.optInt("qty", x.optInt("quantity",1));
                item.unitPrice=x.optDouble("price", x.optDouble("unit_price",0));
                out.items.add(item);
            }
            out.subtotal=data.optDouble("subtotal",0); out.tax=data.optDouble("tax",0);
            out.discount=data.optDouble("discount",0); out.total=data.optDouble("total",0);
            out.completed=data.optBoolean("completed", false) || "completed".equalsIgnoreCase(data.optString("status"));
            return out;
        }catch(Exception ignored){ return null; }
    }
}
