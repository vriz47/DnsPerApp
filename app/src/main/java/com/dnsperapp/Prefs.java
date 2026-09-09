package com.dnsperapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class Prefs {
    private static final String FILE = "dnsperapp";
    private static final String KEY_SERVER = "server";
    private static final String KEY_CUSTOM = "custom_ip";
    private static final String KEY_MODE = "mode"; // 0=vpn, 1=root
    private static final String KEY_PKG = "pkg:";   // pkg:<name> = serverIdx (-1=global)

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int serverIndex(Context c) {
        return sp(c).getInt(KEY_SERVER, 0);
    }

    public static void setServerIndex(Context c, int idx) {
        sp(c).edit().putInt(KEY_SERVER, idx).apply();
    }

    public static String customIp(Context c) {
        return sp(c).getString(KEY_CUSTOM, "").trim();
    }

    public static void setCustomIp(Context c, String ip) {
        sp(c).edit().putString(KEY_CUSTOM, ip == null ? "" : ip.trim()).apply();
    }

    public static boolean modeRoot(Context c) {
        return sp(c).getInt(KEY_MODE, 0) == 1;
    }

    public static boolean modeVpn(Context c) {
        return !modeRoot(c);
    }

    public static void setMode(Context c, boolean root) {
        sp(c).edit().putInt(KEY_MODE, root ? 1 : 0).apply();
    }

    public static void setPackageServer(Context c, String pkg, int serverIdx) {
        SharedPreferences.Editor e = sp(c).edit();
        if (serverIdx < -1) {
            e.remove(KEY_PKG + pkg);
        } else {
            e.putInt(KEY_PKG + pkg, serverIdx);
        }
        e.apply();
    }

    public static int packageServer(Context c, String pkg) {
        return sp(c).getInt(KEY_PKG + pkg, -1);
    }

    /** true if the package is enabled for per-app DNS */
    public static boolean isEnabled(Context c, String pkg) {
        return sp(c).getAll().containsKey(KEY_PKG + pkg);
    }

    public static Set<String> enabledSet(Context c) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_PKG)) {
                out.add(e.getKey().substring(KEY_PKG.length()));
            }
        }
        return out;
    }

    public static String[] enabledPackages(Context c) {
        return new ArrayList<>(enabledSet(c)).toArray(new String[0]);
    }

    /** Resolve effective server IP for a package in VPN mode (always global). */
    public static String serverIp(Context c) {
        return serverIpFor(c, null);
    }

    /** Resolve effective server config for a package; pkg==null -> global selection. */
    public static DnsServer serverConfigFor(Context c, String pkg) {
        int idx = packageServer(c, pkg);
        if (pkg != null && idx >= 0 && idx < DnsServers.NAMES.length) {
            return DnsServers.config(idx);
        }
        int g = serverIndex(c);
        if (g >= 0 && g < DnsServers.NAMES.length) {
            return DnsServers.config(g);
        }
        return new DnsServer("Custom", customIp(c), null, null);
    }

    /** Resolve server IP; pkg==null -> global selection. */
    public static String serverIpFor(Context c, String pkg) {
        DnsServer s = serverConfigFor(c, pkg);
        return s == null ? customIp(c) : s.ip;
    }

    public static String serverNameFor(Context c, String pkg) {
        int idx = packageServer(c, pkg);
        if (pkg != null && idx >= 0 && idx < DnsServers.NAMES.length) {
            return DnsServers.NAMES[idx];
        }
        int g = serverIndex(c);
        if (g >= 0 && g < DnsServers.NAMES.length) {
            return DnsServers.NAMES[g];
        }
        return "Custom";
    }

    public static int configHash(Context c) {
        return (enabledSet(c) + "|" + serverIndex(c) + "|" + customIp(c) + "|" + modeRoot(c)).hashCode();
    }
}