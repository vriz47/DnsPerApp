package com.dnsperapp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public final class DnsRelay {
    public interface Protector {
        DatagramSocket protect(DatagramSocket s) throws Exception;
    }

    /** DoH first (works even where upstream UDP:53 is blocked), fall back to raw UDP. */
    public static byte[] resolve(byte[] query, DnsServer srv, int timeoutMs, Protector protector) {
        if (srv == null) return null;
        if (srv.dohHost != null && srv.dohIp != null) {
            byte[] r = Doh.query(query, srv.dohHost, srv.dohIp, timeoutMs);
            if (r != null) return r;
        }
        return resolveUdp(query, srv.ip, timeoutMs, protector);
    }

    /** Raw UDP DNS to a fixed IP:53 (for custom servers / fallback). */
    public static byte[] resolveUdp(byte[] query, String serverIp, int timeoutMs, Protector protector) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(timeoutMs);
            if (protector != null) {
                s = protector.protect(s);
            }
            InetAddress addr = InetAddress.getByName(serverIp);
            s.send(new DatagramPacket(query, query.length, addr, 53));
            byte[] buf = new byte[65535];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            s.receive(r);
            byte[] out = new byte[r.getLength()];
            System.arraycopy(buf, 0, out, 0, r.getLength());
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) { }
            }
        }
    }
}