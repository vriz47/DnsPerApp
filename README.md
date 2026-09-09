# DNS Per App

Route a different DNS server per Android app. Pick a global DNS server, choose which apps bypass it — the rest of the device stays on your normal network.

Built for **POCO F6 / Android 16 (HyperOS, KernelSU root)**, but works on any ARM64 Android 7+ device.

## Features

- **6 built-in DNS servers + custom IP** — AdGuard, AdGuard Family, Cloudflare, Google, Quad9, OpenDNS.
- **Two working modes:**
  - **Root** — transparent per-UID redirect via `iptables` (works for every app, per-app servers supported).
  - **VPN (no root)** — lightweight DNS-only `VpnService` on a private TUN; one global server applies to selected apps.
- **DoH-first relay** — HTTPS (RFC 8484) is tried first (works on networks where UDP:53 is blocked), falls back to UDP.
- Per-app server override in root mode (DNS index per package).
- Indonesia-first UI, no external dependencies (pure AOSP widgets).

## How it works

- **Root mode** creates an `iptables` chain (`DNS_PER_APP`), `REDIRECT`s UDP:53 per selected UID to a local proxy, which queries the chosen upstream.
- **VPN mode** builds a /24 TUN (`10.1.10.2`), allows only the selected packages, and answers every DNS packet from an internal relay bound to `127.0.0.1:5353`.
- A shared relay (`DnsRelay`) resolves via a minimal RFC 8484 DoH client (`Doh`, `SSLSocket` + SNI + HTTPS endpoint id), then UDP fallback.

## Build

No Gradle — a pure CLI pipeline (`aapt2` → `javac` → `d8` → `jar`/`zipalign` → `apksigner`), handy inside Termux.

Prerequisites (Termux):

```sh
pkg install openjdk-21 openjdk-21-javac openjdk-21-d8 -y
# Android SDK with build-tools 36.1.0 and platform android-35 installed
```

Build:

```sh
./build.sh        # outputs ./app-debug.apk (signed with debug.keystore)
```

## Install

```sh
su -c "pm install -r app-debug.apk"
```

Root mode needs `com.dnsperapp` granted in the KernelSU (or Magisk) superuser list.

## Layout

```
app/src/main/
  AndroidManifest.xml        permissions, <queries>, receiver, service
  java/com/dnsperapp/
    MainActivity.java        dashboard
    AppsActivity.java        per-app editor (search, switch, DNS override)
    ControlReceiver.java     headless START/STOP broadcasts
    RootDns.java             iptables + UID proxy (root mode)
    VpnDnsService.java       DNS-only VpnService (non-root mode)
    DnsRelay.java            DoH-first + UDP fallback upstream proxy
    Doh.java                 minimal RFC 8484 DoH client
    Prefs.java               per-app overrides + settings
  res/                       layouts, drawables, values (light theme)
```

## Notes

- Per-app servers require root mode (the TUN path cannot see app UIDs).
- Runs on the Smartfren test network where public UDP:53 is blocked — that is why the upstream relay is DoH-first.