# DNS Per App

A finished Android app to route a different DNS server per app. Pick a DNS server, choose which apps get it — everything else stays on the device's normal network.

Works on **Android 7+ (ARM64)**, tested on POCO F6 / Android 16 (HyperOS).

## Download

Latest APK: [v0.1.0](https://github.com/vriz47/DnsPerApp/releases/latest)

Install directly from the downloaded file; root mode additionally needs `com.dnsperapp` granted in the KernelSU (or Magisk) superuser list.

## Features

- **6 built-in DNS servers + custom IP** — AdGuard, AdGuard Family, Cloudflare, Google, Quad9, OpenDNS.
- **Two modes:**
  - **Root** — transparent per-UID redirect via `iptables`; per-app servers supported.
  - **VPN (no root)** — lightweight DNS-only `VpnService` on a private TUN; global server for selected apps.
- **DoH-first relay** — HTTPS (RFC 8484) tried first (works even when UDP:53 is blocked), falls back to UDP.
- Indonesia-first UI, zero external dependencies.

## Layout

```
app/src/main/
  java/com/dnsperapp/
    MainActivity.java        dashboard
    AppsActivity.java        per-app editor (search, switch, DNS override)
    ControlReceiver.java     headless START/STOP broadcasts
    RootDns.java             iptables + UID proxy (root mode)
    VpnDnsService.java       DNS-only VpnService (non-root mode)
    DnsRelay.java            DoH-first + UDP fallback upstream proxy
    Doh.java                 minimal RFC 8484 DoH client
    Prefs.java               per-app overrides + settings
  res/                       layouts, drawables, values
```