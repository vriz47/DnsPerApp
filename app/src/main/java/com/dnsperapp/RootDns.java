package com.dnsperapp;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;

public final class RootDns {
    private static final String TAG = "DnsPerApp.RootDns";
    private static final int BASE_PORT = 5353;
    private static volatile boolean running = false;
    private static volatile Thread[] threads;
    private static volatile DatagramSocket[] sockets;

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

    private static DatagramSocket bindSocket(int preferred) {
        for (int p = preferred; p < preferred + 20; p++) {
            try {
                DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
                DatagramSocket s = ch.socket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), p));
                Log.i(TAG, "bound " + p + " addr=" + s.getLocalAddress());
                return s;
            } catch (Exception e) {
                Log.w(TAG, "bind :" + p + " failed: " + e);
            }
        }
        return null;
    }

    private static boolean allAlive() {
        if (threads == null) return false;
        for (Thread t : threads) {
            if (t == null || !t.isAlive()) return false;
        }
        return true;
    }

    public static synchronized boolean start(final Context c) {
        final String[] pkgs = Prefs.enabledPackages(c);
        if (pkgs.length == 0) return false;
        int ownUid = android.os.Process.myUid();

        if (running && allAlive()) {
            int[] ports = portsOf();
            if (ports != null) {
                applyRules(c, ports, ownUid);
                Log.i(TAG, "rules refreshed, ports=" + java.util.Arrays.toString(ports));
                return true;
            }
        }

        if (running || sockets != null) {
            Log.w(TAG, "relay unhealthy; full restart");
            closeQuiet();
        }

        DatagramSocket[] socks = new DatagramSocket[pkgs.length];
        int[] ports = new int[pkgs.length];
        for (int i = 0; i < pkgs.length; i++) {
            DatagramSocket s = bindSocket(BASE_PORT + i);
            if (s == null) {
                Log.e(TAG, "no free port for " + pkgs[i]);
                return false;
            }
            socks[i] = s;
            ports[i] = s.getLocalPort();
        }

        if (!applyRules(c, ports, ownUid)) {
            for (DatagramSocket s : socks) {
                try { s.close(); } catch (Exception ignored) { }
            }
            return false;
        }

        threads = new Thread[pkgs.length];
        for (int i = 0; i < pkgs.length; i++) {
            final DatagramSocket s = socks[i];
            final DnsServer server = Prefs.serverConfigFor(c, pkgs[i]);
            Thread t = new Thread(new UdpProxy(s, server), "dns-proxy-" + s.getLocalPort());
            t.start();
            threads[i] = t;
        }
        sockets = socks;
        running = true;
        Log.i(TAG, "started, ports=" + java.util.Arrays.toString(ports));
        return true;
    }

    private static int[] portsOf() {
        if (sockets == null) return null;
        int[] out = new int[sockets.length];
        for (int i = 0; i < sockets.length; i++) {
            if (sockets[i] == null) return null;
            out[i] = sockets[i].getLocalPort();
        }
        return out;
    }

    private static boolean applyRules(Context c, int[] ports, int ownUid) {
        String[] pkgs = Prefs.enabledPackages(c);
        StringBuilder v4 = new StringBuilder();
        StringBuilder v6 = new StringBuilder();
        android.content.pm.ApplicationInfo[] infos = appsWithUid(c, pkgs);
        for (int i = 0; i < pkgs.length; i++) {
            if (infos[i] == null) continue;
            v4.append("iptables -t nat -A DNS_PER_APP -m owner --uid-owner ")
              .append(infos[i].uid)
              .append(" -p udp --dport 53 -j REDIRECT --to-ports ").append(ports[i]).append("; ");
            v4.append("iptables -t nat -A DNS_PER_APP -m owner --uid-owner ")
              .append(infos[i].uid)
              .append(" -p tcp --dport 53 -j REDIRECT --to-ports ").append(ports[i]).append("; ");
            v6.append("ip6tables -t nat -A DNS_PER_APP -m owner --uid-owner ")
              .append(infos[i].uid)
              .append(" -p udp --dport 53 -j REDIRECT --to-ports ").append(ports[i]).append("; ");
            v6.append("ip6tables -t nat -A DNS_PER_APP -m owner --uid-owner ")
              .append(infos[i].uid)
              .append(" -p tcp --dport 53 -j REDIRECT --to-ports ").append(ports[i]).append("; ");
        }
        int relayPort = ports[0];
        String cmd = "iptables -t nat -D OUTPUT -j DNS_PER_APP; "
                + "iptables -t nat -D OUTPUT -j DNS_PER_APP; "
                + "iptables -t nat -D OUTPUT -j DNS_PER_APP; "
                + "iptables -t nat -D OUTPUT -j DNS_PER_APP; "
                + "iptables -t nat -N DNS_PER_APP 2>/dev/null; "
                + "iptables -t nat -F DNS_PER_APP; "
                + "iptables -t nat -A DNS_PER_APP -m owner --uid-owner " + ownUid + " -j RETURN; "
                + v4
                + "iptables -t nat -I OUTPUT -j DNS_PER_APP; "
                + "ip6tables -t nat -D OUTPUT -j DNS_PER_APP; "
                + "ip6tables -t nat -D OUTPUT -j DNS_PER_APP; "
                + "ip6tables -t nat -N DNS_PER_APP 2>/dev/null; "
                + "ip6tables -t nat -F DNS_PER_APP; "
                + "ip6tables -t nat -A DNS_PER_APP -m owner --uid-owner " + ownUid + " -j RETURN; "
                + v6
                + "ip6tables -t nat -I OUTPUT -j DNS_PER_APP; ";
        String log = execSu(cmd);
        return true;
    }

    private static void closeQuiet() {
        running = false;
        if (sockets != null) {
            for (DatagramSocket s : sockets) {
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) { }
                }
            }
            sockets = null;
        }
        if (threads != null) {
            for (Thread t : threads) {
                if (t != null) {
                    try { t.interrupt(); } catch (Exception ignored) { }
                }
            }
            threads = null;
        }
    }

    public static synchronized boolean reapply(Context c) {
        return start(c);
    }

    public static synchronized void stop(Context c) {
        running = false;
        execSu("iptables -t nat -D OUTPUT -j DNS_PER_APP 2>/dev/null; "
                + "iptables -t nat -F DNS_PER_APP 2>/dev/null; "
                + "iptables -t nat -X DNS_PER_APP 2>/dev/null; "
                + "ip6tables -t nat -D OUTPUT -j DNS_PER_APP 2>/dev/null; "
                + "ip6tables -t nat -F DNS_PER_APP 2>/dev/null; "
                + "ip6tables -t nat -X DNS_PER_APP 2>/dev/null; ");
        if (sockets != null) {
            for (DatagramSocket s : sockets) {
                if (s != null) {
                    try { s.close(); } catch (Exception ignored) { }
                }
            }
            sockets = null;
        }
        if (threads != null) {
            for (Thread t : threads) {
                if (t != null) t.interrupt();
            }
            threads = null;
        }
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

    private static String qname(byte[] b) {
        StringBuilder sb = new StringBuilder(64);
        int i = 12;
        while (i < b.length) {
            int l = b[i] & 0xFF;
            if (l == 0) break;
            i++;
            if ((l & 0xC0) == 0xC0) break;
            if (i + l > b.length) break;
            if (sb.length() > 0) sb.append('.');
            for (int k = 0; k < l; k++) sb.append((char) b[i + k]);
            i += l;
        }
        return sb.toString();
    }

    private static final class UdpProxy implements Runnable {
        final DatagramSocket local;
        final DnsServer server;

        UdpProxy(DatagramSocket local, DnsServer server) {
            this.local = local;
            this.server = server;
        }

        @Override
        public void run() {
            byte[] buf = new byte[4096];
            while (running) {
                try {
                    DatagramPacket req = new DatagramPacket(buf, buf.length);
                    local.receive(req);
                    byte[] payload = new byte[req.getLength()];
                    System.arraycopy(buf, 0, payload, 0, req.getLength());
                    String qn = qname(payload);
                    Log.i(TAG, "recv q=" + qn + " from=" + req.getAddress().getHostAddress() + ":" + req.getPort()
                            + " len=" + req.getLength());
                    long t0 = System.currentTimeMillis();
                    byte[] resp = DnsRelay.resolve(payload, server, 5000, null);
                    Log.i(TAG, "resolve q=" + qn + " " + (resp == null ? "null" : "ok " + resp.length)
                            + " in " + (System.currentTimeMillis() - t0) + "ms");
                    if (resp != null) {
                        resp = DnsRelay.blockRewrite(resp);
                        local.send(new DatagramPacket(resp, resp.length, req.getAddress(), req.getPort()));
                    }
                } catch (java.net.SocketException | java.nio.channels.AsynchronousCloseException e) {
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "proxy error: " + e);
                }
            }
        }
    }
}