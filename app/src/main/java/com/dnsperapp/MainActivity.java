package com.dnsperapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1001;

    private Spinner spinnerDns;
    private Spinner spinnerMode;
    private EditText editCustom;
    private LinearLayout layoutCustom;
    private TextView txtServerDesc;
    private TextView txtModeNote;
    private View dotStatus;
    private TextView txtStatus;
    private TextView txtAppsSummary;
    private TextView txtAppsList;
    private Button btnToggle;

    private boolean preparingUi = true;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().setStatusBarColor(0x00000000);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        spinnerDns = findViewById(R.id.spinnerDns);
        spinnerMode = findViewById(R.id.spinnerMode);
        editCustom = findViewById(R.id.editCustom);
        layoutCustom = findViewById(R.id.layoutCustom);
        txtServerDesc = findViewById(R.id.txtServerDesc);
        txtModeNote = findViewById(R.id.txtModeNote);
        dotStatus = findViewById(R.id.dotStatus);
        txtStatus = findViewById(R.id.txtStatus);
        txtAppsSummary = findViewById(R.id.txtAppsSummary);
        txtAppsList = findViewById(R.id.txtAppsList);
        btnToggle = findViewById(R.id.btnToggle);
        Button btnApps = findViewById(R.id.btnApps);

        spinnerDns.setAdapter(SpinnerAdapters.dns(this));
        spinnerMode.setAdapter(SpinnerAdapters.mode(this));
        spinnerMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (preparingUi) return;
                refreshModeNote(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        spinnerDns.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (preparingUi) return;
                layoutCustom.setVisibility(pos >= DnsServers.NAMES.length ? View.VISIBLE : View.GONE);
                updateServerDesc(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Controller.isRunning()) {
                    onStopClicked();
                } else {
                    onStartClicked();
                }
            }
        });
        btnApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppsActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(VpnDnsService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
        preparingUi = true;
        int s = Prefs.serverIndex(this);
        spinnerDns.setSelection(s);
        spinnerMode.setSelection(Prefs.modeRoot(this) ? 1 : 0);
        editCustom.setText(Prefs.customIp(this));
        editCustom.setVisibility(View.GONE);
        layoutCustom.setVisibility(s >= DnsServers.NAMES.length ? View.VISIBLE : View.GONE);
        preparingUi = false;
        refreshModeNote(spinnerMode.getSelectedItemPosition());
        updateServerDesc(s);
        refreshApps();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) { }
    }

    private void refreshModeNote(int modePos) {
        txtModeNote.setText(modePos == 1 ? R.string.mode_note_root : R.string.mode_note_vpn);
    }

    private void updateServerDesc(int idx) {
        if (idx >= 0 && idx < DnsServers.NAMES.length) {
            String tagline = idx == 0
                    ? "Memblokir iklan & tracking"
                    : (DnsServers.DOH_HOSTS[idx] != null ? "DoH: " + DnsServers.DOH_HOSTS[idx] : "Resolver publik");
            txtServerDesc.setText(tagline + "  ·  " + DnsServers.IPS[idx]);
        } else {
            txtServerDesc.setText("UDP:53 ke alamat custom (tanpa DoH)");
        }
    }

    private void refreshApps() {
        String[] pkgs = Prefs.enabledPackages(this);
        if (pkgs.length == 0) {
            txtAppsSummary.setText(R.string.apps_none);
            txtAppsList.setText(R.string.apps_pick_hint);
            return;
        }
        txtAppsSummary.setText(pkgs.length + " aplikasi terpilih");
        List<String> labels = new ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        for (String p : pkgs) {
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                labels.add(ai.loadLabel(pm).toString());
            } catch (Exception e) {
                labels.add(p);
            }
        }
        Collections.sort(labels);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size() && i < 3; i++) {
            if (i > 0) sb.append(", ");
            sb.append(labels.get(i));
        }
        if (labels.size() > 3) sb.append(" +").append(labels.size() - 3).append(" lainnya");
        txtAppsList.setText(sb.toString());
    }

    private boolean collectConfig() {
        int idx = spinnerDns.getSelectedItemPosition();
        if (idx >= DnsServers.NAMES.length) {
            String ip = editCustom.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, R.string.toast_custom_empty, Toast.LENGTH_LONG).show();
                return false;
            }
            Prefs.setCustomIp(this, ip);
        } else {
            Prefs.setCustomIp(this, "");
        }
        Prefs.setServerIndex(this, idx);
        Prefs.setMode(this, spinnerMode.getSelectedItemPosition() == 1);
        return true;
    }

    private void onStartClicked() {
        if (!collectConfig()) return;
        if (Prefs.enabledPackages(this).length == 0) {
            Toast.makeText(this, R.string.toast_need_apps, Toast.LENGTH_LONG).show();
            return;
        }
        Controller.stop(this);

        if (Prefs.modeVpn(this)) {
            Intent intent = VpnService.prepare(this);
            if (intent != null) {
                startActivityForResult(intent, REQ_VPN);
            } else {
                VpnDnsService.start(this);
                Toast.makeText(this, R.string.toast_started_vpn, Toast.LENGTH_SHORT).show();
            }
        } else {
            if (!RootDns.suAvailable()) {
                Toast.makeText(this, R.string.toast_no_su, Toast.LENGTH_LONG).show();
                return;
            }
            boolean ok = RootDns.start(this);
            Toast.makeText(this, ok ? R.string.toast_started_root : R.string.toast_root_fail, Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private void onStopClicked() {
        Controller.stop(this);
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                VpnDnsService.start(this);
                Toast.makeText(this, R.string.toast_started_vpn, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_vpn_need, Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        }
    }

    private void refreshStatus() {
        boolean running = Controller.isRunning();
        dotStatus.setBackgroundResource(running ? R.drawable.bg_dot_live : R.drawable.bg_dot_idle);
        btnToggle.setText(running ? R.string.btn_stop : R.string.btn_start);
        btnToggle.setBackgroundResource(running ? R.drawable.bg_btn_danger : R.drawable.bg_btn_primary);
        if (running) {
            String mode = Prefs.modeVpn(this) ? "VPN non-root" : "Root iptables";
            String server = Prefs.serverNameFor(this, null);
            int count = Prefs.enabledSet(this).size();
            txtStatus.setText("AKTIF · " + mode + " · " + server + " · " + count + " app");
        } else {
            txtStatus.setText(R.string.not_running);
        }
    }
}