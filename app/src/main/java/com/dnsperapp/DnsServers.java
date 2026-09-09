package com.dnsperapp;

public final class DnsServers {
    public static final String[] NAMES = {
            "AdGuard DNS", "AdGuard Family", "Cloudflare", "Google", "Quad9", "OpenDNS"
    };
    public static final String[] IPS = {
            "94.140.14.14", "94.140.14.15", "1.1.1.1",
            "8.8.8.8", "9.9.9.9", "208.67.222.222"
    };
    public static final String[] DOH_HOSTS = {
            "dns.adguard-dns.com", "dns-family.adguard-dns.com", "cloudflare-dns.com",
            "dns.google", "dns.quad9.net", "doh.opendns.com"
    };
    public static final String[] DOH_IPS = {
            "94.140.14.14", "94.140.14.15", "1.1.1.1",
            "8.8.8.8", "9.9.9.9", "208.67.222.222"
    };

    public static String name(int idx) {
        if (idx >= 0 && idx < NAMES.length) return NAMES[idx];
        return "Custom";
    }

    /** Built-in server config (index safe-bound). Returns null if idx is out of range. */
    public static DnsServer config(int idx) {
        if (idx < 0 || idx >= NAMES.length) return null;
        return new DnsServer(NAMES[idx], IPS[idx], DOH_HOSTS[idx], DOH_IPS[idx]);
    }
}