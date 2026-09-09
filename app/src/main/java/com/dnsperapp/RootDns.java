package com.dnsperapp;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public final class RootDns {
    private static final int BASE_PORT = 5353;
    private static volatile boolean running = false;
    private static volatile Thread[] threads;

    public static boolean isRunning() { return running; }

    public static boolean suAvailable() {
        return execSu("id").contains("uid=0");
    }

    private static String suPath() {
        String[] candidates = {"su", "/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/ksud"};
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-c", "id").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = r.readLine();
                p.waitFor();
                if (line != null && line.contains("uid=0")) return c;
            } catch (Exception ignored) { }
        }
        // fallback: try su via PATH
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "-c", "id").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = r.readLine();
                p.waitFor();
                if (line != null && line.contains("uid=0")) return c;
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static String execSu(String cmd) {
        try {
            String su = suPath();
            if (su == null) return "";
            Process p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            try { while ((line = r.readLine()) != null) sb.append(line).append('\n'); } catch (Exception ignored) { }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized boolean start(final Context c) {
        if (running) return true;
        final String[] pkgs = Prefs.enabledPackages(c);
        if (pkgs.length == 0) return false;
        int ownUid = android.os.Process.myUid();

        StringBuilder v4 = new StringBuilder();
        StringBuilder v6 = new StringBuilder();
        for (android.content.pm.ApplicationInfo ai : appsWithUid(c, pkgs)) {
            if (ai == null) continue;
            int port = BASE_PORT + indexOf(pkgs, ai.packageName);
            v4.append("iptables -t nat -A DNS_PER_APP -m owner --uid-owner ")
              .append(ai.uid)
              .append(" -p udp --dport 53 -j REDIRECT --to-ports ").append(port).append("; ");
            v4.append("iptables -t nat -A DNS_PER_APP -m owner --uid-owner ")
              .append(ai.uid)
              .append(" -p tcp --dport 53 -j REDIRECT --to-ports ").append(port).append("; ");
            v6.append("ip6tables -t nat -A DNS_PER_APP -m owner --uid-owner ")
              .append(ai.uid)
              .append(" -p udp --dport 53 -j REDIRECT --to-ports ").append(port).append("; ");
            v6.append("ip6tables -t nat -A DNS_PER_APP -m owner --uid-owner ")
              .append(ai.uid)
              .append(" -p tcp --dport 53 -j REDIRECT --to-ports ").append(port).append("; ");
        }

        String cmd = "iptables -t nat -N DNS_PER_APP 2>/dev/null; "
                + "iptables -t nat -F DNS_PER_APP; "
                + "iptables -t nat -A DNS_PER_APP -m owner --uid-owner " + ownUid + " -j RETURN; "
                + v4
                + "iptables -t nat -I OUTPUT -j DNS_PER_APP; "
                + "ip6tables -t nat -N DNS_PER_APP 2>/dev/null; "
                + "ip6tables -t nat -F DNS_PER_APP; "
                + "ip6tables -t nat -A DNS_PER_APP -m owner --uid-owner " + ownUid + " -j RETURN; "
                + v6
                + "ip6tables -t nat -I OUTPUT -j DNS_PER_APP; ";
        String log = execSu(cmd);
        if (!log.trim().isEmpty()) {
            // bail if iptables failed
        }

        threads = new Thread[pkgs.length];
        for (int i = 0; i < pkgs.length; i++) {
            final int port = BASE_PORT + i;
            final DnsServer server = Prefs.serverConfigFor(c, pkgs[i]);
            Thread t = new Thread(new UdpProxy(port, server), "dns-proxy-" + port);
            t.start();
            threads[i] = t;
        }
        running = true;
        return true;
    }

    public static synchronized boolean reapply(Context c) {
        if (!running) return start(c);
        return start(c); // idempotent re-apply
    }

    public static synchronized void stop(Context c) {
        if (!running) {
            execSu("iptables -t nat -D OUTPUT -j DNS_PER_APP 2>/dev/null; "
                    + "iptables -t nat -F DNS_PER_APP 2>/dev/null; "
                    + "iptables -t nat -X DNS_PER_APP 2>/dev/null; "
                    + "ip6tables -t nat -D OUTPUT -j DNS_PER_APP 2>/dev/null; "
                    + "ip6tables -t nat -F DNS_PER_APP 2>/dev/null; "
                    + "ip6tables -t nat -X DNS_PER_APP 2>/dev/null; ");
        }
        running = false;
        if (threads != null) {
            for (Thread t : threads) {
                if (t != null) t.interrupt();
            }
            threads = null;
        }
    }

    private static int indexOf(String[] arr, String pkg) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(pkg)) return i;
        return 0;
    }

    private static android.content.pm.ApplicationInfo[] appsWithUid(Context c, String[] pkgs) {
        android.content.pm.ApplicationInfo[] out = new android.content.pm.ApplicationInfo[pkgs.length];
        android.content.pm.PackageManager pm = c.getPackageManager();
        for (int i = 0; i < pkgs.length; i++) {
            try {
                out[i] = pm.getApplicationInfo(pkgs[i], 0);
            } catch (Exception e) {
                out[i] = null;
            }
        }
        return out;
    }

    private static final class UdpProxy implements Runnable {
        final int port;
        final DnsServer server;

        UdpProxy(int port, DnsServer server) {
            this.port = port;
            this.server = server;
        }

        @Override
        public void run() {
            DatagramSocket local = null;
            try {
                local = new DatagramSocket(port);
                byte[] buf = new byte[4096];
                while (running) {
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    local.receive(req); // throws SocketException when closed
                    byte[] payload = new byte[req.getLength()];
                    System.arraycopy(buf, 0, payload, 0, req.getLength());
                    byte[] resp = DnsRelay.resolve(payload, server, 5000, null);
                    if (resp != null) {
                        local.send(new DatagramPacket(resp, resp.length, req.getAddress(), req.getPort()));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (local != null) {
                    try { local.close(); } catch (Exception ignored) { }
                }
            }
        }
    }
}