package com.dnsperapp;

public final class Tun {
    public static final int VIRTUAL_IP = toInt(10, 1, 10, 2);

    public static final class DnsQuery {
        public final int clientIp;      // source IP of the app
        public final int clientPort;    // source UDP port of the app
        public final byte[] payload;    // raw DNS message
        public DnsQuery(int ip, int port, byte[] payload) {
            this.clientIp = ip;
            this.clientPort = port;
            this.payload = payload;
        }
    }

    /** Parse a raw IPv4/UDP packet addressed to our virtual DNS:53. Returns null if not DNS. */
    public static DnsQuery parseDnsQuery(byte[] p, int len) {
        if (len < 28) return null;
        int version = (p[0] >> 4) & 0xF;
        if (version != 4) return null;
        int ihl = (p[0] & 0xF) * 4;
        if (ihl < 20 || len < ihl + 8) return null;
        int total = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        if (total > len) total = len;
        if (total < ihl + 8) return null;
        int protocol = p[9] & 0xFF;
        if (protocol != 17) return null; // UDP only (TCP DNS not handled in MVP)
        int dst = toInt(p, 16);
        if (dst != VIRTUAL_IP) return null;
        int sport = ((p[ihl] & 0xFF) << 8) | (p[ihl + 1] & 0xFF);
        int dport = ((p[ihl + 2] & 0xFF) << 8) | (p[ihl + 3] & 0xFF);
        if (dport != 53) return null;
        int ulen = ((p[ihl + 4] & 0xFF) << 8) | (p[ihl + 5] & 0xFF);
        int payloadLen = ulen - 8;
        int start = ihl + 8;
        if (payloadLen <= 0 || start + payloadLen > total) payloadLen = total - start;
        if (payloadLen <= 0) return null;
        int src = toInt(p, 12);
        byte[] payload = new byte[payloadLen];
        System.arraycopy(p, start, payload, 0, payloadLen);
        return new DnsQuery(src, sport, payload);
    }

    /** Build IPv4/UDP packet: response from virtual DNS:53 back to clientIp:clientPort. */
    public static byte[] buildResponse(int clientIp, int clientPort, byte[] dnsResponse) {
        int ipLen = 20, udpLen = 8;
        int total = ipLen + udpLen + dnsResponse.length;
        byte[] o = new byte[total];
        o[0] = (byte) 0x45;
        o[2] = (byte) ((total >> 8) & 0xFF);
        o[3] = (byte) (total & 0xFF);
        o[8] = 64;              // TTL
        o[9] = 17;              // UDP
        writeInt(o, 12, clientIp);
        writeInt(o, 16, VIRTUAL_IP);
        int ipHeaderChecksum = checksum(o, 0, ipLen);
        o[10] = (byte) ((ipHeaderChecksum >> 8) & 0xFF);
        o[11] = (byte) (ipHeaderChecksum & 0xFF);
        int u = ipLen;
        o[u] = 0; o[u + 1] = 53; // sport
        o[u + 2] = (byte) ((clientPort >> 8) & 0xFF);
        o[u + 3] = (byte) (clientPort & 0xFF);
        int udpTotal = udpLen + dnsResponse.length;
        o[u + 4] = (byte) ((udpTotal >> 8) & 0xFF);
        o[u + 5] = (byte) (udpTotal & 0xFF);
        o[u + 6] = 0; o[u + 7] = 0; // UDP checksum 0 (valid for IPv4)
        System.arraycopy(dnsResponse, 0, o, u + 8, dnsResponse.length);
        return o;
    }

    static int toInt(int a, int b, int c, int d) {
        return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((c & 0xFF) << 8) | (d & 0xFF);
    }

    static int toInt(byte[] p, int off) {
        return ((p[off] & 0xFF) << 24) | ((p[off + 1] & 0xFF) << 16)
                | ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
    }

    static void writeInt(byte[] o, int off, int v) {
        o[off] = (byte) (v >> 24);
        o[off + 1] = (byte) (v >> 16);
        o[off + 2] = (byte) (v >> 8);
        o[off + 3] = (byte) v;
    }

    /** Internet checksum (RFC 1071) over length bytes starting at off. */
    static int checksum(byte[] p, int off, int length) {
        int sum = 0;
        int i = off;
        while (length > 1) {
            sum += ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
            i += 2;
            length -= 2;
        }
        if (length == 1) sum += (p[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (~sum) & 0xFFFF;
    }
}