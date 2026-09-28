package com.skipads;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView serviceStatus;
    private TextView targetApp;
    private TextView lastActions;
    private SharedPreferences prefs;

    private final SharedPreferences.OnSharedPreferenceChangeListener logListener = (p, key) -> {
        if (Prefs.KEY_LOG.equals(key)) runOnUiThread(this::refreshLog);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);

        serviceStatus = findViewById(R.id.service_status);
        targetApp = findViewById(R.id.target_app);
        lastActions = findViewById(R.id.last_actions);

        Button openAccessibility = findViewById(R.id.open_accessibility);
        openAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.choose_target).setOnClickListener(v -> chooseTargetApp());

        Switch enabled = findViewById(R.id.enabled_switch);
        enabled.setChecked(Prefs.isEnabled(this));
        enabled.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Prefs.KEY_ENABLED, checked).apply());

        Switch ocr = findViewById(R.id.ocr_switch);
        ocr.setChecked(Prefs.isOcrEnabled(this));
        ocr.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(Prefs.KEY_OCR, checked).apply());

        EditText keywords = findViewById(R.id.extra_keywords);
        keywords.setText(Prefs.extraKeywords(this));
        findViewById(R.id.save_keywords).setOnClickListener(v -> {
            prefs.edit().putString(Prefs.KEY_EXTRA_KEYWORDS, keywords.getText().toString()).apply();
            Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        serviceStatus.setText(isServiceEnabled()
                ? "無障礙服務：已開啟 ✅"
                : "無障礙服務：未開啟 ❌（請按下方按鈕，找到「SkipAds 自動跳廣告」並開啟）");
        refreshTarget();
        refreshLog();
        prefs.registerOnSharedPreferenceChangeListener(logListener);
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(logListener);
        super.onPause();
    }

    private void refreshTarget() {
        String pkg = Prefs.targetPackage(this);
        targetApp.setText("目標 App：" + (pkg == null ? "（尚未選擇）" : labelOf(pkg) + "\n" + pkg));
    }

    private void refreshLog() {
        lastActions.setText(prefs.getString(Prefs.KEY_LOG, ""));
    }

    private String labelOf(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void chooseTargetApp() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);

        final List<String> packages = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            rows.add(new String[]{ri.loadLabel(pm).toString(), pkg});
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        rows.sort((a, b) -> collator.compare(a[0], b[0]));
        for (String[] row : rows) {
            if (packages.contains(row[1])) continue;
            labels.add(row[0]);
            packages.add(row[1]);
        }

        new AlertDialog.Builder(this)
                .setTitle("選擇要自動跳廣告的 App")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    prefs.edit().putString(Prefs.KEY_TARGET, packages.get(which)).apply();
                    refreshTarget();
                })
                .show();
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName me = new ComponentName(this, AdSkipService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String s : splitter) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }
}
