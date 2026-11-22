package com.akler.routermonitor;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to an ASUS (Asuswrt) router the same way the official ASUS Router app does:
 * log in to /login.cgi for a token, then read /appGet.cgi and /getTraffic.asp.
 */
public class RouterClient {

    /** Thrown when the router rejects the username or password. */
    public static class AuthException extends Exception {
        public AuthException(String msg) { super(msg); }
    }

    public static class Device {
        public String mac;
        public String name;
        public String ip;
        public boolean online;
        /** "0" = wired, "1" = 2.4GHz, "2" = 5GHz, "3" = 5GHz-2 / 6GHz */
        public String isWL;
        public String rssi;
    }

    /** Cumulative byte counters for one client, as reported by the router. */
    public static class Counter {
        public long tx;
        public long rx;
    }

    private static final String USER_AGENT = "asusrouter-Android-DUTUtil-1.0.0.245";
    private static final Pattern TRAFFIC = Pattern.compile("array_traffic\\s*=\\s*(\\[.*?\\])\\s*;", Pattern.DOTALL);

    private final String base;
    private final String user;
    private final String pass;
    private String token;

    public RouterClient(String address, String user, String pass) {
        String a = address.trim();
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        this.base = a;
        this.user = user;
        this.pass = pass;
    }

    public void login() throws Exception {
        String auth = Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String body = "login_authorization=" + URLEncoder.encode(auth, "UTF-8");
        HttpURLConnection c = open("/login.cgi", false);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        String text = read(c);

        String t = null;
        try {
            JSONObject j = new JSONObject(text);
            t = j.optString("asus_token", null);
            if (t == null || t.isEmpty()) {
                String status = j.optString("error_status", "");
                if ("3".equals(status)) throw new AuthException("שם משתמש או סיסמה שגויים");
                if ("7".equals(status) || "10".equals(status))
                    throw new AuthException("הראוטר חסם זמנית ניסיונות התחברות. נסה שוב בעוד כמה דקות");
                if (!status.isEmpty()) throw new AuthException("הראוטר דחה את ההתחברות (קוד " + status + ")");
            }
        } catch (AuthException e) {
            throw e;
        } catch (Exception ignored) {
            // not JSON - fall back to the cookie below
        }
        if (t == null || t.isEmpty()) {
            List<String> cookies = c.getHeaderFields().get("Set-Cookie");
            if (cookies != null) {
                for (String ck : cookies) {
                    Matcher m = Pattern.compile("asus_token=([^;]+)").matcher(ck);
                    if (m.find()) t = m.group(1);
                }
            }
        }
        if (t == null || t.isEmpty()) throw new AuthException("ההתחברות לראוטר נכשלה");
        token = t;
    }

    /** All clients the router knows, keyed by upper-case MAC. */
    public Map<String, Device> getClients() throws Exception {
        String text = request("/appGet.cgi", "hook=" + URLEncoder.encode("get_clientlist();", "UTF-8"));
        JSONObject root = new JSONObject(text);
        JSONObject list = root.optJSONObject("get_clientlist");
        Map<String, Device> out = new HashMap<>();
        if (list == null) return out;
        Iterator<String> keys = list.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject o = list.optJSONObject(k);
            if (o == null || !o.has("mac")) continue;
            Device d = new Device();
            d.mac = o.optString("mac", k).toUpperCase(Locale.ROOT);
            String nick = o.optString("nickName", "").trim();
            String name = o.optString("name", "").trim();
            d.name = !nick.isEmpty() ? nick : (!name.isEmpty() ? name : d.mac);
            d.ip = o.optString("ip", "");
            d.online = "1".equals(o.optString("isOnline", "0"));
            d.isWL = o.optString("isWL", "0");
            d.rssi = o.optString("rssi", "");
            out.put(d.mac, d);
        }
        return out;
    }

    /**
     * Per-client byte counters from the router's Bandwidth Monitor (needs Adaptive QoS /
     * Traffic Analyzer turned on). Returns an empty map if the router has no data.
     */
    public Map<String, Counter> getTraffic() throws Exception {
        String text = request("/getTraffic.asp", null);
        Map<String, Counter> out = new HashMap<>();
        Matcher m = TRAFFIC.matcher(text);
        if (!m.find()) return out;
        JSONArray arr;
        try {
            arr = new JSONArray(m.group(1));
        } catch (Exception e) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONArray row = arr.optJSONArray(i);
            if (row == null || row.length() < 3) continue;
            String mac = row.optString(0, "").toUpperCase(Locale.ROOT);
            if (mac.isEmpty()) continue;
            Counter c = new Counter();
            c.tx = parseLong(row.opt(1));
            c.rx = parseLong(row.opt(2));
            out.put(mac, c);
        }
        return out;
    }

    private static long parseLong(Object o) {
        if (o == null) return 0;
        try {
            return (long) Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** GET (body == null) or POST, logging in first / again when needed. */
    private String request(String path, String body) throws Exception {
        if (token == null) login();
        String text = doRequest(path, body);
        if (looksLoggedOut(text)) {
            login();
            text = doRequest(path, body);
            if (looksLoggedOut(text)) throw new AuthException("הראוטר ניתק את ההתחברות");
        }
        return text;
    }

    private static boolean looksLoggedOut(String text) {
        return text == null
                || text.contains("Main_Login.asp")
                || text.contains("\"error_status\"")
                || text.contains("top.location.href='/'");
    }

    private String doRequest(String path, String body) throws IOException {
        HttpURLConnection c = open(path, true);
        if (body != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        if (code == 401 || code == 403 || (code >= 300 && code < 400)) return null;
        return read(c);
    }

    private HttpURLConnection open(String path, boolean withToken) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Referer", base + "/");
        if (withToken && token != null) c.setRequestProperty("Cookie", "asus_token=" + token);
        return c;
    }

    private static String read(HttpURLConnection c) throws IOException {
        InputStream in;
        try {
            in = c.getInputStream();
        } catch (IOException e) {
            in = c.getErrorStream();
            if (in == null) throw e;
        }
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
