package com.dnsperapp;

import android.content.Context;
import android.content.Intent;

public final class Controller {
    public static boolean isRunning() {
        return VpnDnsService.isRunning() || RootDns.isRunning();
    }

    public static void start(Context c) {
        if (Prefs.modeVpn(c)) {
            VpnDnsService.start(c);
        } else {
            RootDns.start(c);
        }
    }

    public static void stop(Context c) {
        if (VpnDnsService.isRunning()) {
            VpnDnsService.stop(c);
        }
        if (RootDns.isRunning()) {
            RootDns.stop(c);
        }
    }
}