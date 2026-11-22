package com.akler.routermonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int MENU_SETTINGS = 1;
    private static final int MENU_SORT = 2;
    private static final int REQ_SETTINGS = 10;
    private static final long CLIENTS_REFRESH_MS = 15000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private RouterClient client;
    private int intervalSec = 3;
    private boolean running;
    private boolean busy;
    private boolean sortByName;

    private Map<String, RouterClient.Device> devices = new HashMap<>();
    private long devicesTime;
    private Map<String, RouterClient.Counter> prevTraffic;
    private long prevTrafficTime;
    /** bits per second: [0] = download, [1] = upload */
    private final Map<String, double[]> rates = new HashMap<>();
    private boolean trafficAvailable = true;

    private TextView totalDown, totalUp, countText, statusText;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Color.parseColor("#87CEEB"));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        // Summary card
        LinearLayout card = card();
        LinearLayout totals = new LinearLayout(this);
        totals.setOrientation(LinearLayout.HORIZONTAL);
        totalDown = bigNumber("#006994");
        totalUp = bigNumber("#006994");
        totals.addView(column("הורדה ↓", totalDown), new LinearLayout.LayoutParams(0, -2, 1));
        totals.addView(column("העלאה ↑", totalUp), new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(totals);
        countText = text(15, "#006994", true);
        countText.setPadding(0, dp(8), 0, 0);
        card.addView(countText);
        statusText = text(12, "#006994", false);
        card.addView(statusText);
        root.addView(card);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.topMargin = dp(10);
        root.addView(sv, lp);

        setContentView(root);
        statusText.setText("מתחבר...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences p = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        String pass = p.getString(SettingsActivity.KEY_PASS, "");
        if (pass.isEmpty()) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
            return;
        }
        if (client == null) {
            client = new RouterClient(
                    p.getString(SettingsActivity.KEY_ADDRESS, "192.168.1.1"),
                    p.getString(SettingsActivity.KEY_USER, "admin"),
                    pass);
            prevTraffic = null;
            devicesTime = 0;
        }
        intervalSec = p.getInt(SettingsActivity.KEY_INTERVAL, 3);
        running = true;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        main.removeCallbacks(tick);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS && resultCode == RESULT_OK) {
            client = null; // reconnect with the new settings
            rates.clear();
            devices = new HashMap<>();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SORT, 0, "מיון").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, MENU_SETTINGS, 1, "הגדרות").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
            return true;
        }
        if (item.getItemId() == MENU_SORT) {
            sortByName = !sortByName;
            render();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!busy && client != null) poll();
            main.postDelayed(this, intervalSec * 1000L);
        }
    };

    private void poll() {
        busy = true;
        final RouterClient c = client;
        final boolean needClients = System.currentTimeMillis() - devicesTime > CLIENTS_REFRESH_MS;
        worker.execute(() -> {
            Map<String, RouterClient.Device> newDevices = null;
            Map<String, RouterClient.Counter> traffic = null;
            String error = null;
            try {
                if (needClients) newDevices = c.getClients();
                traffic = c.getTraffic();
            } catch (RouterClient.AuthException e) {
                error = e.getMessage();
            } catch (UnknownHostException | ConnectException | NoRouteToHostException | SocketTimeoutException e) {
                error = "אין חיבור לראוטר. ודא שהטלפון מחובר ל-Wi-Fi של הבית ושהכתובת בהגדרות נכונה";
            } catch (Exception e) {
                error = "שגיאה: " + e.getClass().getSimpleName() + (e.getMessage() != null ? " - " + e.getMessage() : "");
            }
            final Map<String, RouterClient.Device> fd = newDevices;
            final Map<String, RouterClient.Counter> ft = traffic;
            final String fe = error;
            main.post(() -> {
                busy = false;
                if (c != client) return; // settings changed meanwhile
                if (fe != null) {
                    statusText.setText(fe);
                    statusText.setTextColor(Color.parseColor("#C62828"));
                    return;
                }
                if (fd != null) {
                    devices = fd;
                    devicesTime = System.currentTimeMillis();
                }
                updateRates(ft);
                statusText.setTextColor(Color.parseColor("#006994"));
                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                statusText.setText(trafficAvailable
                        ? "עודכן " + time
                        : "עודכן " + time + "\nאין נתוני רוחב פס. בראוטר צריך להפעיל Adaptive QoS או Traffic Analyzer");
                render();
            });
        });
    }

    private void updateRates(Map<String, RouterClient.Counter> traffic) {
        long now = System.currentTimeMillis();
        trafficAvailable = traffic != null && !traffic.isEmpty();
        if (traffic == null) return;
        if (prevTraffic != null) {
            double sec = (now - prevTrafficTime) / 1000.0;
            if (sec > 0.5) {
                rates.clear();
                for (Map.Entry<String, RouterClient.Counter> e : traffic.entrySet()) {
                    RouterClient.Counter old = prevTraffic.get(e.getKey());
                    if (old == null) continue;
                    long dTx = e.getValue().tx - old.tx;
                    long dRx = e.getValue().rx - old.rx;
                    if (dTx < 0) dTx = 0;
                    if (dRx < 0) dRx = 0;
                    // tx = upload from the device, rx = download to the device (as in the router's own page)
                    rates.put(e.getKey(), new double[]{dRx * 8 / sec, dTx * 8 / sec});
                }
            }
        }
        prevTraffic = traffic;
        prevTrafficTime = now;
    }

    private void render() {
        // Devices to show: online ones, plus anything that is moving traffic
        Set<String> macs = new HashSet<>();
        for (RouterClient.Device d : devices.values()) if (d.online) macs.add(d.mac);
        for (Map.Entry<String, double[]> e : rates.entrySet()) {
            if (e.getValue()[0] + e.getValue()[1] > 0) macs.add(e.getKey());
        }

        List<String> order = new ArrayList<>(macs);
        order.sort((a, b) -> {
            if (sortByName) return nameOf(a).compareToIgnoreCase(nameOf(b));
            double ra = total(a), rb = total(b);
            if (ra != rb) return Double.compare(rb, ra);
            return nameOf(a).compareToIgnoreCase(nameOf(b));
        });

        double down = 0, up = 0;
        for (double[] r : rates.values()) { down += r[0]; up += r[1]; }
        totalDown.setText(formatRate(down));
        totalUp.setText(formatRate(up));
        countText.setText(order.size() + " מכשירים מחוברים" + (sortByName ? " · לפי שם" : " · לפי צריכה"));

        list.removeAllViews();
        for (String mac : order) list.addView(row(mac));
    }

    private View row(final String mac) {
        RouterClient.Device d = devices.get(mac);
        double[] r = rates.get(mac);

        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(16, "#006994", true);
        name.setText(nameOf(mac));
        left.addView(name);
        TextView sub = text(12, "#006994", false);
        sub.setText((d != null && !d.ip.isEmpty() ? d.ip + " · " : "") + connection(d));
        sub.setTextDirection(View.TEXT_DIRECTION_RTL);
        left.addView(sub);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        TextView dn = text(14, "#006994", r != null && r[0] > 1_000_000);
        dn.setText("↓ " + (r == null ? "—" : formatRate(r[0])));
        TextView upv = text(14, "#006994", r != null && r[1] > 1_000_000);
        upv.setText("↑ " + (r == null ? "—" : formatRate(r[1])));
        right.addView(dn);
        right.addView(upv);
        row.addView(right);

        row.setOnClickListener(v -> showDetails(mac));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private void showDetails(String mac) {
        RouterClient.Device d = devices.get(mac);
        double[] r = rates.get(mac);
        StringBuilder sb = new StringBuilder();
        sb.append("MAC: ").append(mac).append('\n');
        if (d != null) {
            sb.append("IP: ").append(d.ip.isEmpty() ? "—" : d.ip).append('\n');
            sb.append("חיבור: ").append(connection(d)).append('\n');
            if (!d.rssi.isEmpty() && !"0".equals(d.rssi) && !"0".equals(d.isWL))
                sb.append("עוצמת אות: ").append(d.rssi).append(" dBm\n");
        }
        if (r != null) {
            sb.append("הורדה: ").append(formatRate(r[0])).append('\n');
            sb.append("העלאה: ").append(formatRate(r[1]));
        }
        new AlertDialog.Builder(this)
                .setTitle(nameOf(mac))
                .setMessage(sb.toString())
                .setPositiveButton("סגור", null)
                .show();
    }

    private String nameOf(String mac) {
        RouterClient.Device d = devices.get(mac);
        return d != null ? d.name : mac;
    }

    private double total(String mac) {
        double[] r = rates.get(mac);
        return r == null ? 0 : r[0] + r[1];
    }

    private static String connection(RouterClient.Device d) {
        if (d == null) return "לא ידוע";
        switch (d.isWL) {
            case "0": return "חוטי";
            case "1": return "Wi-Fi 2.4GHz";
            case "2": return "Wi-Fi 5GHz";
            case "3": return "Wi-Fi 5GHz-2/6GHz";
            default: return "Wi-Fi";
        }
    }

    private static String formatRate(double bps) {
        if (bps >= 1_000_000) return String.format(Locale.US, "%.1f Mbps", bps / 1_000_000);
        if (bps >= 1_000) return String.format(Locale.US, "%.0f Kbps", bps / 1_000);
        return String.format(Locale.US, "%.0f bps", bps);
    }

    // ---- small view helpers ----

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        l.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        l.setBackground(bg);
        l.setElevation(dp(1));
        return l;
    }

    private LinearLayout column(String label, TextView value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(13, "#006994", false);
        l.setText(label);
        c.addView(l);
        c.addView(value);
        return c;
    }

    private TextView bigNumber(String color) {
        TextView t = text(24, color, true);
        t.setText("—");
        return t;
    }

    private TextView text(int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
