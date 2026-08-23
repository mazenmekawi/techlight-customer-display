package sa.techlight.customerdisplay;

import org.json.JSONObject;

public final class PairingParser {
    public static PairingInfo parse(String raw) throws Exception {
        JSONObject o = new JSONObject(raw.trim());
        if (!"pos_pair".equals(o.optString("type"))) throw new IllegalArgumentException("Invalid QR type");
        String ip = o.getString("ip");
        int port = o.optInt("port", 4040);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        return new PairingInfo(ip, port);
    }
    public static final class PairingInfo {
        public final String ip; public final int port;
        public PairingInfo(String ip, int port) { this.ip = ip; this.port = port; }
        public String toString() { return ip + ":" + port; }
    }
}
