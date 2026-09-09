package com.dnsperapp;

/** Resolved DNS server config: raw UDP endpoint (ip:53) plus optional DoH endpoint. */
public final class DnsServer {
    public final String name;
    public final String ip;
    public final String dohHost;
    public final String dohIp;

    public DnsServer(String name, String ip, String dohHost, String dohIp) {
        this.name = name;
        this.ip = ip;
        this.dohHost = dohHost;
        this.dohIp = dohIp;
    }
}