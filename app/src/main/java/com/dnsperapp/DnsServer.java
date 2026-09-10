package com.dnsperapp;

/** Resolved DNS server config: raw UDP endpoint (ip:53), optional DoH endpoint, optional DoT endpoint. */
public final class DnsServer {
    public final String name;
    public final String ip;
    public final String dohHost;
    public final String dohIp;
    public final String dotHost;
    public final String dotIp;

    public DnsServer(String name, String ip, String dohHost, String dohIp) {
        this(name, ip, dohHost, dohIp, null, null);
    }

    public DnsServer(String name, String ip, String dohHost, String dohIp, String dotHost, String dotIp) {
        this.name = name;
        this.ip = ip;
        this.dohHost = dohHost;
        this.dohIp = dohIp;
        this.dotHost = dotHost;
        this.dotIp = dotIp;
    }
}