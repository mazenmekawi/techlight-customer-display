package sa.techlight.customerdisplay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PairingParser {
    private static final Pattern IPV4 = Pattern.compile("((?:\\d{1,3}\\.){3}\\d{1,3})");
    private static final Pattern PORT = Pattern.compile("(?i)(?:port\\s*[:=]\\s*|:(\\s*))([0-9]{2,5})");

    public static PairingInfo parse(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Empty QR");
        String value = normalize(raw.trim());

        PairingInfo json = parseJson(value);
        if (json != null) return json;

        PairingInfo uri = parseUri(value);
        if (uri != null) return uri;

        Matcher ipMatch = IPV4.matcher(value);
        if (ipMatch.find()) {
            String host = ipMatch.group(1);
            int port = 4040;
            Matcher portMatch = PORT.matcher(value.substring(ipMatch.end()));
            if (portMatch.find()) port = Integer.parseInt(portMatch.group(2));
            return checked(host, port);
        }
        throw new IllegalArgumentException("Unsupported pairing QR");
    }

    private static String normalize(String value) {
        String normalized = value.replace("\\\"", "\"").trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 2) {
            try {
                return new JSONArray("[" + normalized + "]").getString(0).trim();
            } catch (Exception ignored) {
                return normalized.substring(1, normalized.length() - 1).trim();
            }
        }
        return normalized;
    }

    private static PairingInfo parseJson(String value) {
        try {
            JSONObject object = new JSONObject(value);
            JSONObject pairing = object.optJSONObject("pairing");
            if (pairing != null) object = pairing;
            String host = firstText(object, "ip", "host", "address");
            if (host == null) return null;
            int port = object.optInt("port", 4040);
            if (port == 0) {
                try { port = Integer.parseInt(object.optString("port", "4040")); }
                catch (Exception ignored) { port = 4040; }
            }
            return checked(host, port);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PairingInfo parseUri(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            int port = uri.getPort();
            String query = uri.getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    String[] entry = part.split("=", 2);
                    if (entry.length != 2) continue;
                    String key = URLDecoder.decode(entry[0], "UTF-8");
                    String content = URLDecoder.decode(entry[1], "UTF-8");
                    if (("ip".equalsIgnoreCase(key) || "host".equalsIgnoreCase(key)) && !content.isEmpty()) host = content;
                    if ("port".equalsIgnoreCase(key)) port = Integer.parseInt(content);
                }
            }
            if (host == null || host.isEmpty()) return null;
            return checked(host, port > 0 ? port : 4040);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private static PairingInfo checked(String host, int port) {
        String cleanHost = host.trim().replace("ws://", "").replace("wss://", "");
        int slash = cleanHost.indexOf('/');
        if (slash >= 0) cleanHost = cleanHost.substring(0, slash);
        if (cleanHost.isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid address");
        return new PairingInfo(cleanHost, port);
    }

    public static final class PairingInfo {
        public final String ip;
        public final int port;
        public PairingInfo(String ip, int port) { this.ip = ip; this.port = port; }
        public String toString() { return ip + ":" + port; }
    }
}
