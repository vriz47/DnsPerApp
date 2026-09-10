package com.dnsperapp;

import android.net.VpnService;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Full-tunnel NAT44 forwarder for VpnDnsService: relays non-DNS IPv4 packets
 *  (UDP + TCP) out over the underlying network via protect()ed sockets, rebuilding
 *  IPv4 headers so the app sees a fully transparent connection. */
public class TunForwarder {
    private static final String TAG = "TunFwd";
    private static final long KEEP_ALIVE_MS = 120000;
    private static final int MSS = 1400;

    private final VpnService vpn;
    private final Random rnd = new Random();
    private final Map<Key, UdpEntry> udps = new HashMap<>();
    private final Map<Key, TcpEntry> tcps = new HashMap<>();
    private volatile boolean alive = true;

    public TunForwarder(VpnService vpn) {
        this.vpn = vpn;
    }

    public void close() {
        alive = false;
        synchronized (udps) {
            for (UdpEntry e : udps.values()) { try { e.sock.close(); } catch (Exception ignored) { } }
            udps.clear();
        }
        synchronized (tcps) {
            for (TcpEntry e : tcps.values()) { e.closeQuiet(); }
            tcps.clear();
        }
    }

    static final class Key {
        final int ip; final int port;
        Key(int ip, int port) { this.ip = ip; this.port = port; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o; return k.ip == ip && k.port == port;
        }
        @Override public int hashCode() { return ip * 31 + port; }
    }

    /** Handle one IPv4 packet read from the tunnel. Returns true if consumed
     *  (DNS UDP must be handled by the caller; this returns false for it). */
    public boolean handlePacket(byte[] p, int len) {
        if (!alive) return true;
        if (len < 20) return true;
        if ((p[0] >> 4) != 4) return true;
        int ihl = (p[0] & 0xF) * 4;
        if (ihl < 20 || len < ihl + 8) return true;
        int proto = p[9] & 0xFF;
        int src = Tun.toInt(p, 12);
        int dst = Tun.toInt(p, 16);
        int sport = ((p[ihl] & 0xFF) << 8) | (p[ihl + 1] & 0xFF);
        int dport = ((p[ihl + 2] & 0xFF) << 8) | (p[ihl + 3] & 0xFF);
        if (src == Tun.VIRTUAL_IP || dst == Tun.VIRTUAL_IP) return true;
        if (proto == 17) {
            if (dport == 53) return false;
            return handleUdp(src, sport, dst, dport, p, ihl, len);
        }
        if (proto == 6) return handleTcp(src, sport, dst, dport, p, ihl, len);
        return true;
    }

    // ---------------- UDP ----------------

    private boolean handleUdp(int src, int sport, int dst, int dport, byte[] p, int ihl, int len) {
        int payloadLen = len - (ihl + 8);
        if (payloadLen <= 0) return true;
        byte[] data = new byte[payloadLen];
        System.arraycopy(p, ihl + 8, data, 0, payloadLen);
        Key k = new Key(src, sport);
        UdpEntry e;
        synchronized (udps) {
            e = udps.get(k);
            if (e == null) {
                e = new UdpEntry(src, sport, dst, dport);
                udps.put(k, e);
                e.thread = new Thread(e, "udp-nat");
                e.thread.start();
            }
            e.lastUsed = System.currentTimeMillis();
        }
        try {
            e.sock.send(new DatagramPacket(data, data.length, new InetSocketAddress(Tun.addr(dst), dport)));
        } catch (Exception ex) {
            Log.w(TAG, "udp send", ex);
        }
        return true;
    }

    final class UdpEntry implements Runnable {
        final int cIp, cPort, sIp, sPort;
        final DatagramSocket sock;
        volatile long lastUsed = System.currentTimeMillis();
        Thread thread;

        UdpEntry(int cIp, int cPort, int sIp, int sPort) {
            this.cIp = cIp; this.cPort = cPort; this.sIp = sIp; this.sPort = sPort;
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                vpn.protect(s);
            } catch (Exception ex) {
                Log.e(TAG, "udp socket", ex);
            }
            sock = s;
        }

        @Override
        public void run() {
            byte[] buf = new byte[65535];
            try {
                while (alive) {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    sock.setSoTimeout(5000);
                    sock.receive(dp);
                    if (System.currentTimeMillis() - lastUsed > KEEP_ALIVE_MS) break;
                    byte[] out = new byte[dp.getLength()];
                    System.arraycopy(dp.getData(), dp.getOffset(), out, 0, dp.getLength());
                    byte[] packet = buildIpUdp(Tun.toInt(dp.getAddress().getAddress(), 0),
                            dp.getPort(), cIp, cPort, out);
                    if (packet != null) VpnDnsService.inject(packet);
                }
            } catch (java.net.SocketTimeoutException ignored) {
            } catch (Exception ex) {
                Log.w(TAG, "udp recv", ex);
            }
            synchronized (udps) {
                udps.remove(new Key(cIp, cPort));
            }
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    // ---------------- TCP ----------------

    private boolean handleTcp(int src, int sport, int dst, int dport, byte[] p, int ihl, int len) {
        if (len < ihl + 20) return true;
        int toff = (p[ihl + 12] >> 4) & 0xF;
        int hlen = toff * 4;
        if (hlen < 20 || ihl + hlen > len) return true;
        boolean syn = (p[ihl + 13] & 0x02) != 0;
        boolean fin = (p[ihl + 13] & 0x01) != 0;
        boolean rst = (p[ihl + 13] & 0x04) != 0;
        int seq = Tun.intAt(p, ihl + 4);
        Key k = new Key(src, sport);
        TcpEntry e;
        synchronized (tcps) {
            if (rst) {
                TcpEntry old = tcps.remove(k);
                if (old != null) old.closeQuiet();
                return true;
            }
            e = tcps.get(k);
            if (e == null) {
                if (!syn) return true;
                int mss = readMss(p, ihl + 20, toff);
                e = new TcpEntry(src, sport, dst, dport, seq, mss);
                tcps.put(k, e);
            } else {
                e.lastUsed = System.currentTimeMillis();
            }
        }
        if (e.socket == null && !e.connecting) return true;
        e.feedClient(seq, fin, rst, p, ihl + hlen, len - (ihl + hlen));
        return true;
    }

    private static int readMss(byte[] p, int optsStart, int toff) {
        int optLen = (toff - 5) * 4;
        for (int i = 0; i + 1 < optLen; ) {
            int kind = p[optsStart + i] & 0xFF;
            if (kind == 0) break;
            if (kind == 2 && i + 4 <= optLen) {
                return ((p[optsStart + i + 2] & 0xFF) << 8) | (p[optsStart + i + 3] & 0xFF);
            }
            int l = p[optsStart + i + 1] & 0xFF;
            if (l < 2) break;
            i += l;
        }
        return 0;
    }

    private static void buildSynOptions(byte[] o, int mss) {
        int n = 0;
        o[n++] = 2; o[n++] = 4; o[n++] = (byte) (mss >> 8); o[n++] = (byte) mss;
        if (n + 2 <= o.length) { o[n++] = 4; o[n++] = 2; }
        if (n + 3 <= o.length) { o[n++] = 3; o[n++] = 3; o[n++] = 10; }
        if (n < o.length) o[n] = 0;
    }

    final class TcpEntry implements Runnable {
        final int cIp, cPort, sIp, sPort;
        final int cIsn;
        final int sIsn = rnd.nextInt();
        final int clientMss;
        volatile Socket socket;
        volatile boolean connecting = true;
        volatile long lastUsed = System.currentTimeMillis();
        long cSent;
        long sSentOff;
        final byte[] synOptions;
        final ByteArrayOutputStream pend = new ByteArrayOutputStream(8192);
        boolean closed;

        TcpEntry(int cIp, int cPort, int sIp, int sPort, int cIsn, int clientMss) {
            this.cIp = cIp; this.cPort = cPort; this.sIp = sIp; this.sPort = sPort;
            this.cIsn = cIsn;
            this.clientMss = (clientMss > 0 && clientMss < MSS) ? clientMss : MSS;
            synOptions = new byte[this.clientMss / 2 + 7];
            buildSynOptions(synOptions, this.clientMss);
            new Thread(this, "tcp-connect").start();
        }

        @Override
        public void run() {
            Socket s = null;
            try {
                s = new Socket();
                if (!vpn.protect(s)) throw new Exception("protect fail");
                s.connect(new InetSocketAddress(Tun.addr(sIp), sPort), 5000);
            } catch (Exception ex) {
                Log.w(TAG, "tcp connect fail " + Tun.dotted(sIp) + ":" + sPort);
                try { if (s != null) s.close(); } catch (Exception ignored) { }
                sendTcp(sIp, sPort, cIp, cPort, sIsn, cIsn + 1, 0x14, null, 0, 0, null, 0);
                synchronized (tcps) { tcps.remove(new Key(cIp, cPort)); }
                return;
            }
            synchronized (this) {
                socket = s;
                connecting = false;
            }
            sendTcp(sIp, sPort, cIp, cPort, sIsn, cIsn + 1, 0x12, synOptions, 0, synOptions.length, null, 0);
            flushPending();
            try {
                InputStream is = s.getInputStream();
                byte[] buf = new byte[32768];
                int n;
                while (alive && (n = is.read(buf)) > 0) {
                    try {
                        int off = 0;
                        while (off < n) {
                            int block = Math.min(clientMss, n - off);
                            sendTcp(sIp, sPort, cIp, cPort, (int) (sIsn + 1 + sSentOff),
                                    (int) (cIsn + cSent), 0x18, buf, off, block, null, 0);
                            sSentOff += block;
                            off += block;
                            lastUsed = System.currentTimeMillis();
                        }
                    } catch (Exception ex) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            if (alive) {
                sendTcp(sIp, sPort, cIp, cPort, (int) (sIsn + 1 + sSentOff),
                        (int) (cIsn + cSent), 0x11, null, 0, 0, null, 0);
            }
            closeQuiet();
        }

        private void flushPending() {
            byte[] b;
            synchronized (pend) {
                if (pend.size() == 0) return;
                b = pend.toByteArray();
                pend.reset();
            }
            try {
                socket.getOutputStream().write(b);
                socket.getOutputStream().flush();
            } catch (Exception ignored) { }
        }

        void feedClient(int seq, boolean fin, boolean rst, byte[] p, int start, int plen) {
            try {
                synchronized (this) {
                    if (socket == null) {
                        if (!connecting) return;
                        int rel = (int) ((long) seq - cIsn - 1);
                        int skip = (int) Math.min(cSent, rel);
                        int off = (int) Math.max(0, skip);
                        int fwd = Math.min(plen - off, 65535);
                        if (fwd > 0) {
                            pend.write(p, start + off, fwd);
                            cSent += fwd;
                        }
                        return;
                    }
                }
                long rel = (long) seq - cIsn - 1;
                long skip = cSent - rel;
                int off = (int) Math.max(0, skip);
                int fwd = plen - off;
                if (fwd <= 0) return;
                OutputStream os = socket.getOutputStream();
                synchronized (pend) {
                    if (pend.size() > 0) {
                        byte[] b = pend.toByteArray();
                        pend.reset();
                        os.write(b);
                    }
                }
                synchronized (this) {
                    os.write(p, start + off, fwd);
                    cSent += fwd;
                }
                os.flush();
                lastUsed = System.currentTimeMillis();
                if (fin) {
                    try { socket.shutdownOutput(); } catch (Exception ignored) { }
                }
                if (rst) closeQuiet();
            } catch (Exception ex) {
                closeQuiet();
            }
        }

        void closeQuiet() {
            try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        }
    }

    // ---------------- packet builders ----------------

    private byte[] buildIpUdp(int sIp, int sPort, int cIp, int cPort, byte[] data) {
        int total = 20 + 8 + data.length;
        byte[] o = new byte[total];
        o[0] = 0x45;
        o[2] = (byte) (total >> 8); o[3] = (byte) total;
        o[8] = 64; o[9] = 17;
        Tun.writeInt(o, 12, sIp);
        Tun.writeInt(o, 16, cIp);
        int cs = Tun.checksum(o, 0, 20);
        o[10] = (byte) (cs >> 8); o[11] = (byte) cs;
        int u = 20;
        o[u] = (byte) (sPort >> 8); o[u + 1] = (byte) sPort;
        o[u + 2] = (byte) (cPort >> 8); o[u + 3] = (byte) cPort;
        int ut = 8 + data.length;
        o[u + 4] = (byte) (ut >> 8); o[u + 5] = (byte) ut;
        System.arraycopy(data, 0, o, u + 8, data.length);
        return o;
    }

    private void sendTcp(int sIp, int sPort, int cIp, int cPort, int seq, int ack, int flags,
                         byte[] data, int off, int len, byte[] opts, int optLen) {
        try {
            optLen = optLen > 0 ? optLen : 0;
            byte[] o = new byte[40 + optLen + len];
            o[0] = 0x45; // IPv4, IHL 5
            int total = o.length;
            o[2] = (byte) (total >> 8); o[3] = (byte) total;
            o[8] = 64; o[9] = 6;
            Tun.writeInt(o, 12, sIp);
            Tun.writeInt(o, 16, cIp);
            int t = 20;
            o[t] = (byte) (sPort >> 8); o[t + 1] = (byte) sPort;
            o[t + 2] = (byte) (cPort >> 8); o[t + 3] = (byte) cPort;
            Tun.writeInt(o, t + 4, seq);
            Tun.writeInt(o, t + 8, ack);
            int win = 65535;
            o[t + 14] = (byte) (win >> 8); o[t + 15] = (byte) win;
            o[t + 12] = (byte) (((20 + optLen) / 4) << 4);
            o[t + 13] = (byte) flags;
            int pos = t + 20;
            if (opts != null && optLen > 0) {
                System.arraycopy(opts, 0, o, pos, optLen);
                pos += optLen;
            }
            if (data != null && len > 0) System.arraycopy(data, off, o, pos, len);
            int tcpLen = 20 + optLen + len;
            int tcpCs = tcpChecksum(o, t, tcpLen);
            o[t + 16] = (byte) (tcpCs >> 8); o[t + 17] = (byte) tcpCs;
            int cs = Tun.checksum(o, 0, 20);
            o[10] = (byte) (cs >> 8); o[11] = (byte) cs;
            VpnDnsService.inject(o);
        } catch (Exception ex) {
            Log.w(TAG, "sendTcp", ex);
        }
    }

    static int tcpChecksum(byte[] p, int tcpOff, int tcpLen) {
        int sum = 0;
        int off = 12;
        sum += ((p[off] & 0xFF) << 8) | (p[off + 1] & 0xFF);
        sum += ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
        sum += ((p[off + 4] & 0xFF) << 8) | (p[off + 5] & 0xFF);
        sum += ((p[off + 6] & 0xFF) << 8) | (p[off + 7] & 0xFF);
        sum += 6;               // TCP protocol
        sum += tcpLen;          // pseudo-header length
        int i = tcpOff;
        int remain = tcpLen;
        while (remain > 1) {
            sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
            i += 2;
            remain -= 2;
        }
        if (remain == 1) sum += (p[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (~sum) & 0xFFFF;
    }
}