package com.dnsperapp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal DNS-over-HTTPS client (RFC 8484) using only platform classes.
 * POST /dns-query with application/dns-message, TLS via SSLSocket, SNI + hostname
 * verification. Returns raw DNS response bytes, or null on any failure/timeout.
 */
public final class Doh {

    public static byte[] queryDot(byte[] dnsQuery, String host, String ip, int timeoutMs) {
        SSLSocket s = null;
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            s = (SSLSocket) f.createSocket();
            SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            s.setSSLParameters(p);
            s.connect(new InetSocketAddress(ip, 853), timeoutMs);
            s.setSoTimeout(timeoutMs);

            OutputStream out = s.getOutputStream();
            byte[] len = new byte[]{(byte) (dnsQuery.length >> 8), (byte) (dnsQuery.length & 0xFF)};
            out.write(len);
            out.write(dnsQuery);
            out.flush();

            DataInputStream in = new DataInputStream(s.getInputStream());
            int n;
            try {
                n = in.readUnsignedShort();
            } catch (java.io.EOFException e) {
                return null;
            }
            if (n <= 0 || n > 65535) return null;
            byte[] resp = new byte[n];
            in.readFully(resp);
            if (resp.length < 12) return null;
            if ((resp[2] & 0x80) == 0) return null;
            return resp;
        } catch (IOException e) {
            android.util.Log.w("DnsPerApp.Doh", "dot fail " + e);
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static byte[] query(byte[] dnsQuery, String host, String ip, int timeoutMs) {
        SSLSocket s = null;
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            s = (SSLSocket) f.createSocket();
            SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            s.setSSLParameters(p);
            s.connect(new InetSocketAddress(ip, 443), timeoutMs);
            s.setSoTimeout(timeoutMs);

            OutputStream out = s.getOutputStream();
            StringBuilder req = new StringBuilder(200);
            req.append("POST /dns-query HTTP/1.1\r\n")
               .append("Host: ").append(host).append("\r\n")
               .append("Content-Type: application/dns-message\r\n")
               .append("Accept: application/dns-message\r\n")
               .append("Content-Length: ").append(dnsQuery.length).append("\r\n")
               .append("Connection: close\r\n\r\n");
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(dnsQuery);
            out.flush();

            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            StringBuilder head = new StringBuilder(2048);
            int headerEnd = -1;
            while (head.length() < 65536) {
                int r = in.read(buf);
                if (r < 0) return null;
                head.append(new String(buf, 0, r, StandardCharsets.ISO_8859_1));
                int idx = head.indexOf("\r\n\r\n");
                if (idx >= 0) {
                    headerEnd = idx + 4;
                    break;
                }
            }
            if (headerEnd < 0) return null;

            String h = head.toString();
            String status = h.substring(0, h.indexOf("\r\n"));
            if (!status.contains(" 200")) return null;

            int contentLength = -1;
            boolean chunked = false;
            for (String line : h.substring(0, headerEnd).split("\r\n")) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim().toLowerCase(Locale.US);
                String v = line.substring(c + 1).trim();
                if (k.equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(v);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (k.equals("transfer-encoding") && v.toLowerCase(Locale.US).contains("chunked")) {
                    chunked = true;
                }
            }

            byte[] leftovers = h.substring(headerEnd).getBytes(StandardCharsets.ISO_8859_1);
            // First serve bytes already read past the header, then the socket.
            InputStream body = new JoinedStream(new ByteArrayInputStream(leftovers), in);
            ByteArrayOutputStream outBody = new ByteArrayOutputStream(512);
            byte[] tmp = new byte[4096];

            if (chunked) {
                while (true) {
                    String sizeLine = readLine(body);
                    if (sizeLine == null) break;
                    int semi = sizeLine.indexOf(';');
                    if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
                    int sz;
                    try {
                        sz = Integer.parseInt(sizeLine.trim(), 16);
                    } catch (NumberFormatException e) {
                        break;
                    }
                    if (sz == 0) break; // trailers ignored; Connection: close
                    int got = 0;
                    while (got < sz) {
                        int r = body.read(tmp, 0, Math.min(tmp.length, sz - got));
                        if (r < 0) break;
                        outBody.write(tmp, 0, r);
                        got += r;
                    }
                    if (got != sz) return null;
                    body.read(); // \r
                    body.read(); // \n
                }
            } else {
                long remaining = contentLength >= 0 ? contentLength : Long.MAX_VALUE;
                while (remaining > 0) {
                    int want = (int) Math.min(tmp.length, remaining);
                    int r = body.read(tmp, 0, want);
                    if (r < 0) break;
                    outBody.write(tmp, 0, r);
                    remaining -= r;
                }
            }

            byte[] resp = outBody.toByteArray();
            if (resp.length < 12) return null;
            // sanity: DNS response (QR bit) must be set
            if ((resp[2] & 0x80) == 0) return null;
            return resp;
        } catch (IOException e) {
            android.util.Log.w("DnsPerApp.Doh", "doh fail " + e);
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            line.write(b);
        }
        if (b < 0 && line.size() == 0) return null;
        String s = new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
        if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Concatenation of two streams (a until exhausted, then b). */
    private static final class JoinedStream extends InputStream {
        private InputStream a;
        private InputStream b;

        JoinedStream(InputStream a, InputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public int read() throws IOException {
            int r = a.read();
            if (r >= 0 || a == b) return r;
            swap();
            return a.read();
        }

        @Override
        public int read(byte[] out, int off, int len) throws IOException {
            int n = a.read(out, off, len);
            if (n > 0 || a == b) return n;
            swap();
            return a.read(out, off, len);
        }

        private void swap() {
            InputStream t = a;
            a = b;
            b = t;
        }
    }
}