package com.dnsperapp;

import android.content.Context;
import android.widget.ArrayAdapter;

import java.util.ArrayList;
import java.util.List;

public final class SpinnerAdapters {

    public static ArrayAdapter<String> dns(Context c) {
        List<String> opts = new ArrayList<>();
        for (int i = 0; i < DnsServers.NAMES.length; i++) {
            opts.add(DnsServers.NAMES[i] + "  ·  " + DnsServers.IPS[i]);
        }
        opts.add("Custom…");
        ArrayAdapter<String> a = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item, opts);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    public static ArrayAdapter<String> mode(Context c) {
        ArrayAdapter<String> a = new ArrayAdapter<>(c, android.R.layout.simple_spinner_item,
                new String[]{"VPN (tanpa root)", "Root (iptables)"});
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }
}