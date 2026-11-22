package com.akler.routermonitor;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    public static final String PREFS = "router";
    public static final String KEY_ADDRESS = "address";
    public static final String KEY_USER = "user";
    public static final String KEY_PASS = "pass";
    public static final String KEY_INTERVAL = "interval";

    private EditText address, user, pass, interval;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        address = field(root, "כתובת הראוטר", p.getString(KEY_ADDRESS, "192.168.1.1"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = field(root, "שם משתמש", p.getString(KEY_USER, "admin"), InputType.TYPE_CLASS_TEXT);
        pass = field(root, "סיסמה", p.getString(KEY_PASS, ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        interval = field(root, "רענון כל (שניות)", String.valueOf(p.getInt(KEY_INTERVAL, 3)), InputType.TYPE_CLASS_NUMBER);

        TextView note = new TextView(this);
        note.setText("אלה פרטי הכניסה לממשק הניהול של הראוטר (אותם פרטים של אפליקציית ASUS Router). הם נשמרים רק בטלפון הזה.");
        note.setPadding(0, dp(12), 0, dp(12));
        root.addView(note);

        Button save = new Button(this);
        save.setText("שמור");
        save.setOnClickListener(v -> save());
        root.addView(save);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(android.graphics.Color.parseColor("#87CEEB"));
        sv.addView(root);
        setContentView(sv);
    }

    private void save() {
        String a = address.getText().toString().trim();
        String u = user.getText().toString().trim();
        String pw = pass.getText().toString();
        int sec;
        try {
            sec = Integer.parseInt(interval.getText().toString().trim());
        } catch (NumberFormatException e) {
            sec = 3;
        }
        if (sec < 2) sec = 2;
        if (sec > 60) sec = 60;
        if (a.isEmpty() || u.isEmpty() || pw.isEmpty()) {
            Toast.makeText(this, "צריך למלא כתובת, שם משתמש וסיסמה", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ADDRESS, a)
                .putString(KEY_USER, u)
                .putString(KEY_PASS, pw)
                .putInt(KEY_INTERVAL, sec)
                .apply();
        setResult(RESULT_OK);
        finish();
    }

    private EditText field(LinearLayout root, String label, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        l.setPadding(0, dp(10), 0, 0);
        root.addView(l);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        root.addView(e);
        return e;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
