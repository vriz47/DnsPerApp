package com.dnsperapp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public final class DnsRelay {
    public interface Protector {
        DatagramSocket protect(DatagramSocket s) throws Exception;
    }

    /** DoH first, then DoT, fall back to raw UDP. */
    public static byte[] resolve(byte[] query, DnsServer srv, int timeoutMs, Protector protector) {
        if (srv == null) return null;
        if (srv.dohHost != null && srv.dohIp != null) {
            byte[] r = Doh.query(query, srv.dohHost, srv.dohIp, timeoutMs);
            if (r != null) return r;
        }
        if (srv.dotHost != null && srv.dotIp != null) {
            byte[] r = Doh.queryDot(query, srv.dotHost, srv.dotIp, timeoutMs);
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

    /** Rewrite 0.0.0.0 (blocked) A records to 127.0.0.1, mirroring Android's Private DNS sanitizer. */
    public static byte[] blockRewrite(byte[] resp) {
        if (resp == null || resp.length < 12) return resp;
        int found = 0;
        byte[] pat = {(byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01};
        int i = 12;
        while (i + 14 <= resp.length) {
            int idx = -1;
            for (int k = i; k + 4 <= resp.length; k++) {
                if (resp[k] == pat[0] && resp[k + 1] == pat[1]
                        && resp[k + 2] == pat[2] && resp[k + 3] == pat[3]) {
                    idx = k;
                    break;
                }
            }
            if (idx < 0) break;
            int rdLen = ((resp[idx + 8] & 0xFF) << 8) | (resp[idx + 9] & 0xFF);
            if (rdLen == 4 && idx + 14 <= resp.length
                    && resp[idx + 10] == 0 && resp[idx + 11] == 0 && resp[idx + 12] == 0 && resp[idx + 13] == 0) {
                resp[idx + 10] = 127;
                resp[idx + 11] = 0;
                resp[idx + 12] = 0;
                resp[idx + 13] = 1;
                found++;
            }
            i = idx + 4;
        }
        if (found > 0) {
            android.util.Log.i("DnsPerApp.DnsRelay", "blockRewrite: " + found + " -> 127.0.0.1");
        }
        return resp;
    }
}