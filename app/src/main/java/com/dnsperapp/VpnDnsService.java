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
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VpnDnsService extends VpnService implements Runnable {
    private static final String TAG = "VpnDns";
    private static final String CHANNEL = "dnsperapp";

    private static volatile boolean running;
    private static volatile int lastHash;
    private static volatile boolean rebuild;

    private ParcelFileDescriptor tunnel;
    private Thread thread;
    private ExecutorService pool;
    private final Object writeLock = new Object();
    private volatile boolean keepRunning;

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
        c.stopService(new Intent(c, VpnDnsService.class));
    }

    /** Signal the service thread to rebuild the VPN with current prefs. */
    public static void requestRebuild() { rebuild = true; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        startForeground(1, n);
        startInternal();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopInternal();
        super.onDestroy();
    }

    private void startInternal() {
        stopInternal();
        String[] pkgs = Prefs.enabledPackages(this);
        if (pkgs.length == 0) {
            stopSelf();
            return;
        }
        try {
            Builder b = new Builder();
            b.setSession(getString(R.string.app_name));
            b.setMtu(1500);
            b.addAddress("10.1.10.2", 24);
            b.addRoute("10.1.10.0", 24);
            b.addDnsServer("10.1.10.2");
            for (String p : pkgs) {
                try {
                    b.addAllowedApplication(p);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // app uninstalled meanwhile
                }
            }
            tunnel = b.establish();
            if (tunnel == null) {
                stopSelf();
                return;
            }
            keepRunning = true;
            rebuild = false;
            lastHash = Prefs.configHash(this);
            pool = Executors.newFixedThreadPool(8);
            thread = new Thread(this, "dns-tun");
            thread.start();
            running = true;
            Log.i(TAG, "VPN started, apps=" + pkgs.length);
        } catch (Exception e) {
            Log.e(TAG, "startInternal", e);
            stopInternal();
        }
    }

    private void stopInternal() {
        keepRunning = false;
        running = false;
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
            if (q == null) continue;
            final byte[] queryBytes = q.payload;
            final DnsServer server = Prefs.serverConfigFor(this, null);
            final FileOutputStream fout = out;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    long t = System.currentTimeMillis();
                    byte[] resp = DnsRelay.resolve(queryBytes, server, 5000, new DnsRelay.Protector() {
                        @Override
                        public DatagramSocket protect(DatagramSocket s) throws Exception {
                            VpnDnsService thiz = VpnDnsService.this;
                            return thiz.protect(s) ? s : s;
                        }
                    });
                    if (resp != null && keepRunning) {
                        byte[] packet = Tun.buildResponse(q.clientIp, q.clientPort, resp);
                        synchronized (writeLock) {
                            try {
                                fout.write(packet);
                                fout.flush();
                            } catch (Exception ignored) { }
                        }
                    }
                }
            });
        }
        stopSelf();
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