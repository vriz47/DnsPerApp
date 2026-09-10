package com.dnsperapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VpnDnsService extends VpnService implements Runnable {
    private static final String TAG = "VpnDns";
    private static final String CHANNEL = "dnsperapp";
    public static final String ACTION_STATE = "com.dnsperapp.STATE";

    private static volatile boolean running;
    private static volatile int lastHash;
    private static volatile boolean rebuild;
    private static volatile VpnDnsService sInstance;
    private static volatile java.io.FileOutputStream sTunOut;
    private static final Object INJ = new Object();

    private ParcelFileDescriptor tunnel;
    private Thread thread;
    private ExecutorService pool;
    private final Object writeLock = new Object();
    private volatile boolean keepRunning;
    private volatile TunForwarder fwd;
    private volatile DatagramSocket localDns;
    private volatile Thread localDnsThread;
    private static final String REDIR_CHAIN = "DNSVPN";

    /** Write an IPv4 packet back into the tunnel (any thread). */
    public static void inject(byte[] packet) {
        java.io.FileOutputStream out = sTunOut;
        if (out == null) return;
        synchronized (INJ) {
            try {
                out.write(packet);
                out.flush();
            } catch (Exception ignored) { }
        }
    }

    public static boolean isRunning() { return running; }

    public static void start(Context c) {
        Intent i = new Intent(c, VpnDnsService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        VpnDnsService s = sInstance;
        if (s != null) {
            s.keepRunning = false;
            running = false;
            new Handler(Looper.getMainLooper()).post(s::stopNow);
        } else {
            c.stopService(new Intent(c, VpnDnsService.class));
        }
    }

    /** Signal the service thread to rebuild the VPN with current prefs. */
    public static void requestRebuild() { rebuild = true; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        startForeground(1, n);
        startInternal();
        return START_NOT_STICKY;
    }

    /** Tear down the VPN first (close tunnel), then let the service die. stopService()
     *  alone never reaches onDestroy while the system holds the VpnService binding. */
    private void stopNow() {
        Log.i(TAG, "stopNow: closing tunnel");
        stopInternal();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        notifyState();
    }

    private void notifyState() {
        try {
            Intent i = new Intent(ACTION_STATE);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        } catch (Exception ignored) { }
    }

    private static String qname(byte[] b) {
        try {
            int i = 12;
            StringBuilder sb = new StringBuilder();
            while (i < b.length) {
                int l = b[i] & 0xFF;
                if (l == 0) break;
                if ((l & 0xC0) == 0xC0) {
                    int ptr = ((l & 0x3F) << 8) | (b[i + 1] & 0xFF);
                    if (ptr + 1 >= b.length) break;
                    int j = ptr;
                    boolean first = true;
                    while (j < b.length) {
                        int k = b[j] & 0xFF;
                        if (k == 0) break;
                        if (j > ptr && !first) sb.append('.');
                        first = false;
                        for (int m = j + 1; m < j + 1 + k && m < b.length; m++) {
                            sb.append((char) b[m]);
                        }
                        j += k + 1;
                    }
                    break;
                }
                if (i + 1 + l > b.length) break;
                if (sb.length() > 0) sb.append('.');
                for (int m = i + 1; m < i + 1 + l; m++) sb.append((char) b[m]);
                i += l + 1;
            }
            return sb.length() > 0 ? sb.toString() : "raw";
        } catch (Exception e) {
            return "raw";
        }
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        stopInternal();
        notifyState();
        super.onDestroy();
    }

    private void startInternal() {
        stopInternal();
        String[] pkgs = Prefs.enabledPackages(this);
        if (pkgs.length == 0) {
            notifyState();
            stopSelf();
            return;
        }
        try {
            Builder b = new Builder();
            b.setSession(getString(R.string.app_name));
            b.setMtu(1500);
            b.addAddress("10.1.10.2", 24);
            b.addRoute("0.0.0.0", 0);
            b.addDnsServer("10.1.10.2");
            for (String p : pkgs) {
                try {
                    b.addAllowedApplication(p);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            tunnel = b.establish();
            if (tunnel == null) {
                notifyState();
                stopSelf();
                return;
            }
            keepRunning = true;
            rebuild = false;
            lastHash = Prefs.configHash(this);
            pool = Executors.newFixedThreadPool(8);
            fwd = new TunForwarder(this);
            applyRedirectRules();
            startLocalDns();
            thread = new Thread(this, "dns-tun");
            thread.start();
            running = true;
            Log.i(TAG, "VPN started, apps=" + pkgs.length);
            notifyState();
        } catch (Exception e) {
            Log.e(TAG, "startInternal", e);
            stopInternal();
            notifyState();
        }
    }

    private void stopInternal() {
        keepRunning = false;
        running = false;
        sTunOut = null;
        clearRedirectRules();
        if (localDnsThread != null) {
            try { localDnsThread.join(1000); } catch (Exception ignored) { }
            localDnsThread = null;
        }
        if (localDns != null) {
            try { localDns.close(); } catch (Exception ignored) { }
            localDns = null;
        }
        if (fwd != null) {
            fwd.close();
            fwd = null;
        }
        if (thread != null) {
            try { thread.join(1000); } catch (Exception ignored) { }
            thread = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        if (tunnel != null) {
            try { tunnel.close(); } catch (Exception ignored) { }
            tunnel = null;
        }
    }

    private void applyRedirectRules() {
        try {
            String[] pkgs = Prefs.enabledPackages(this);
            StringBuilder sb = new StringBuilder("ip6tables -t nat -X " + REDIR_CHAIN + " 2>/dev/null; ");
            sb.append("iptables -t nat -N ").append(REDIR_CHAIN).append(" 2>/dev/null; ");
            sb.append("iptables -t nat -F ").append(REDIR_CHAIN).append("; ");
            sb.append("iptables -t nat -A ").append(REDIR_CHAIN)
                    .append(" -m owner --uid-owner 0 -p udp --dport 53 -d 10.1.10.2 -j REDIRECT --to-ports 5353; ");
            for (String p : pkgs) {
                try {
                    int uid = getPackageManager().getApplicationInfo(p, 0).uid;
                    sb.append("iptables -t nat -A ").append(REDIR_CHAIN)
                            .append(" -m owner --uid-owner ").append(uid)
                            .append(" -p udp --dport 53 -j REDIRECT --to-ports 5353; ");
                    sb.append("iptables -t nat -A ").append(REDIR_CHAIN)
                            .append(" -m owner --uid-owner ").append(uid)
                            .append(" -p tcp --dport 53 -j REDIRECT --to-ports 5353; ");
                } catch (Exception ignored) { }
            }
            sb.append("iptables -t nat -A OUTPUT -j ").append(REDIR_CHAIN).append("; ");
            su(sb.toString());
        } catch (Exception ex) {
            Log.e(TAG, "applyRedirectRules", ex);
        }
    }

    private void clearRedirectRules() {
        try {
            su("iptables -t nat -D OUTPUT -j " + REDIR_CHAIN + " 2>/dev/null; "
                    + "iptables -t nat -F " + REDIR_CHAIN + " 2>/dev/null; "
                    + "iptables -t nat -X " + REDIR_CHAIN + " 2>/dev/null; ");
        } catch (Exception ex) {
            Log.e(TAG, "clearRedirectRules", ex);
        }
    }

    private void su(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/su", "-c", cmd});
        java.io.InputStream is = p.getInputStream();
        while (is.read() != -1) { }
        p.waitFor();
    }

    private void startLocalDns() {
        try {
            final DatagramSocket sock = new DatagramSocket(5353,
                    InetAddress.getByName("127.0.0.1"));
            localDns = sock;
            Thread th = new Thread(() -> {
                byte[] buf = new byte[4096];
                while (keepRunning) {
                    try {
                        DatagramPacket dp = new DatagramPacket(buf, buf.length);
                        sock.setSoTimeout(1000);
                        sock.receive(dp);
                        final byte[] q = new byte[dp.getLength()];
                        System.arraycopy(dp.getData(), dp.getOffset(), q, 0, dp.getLength());
                        final int type = qtypeOf(q);
                        final java.net.InetSocketAddress peer = new java.net.InetSocketAddress(
                                dp.getAddress(), dp.getPort());
                        Log.i(TAG, "ldns q=" + qname(q) + " t=" + type + " from=" + dp.getAddress().getHostAddress());
                        pool.execute(() -> {
                            byte[] resp = DnsRelay.resolve(q,
                                    Prefs.serverConfigFor(this, null), 5000,
                                    new DnsRelay.Protector() {
                                        @Override
                                        public DatagramSocket protect(DatagramSocket s) throws Exception {
                                            return VpnDnsService.this.protect(s) ? s : s;
                                        }
                                    });
                            if (resp != null) {
                                resp = DnsRelay.blockRewrite(resp);
                                try {
                                    sock.send(new DatagramPacket(resp, resp.length, peer));
                                } catch (Exception ignored) { }
                            }
                        });
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        break;
                    }
                }
            }, "localdns");
            localDnsThread = th;
            th.start();
            Log.i(TAG, "local dns 127.0.0.1:5353 up (redirect " + REDIR_CHAIN + ")");
        } catch (Exception e) {
            Log.e(TAG, "startLocalDns", e);
        }
    }

    @Override
    public void run() {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(tunnel.getFileDescriptor());
            out = new FileOutputStream(tunnel.getFileDescriptor());
        } catch (Exception e) {
            Log.e(TAG, "streams", e);
            stopSelf();
            return;
        }
        sTunOut = out;
        byte[] buf = new byte[32767];
        while (keepRunning) {
            if (rebuild) {
                rebuild = false;
                if (Prefs.configHash(this) != lastHash) {
                    Log.i(TAG, "config changed, rebuilding VPN");
                    startInternal();
                    return;
                }
            }
            int n;
            try {
                n = in.read(buf);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;
            final Tun.DnsQuery q = Tun.parseDnsQuery(buf, n);
            if (q != null) {
                final byte[] queryBytes = q.payload;
                final String qn = qname(queryBytes);
                Log.i(TAG, "recv q=" + qn);
                final DnsServer server = Prefs.serverConfigFor(this, null);
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        long t = System.currentTimeMillis();
                        byte[] resp = DnsRelay.resolve(queryBytes, server, 5000, new DnsRelay.Protector() {
                            @Override
                            public DatagramSocket protect(DatagramSocket socket) throws Exception {
                                VpnDnsService thiz = VpnDnsService.this;
                                return thiz.protect(socket) ? socket : socket;
                            }
                        });
                        long dt = System.currentTimeMillis() - t;
                        Log.i(TAG, "resolve q=" + qn + " " + (resp != null ? "ok" : "null") + " " + dt + "ms");
                        if (resp != null && keepRunning) {
                            resp = DnsRelay.blockRewrite(resp);
                            byte[] packet = Tun.buildResponse(q.clientIp, q.clientPort, resp);
                            inject(packet);
                        }
                    }
                });
                continue;
            }
            TunForwarder f = fwd;
            if (f != null) {
                f.handlePacket(buf, n);
            }
        }
        stopSelf();
    }

    private static int qtypeOf(byte[] q) {
        if (q.length < 14) return 0;
        int i = 12;
        while (i < q.length) {
            int l = q[i] & 0xFF;
            if (l == 0) { i++; break; }
            if ((l & 0xC0) == 0xC0) return 0;
            i += l + 1;
        }
        if (i + 2 > q.length) return 0;
        return ((q[i] & 0xFF) << 8) | (q[i + 1] & 0xFF);
    }

    private static byte[] emptyResponse(byte[] q) {
        byte[] r = new byte[q.length];
        System.arraycopy(q, 0, r, 0, q.length);
        r[2] = (byte) 0x81;
        r[3] = (byte) 0x80;
        r[6] = 0; r[7] = 0;
        r[8] = 0; r[9] = 0;
        r[10] = 0; r[11] = 0;
        return r;
    }

    // ---- notification ----

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel c = new NotificationChannel(CHANNEL, "DNS Per App", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String server = Prefs.serverNameFor(this, null);
        int count = Prefs.enabledPackages(this).length;
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("DNS Per App aktif")
                .setContentText(server + " · " + count + " app")
                .setContentIntent(pi)
                .build();
    }
}