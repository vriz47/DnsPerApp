package com.dnsperapp;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toolbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AppsActivity extends Activity {

    private final List<App> all = new ArrayList<>();
    private final List<App> apps = new ArrayList<>();
    private AppAdapter adapter;
    private ListView list;
    private TextView txtCount;
    private String query = "";

    private static final class App {
        final String pkg;
        String label;
        Drawable icon;
        App(String pkg) { this.pkg = pkg; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);

        getWindow().setStatusBarColor(0x00000000);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        list = findViewById(R.id.listApps);
        TextView empty = findViewById(R.id.txtEmpty);
        list.setEmptyView(empty);
        txtCount = findViewById(R.id.txtCount);
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        EditText search = findViewById(R.id.editSearch);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                query = s.toString().trim().toLowerCase();
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) { }
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
    }

    private void loadApps() {
        all.clear();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : installed) {
            if (ai.packageName.equals(getPackageName())) continue;
            if (ai.uid < 10000) continue;
            if (!isLauncherApp(ai)) continue;
            App a = new App(ai.packageName);
            a.label = ai.loadLabel(pm).toString();
            a.icon = ai.loadIcon(pm);
            all.add(a);
        }
        Collections.sort(all, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                boolean ea = Prefs.isEnabled(AppsActivity.this, a.pkg);
                boolean eb = Prefs.isEnabled(AppsActivity.this, b.pkg);
                if (ea != eb) return ea ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        applyFilter();
    }

    private void applyFilter() {
        apps.clear();
        for (App a : all) {
            if (query.isEmpty()
                    || a.label.toLowerCase(java.util.Locale.US).contains(query)
                    || a.pkg.toLowerCase(java.util.Locale.US).contains(query)) {
                apps.add(a);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        if (txtCount == null) return;
        int enabled = Prefs.enabledSet(this).size();
        txtCount.setText(apps.size() + " aplikasi ditampilkan · " + enabled + " terpilih");
    }

    private boolean isLauncherApp(ApplicationInfo ai) {
        try {
            return getPackageManager().getLaunchIntentForPackage(ai.packageName) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private class AppAdapter extends BaseAdapter {
        private final String[] options;
        private final ArrayAdapter<String> spinnerAdapter;

        AppAdapter() {
            options = new String[DnsServers.NAMES.length + 1];
            options[0] = "Global";
            System.arraycopy(DnsServers.NAMES, 0, options, 1, DnsServers.NAMES.length);
            spinnerAdapter = new ArrayAdapter<>(AppsActivity.this,
                    android.R.layout.simple_spinner_item, options);
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public int getCount() { return apps.size(); }

        @Override
        public Object getItem(int position) { return apps.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            final App app = apps.get(position);
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_app, parent, false);
            }
            ((ImageView) v.findViewById(R.id.imgIcon)).setImageDrawable(app.icon);
            ((TextView) v.findViewById(R.id.txtLabel)).setText(app.label);
            ((TextView) v.findViewById(R.id.txtPkg)).setText(app.pkg);

            final Switch sw = v.findViewById(R.id.sw);
            final Spinner sp = v.findViewById(R.id.spServer);
            final TextView txtRule = v.findViewById(R.id.txtRule);

            sp.setAdapter(spinnerAdapter);

            final boolean enabled = Prefs.isEnabled(AppsActivity.this, app.pkg);
            int serverIdx = Prefs.packageServer(AppsActivity.this, app.pkg);
            final int spinnerSel = (serverIdx < 0 ? 0 : serverIdx + 1);

            sw.setOnCheckedChangeListener(null);
            sw.setChecked(enabled);
            sp.setEnabled(enabled);
            sp.setSelection(spinnerSel);
            sp.setOnItemSelectedListener(null);
            refreshRule(txtRule, enabled, spinnerSel);

            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    sp.setEnabled(isChecked);
                    if (isChecked) {
                        int sel = sp.getSelectedItemPosition();
                        int idx = sel >= 1 ? sel - 1 : -1;
                        Prefs.setPackageServer(AppsActivity.this, app.pkg, idx);
                        refreshRule(txtRule, true, sel);
                    } else {
                        Prefs.setPackageServer(AppsActivity.this, app.pkg, -2);
                        refreshRule(txtRule, false, 0);
                    }
                    onConfigChanged();
                    updateCount();
                }
            });
            sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (!Prefs.isEnabled(AppsActivity.this, app.pkg)) return;
                    Prefs.setPackageServer(AppsActivity.this, app.pkg, pos >= 1 ? pos - 1 : -1);
                    refreshRule(txtRule, true, pos);
                    onConfigChanged();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) { }
            });
            return v;
        }

        private void refreshRule(TextView tv, boolean enabled, int spinnerSel) {
            if (!enabled) {
                tv.setText(R.string.rule_disabled);
                tv.setTextColor(resColor(R.color.text_muted));
            } else if (spinnerSel >= 1 && spinnerSel <= DnsServers.NAMES.length) {
                tv.setText("→ " + DnsServers.NAMES[spinnerSel - 1]);
                tv.setTextColor(resColor(R.color.primary));
            } else if (spinnerSel >= 1) {
                tv.setText(R.string.rule_custom);
                tv.setTextColor(resColor(R.color.primary));
            } else {
                tv.setText(R.string.rule_global);
                tv.setTextColor(resColor(R.color.text_secondary));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private int resColor(int res) {
        return getResources().getColor(res);
    }

    /** Called when user changes per-app rules while a session is running. */
    private void onConfigChanged() {
        if (VpnDnsService.isRunning()) {
            VpnDnsService.requestRebuild();
        } else if (RootDns.isRunning()) {
            RootDns.reapply(this);
        }
    }
}