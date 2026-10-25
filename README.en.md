<p align="center">
  <a href="README.md">简体中文</a> · <b>English</b>
</p>

<p align="center">
  <img src="docs/app-icon.png" width="96" alt="Mine App">
  &nbsp;&nbsp;
  <img src="docs/app-icon-clover.png" width="96" alt="Mine App">
</p>

<h1 align="center">Mine App · App Manager</h1>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-1.3.2-0F6E56">
  <img alt="APK" src="https://img.shields.io/badge/APK-525%20KB-0F6E56">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-26-0F6E56">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-35-0F6E56">
  <img alt="license" src="https://img.shields.io/badge/license-GPL--3.0-0F6E56">
</p>

> **Mine App is a featherweight Android app manager that brings sluggish devices back to life — without root or heavy frameworks.**
>
> - **Zero Bloat, Zero Dependencies**: Built without Gradle overhead; operates completely independent of Root or Shizuku services. Ultra-compact footprint with <1MB APK size and <10MB total storage footprint (including runtime caches).
> - **Paradoxical Energy Efficiency**: Pair a sustained performance mode with aggressive background freezes and restriction policies. Cut off stealthy background resource grabs to minimize power drain while ensuring maximum foreground responsiveness.
> - **Low-Level Acceleration**: Built-in Android AOT Speed batch compilation compiles DEX to native code, eliminating JIT stutters and dramatically accelerating cold-start times on low-spec hardware.

---

## What is this

The usual way to keep an unused app completely silent on Android is to freeze it. Most solutions
require root, or drag in a whole stack of Shizuku / Ice Box / Hail. Mine App takes a different route:

**The phone connects to its own adbd.**

Run `adb tcpip 5555` **once, at install time**, over a USB cable or through Wireless debugging.
That makes adbd listen on `127.0.0.1:5555`. The app ships a minimal ADB client, authenticates with a
key kept in its private storage, and then talks to `pm` / `am` / `cmd` directly.

**After that the PC is out of the picture** — freezing, restricting, AOT compilation and performance
profiles are all a few taps on the phone, with no adb tooling involved. As long as you do not reboot,
that single `adb tcpip` keeps working; after a reboot, just run it once more.

<p align="center">
  <img src="docs/main-en.jpg" width="320" alt="Main screen">
</p>

### Compared to the usual options

| | **Mine App** | Built-in "Force stop" | Ice Box | Hail |
|---|---|---|---|---|
| Needs root | **No** | No | No | No |
| Requires Shizuku | **No ✓** | No | Yes | Yes |
| Per-app deployment | **Yes ✓** | — | No | No |
| Needs a background daemon | **No ✓** | No | Yes (Shizuku running) | Yes (Shizuku running) |
| APK size | **< 1MB ✓** | Built in | Several MB | Several MB |
| Batch freeze / unfreeze | **Yes** | — | Yes | Yes |
| Survives a reboot | **Yes** | No | Yes | Yes |
| Performance profile tuning | **Yes ✓** | — | — | — |
| Per-app AOT compilation | **Yes ✓** | — | — | — |
| PSS memory sorting | **Yes ✓** | — | — | — |

> **✓** marks where Mine App has the edge.
> "Per-app deployment" means freezing / restricting / AOT-compiling one specific app without
> touching the others.
> The Ice Box / Hail columns are based on their public documentation and common usage;
> refer to their own docs for details.

## Features

| Feature | What it does |
|---|---|
| **Archive (freeze)** | `pm disable-user` — the app never runs, disappears from recents, launcher icon greys out |
| **Restrict** | Restricts background activity; milder than freezing, the app can still be launched |
| **AOT compile** | `cmd package compile -m speed` — pre-compiles dex to native code for faster cold starts; **per-app or in bulk**, with a live percentage |
| **Performance profile tuning** | One-tap system performance governor, automatically re-applied if the ROM reverts it |
| **Memory sort** | Sort by PSS to see exactly what is eating RAM; live aggregate of the selected apps' memory |
| **Batch actions** | Select many apps, then archive / restore / restrict / unrestrict in one go |
| **Ongoing notification** | Battery health, memory usage and archive count in the status bar |
| **Appearance** | 6 icon sets (blocks / clover × green / gold / black), two app names, light & dark theme, Chinese & English |
| **List cache** | The fetched list is cached locally; re-entering within a minute does not refetch |

<p align="center">
  <img src="docs/settings-en.jpg" width="320" alt="Settings">
</p>

<p align="center">
  <img src="docs/icon-set.png" width="760" alt="Six icon sets">
</p>

## Requirements

- Android 8.0 (API 26) or newer, targetSdk 35
- Developer options → **USB debugging** enabled
- No root

## Install

1. Grab `app-manager.apk` (~525 KB) from [Releases](https://github.com/henry-97/MineApp/releases)
2. Or build it yourself (see below)

### First-time setup (once only)

The app talks to the adbd listening on **port 5555 of the same device**, so adbd has to be told to
listen on TCP. **Do this once, at install time:**

```bash
# Plug the phone into a PC once (Wireless debugging works too)
adb tcpip 5555

# Unplug. From here on it is all taps inside the app — no more PC
```

**After a reboot** the port closes — just run `adb tcpip 5555` once more.

> Wireless debugging is only an alternative *transport* for running that one command
> (no USB cable needed) — the app itself cannot talk to it, see Known limitations.

On first launch the phone shows the ADB authorisation dialog — tick "Always allow" and you are done.

## Build from source

No Gradle, no Android Studio — just JDK + Android SDK build-tools + python3:

```bash
git clone https://github.com/henry-97/MineApp.git
cd MineApp/appmanager

cp local.env.example local.env     # point it at your SDK / JDK
bash build.sh                      # output: out/app-manager.apk
```

You need:

- JDK 17
- Android SDK: `build-tools;35.0.0`, `platforms;android-35`
- Python 3 + Pillow (for icon generation)

`build.sh` generates a self-signed key on first run, so there is nothing to prepare.
Every path can be overridden via environment variables — see the header of `build.sh`.

> An APK you build yourself is signed with a different key and **cannot be installed over** the
> official release — uninstall first. Conversely, nobody can ship an update over the official
> release either. That is exactly what a release key is for.

## Project layout

```
MineApp/
├── appmanager/                   Android project (no Gradle)
│   ├── build.sh                  aapt2 → javac → d8 → zipalign → apksigner
│   ├── src/java/.../
│   │   ├── AdbClient.java        ADB protocol (CNXN/AUTH/OPEN/WRTE/OKAY/CLSE)
│   │   ├── AdbService.java       Business layer: list, freeze, restrict, AOT, battery, memory
│   │   ├── Bridge.java           JS ↔ Java bridge, single-threaded
│   │   ├── MainActivity.java     WebView host
│   │   └── KeepAliveService.java Ongoing notification
│   ├── res/                      Resources (icons generated by tools/gen_icon.py)
│   ├── assets/                   Front-end bundled into the APK
│   └── tools/
│       ├── gen_assets.py         Extracts the UI from server.py + package name map
│       ├── gen_icon.py           Generates the 6 adaptive icon sets
│       └── gen_manifest.py       Generates the 12 activity-aliases
└── termux/
    └── webui/server.py           Single source of truth for the UI (also the Termux server)
```

**There is only one copy of the front-end.** `server.py` embeds the whole HTML/CSS/JS;
`gen_assets.py` extracts it verbatim and injects a `fetch → Java bridge` shim. The Termux build and
the APK therefore share identical UI logic — no second implementation to keep in sync.

## Known limitations

- **Changing the icon or name kills the app process.** Android cannot change `label`/`icon` at
  runtime, so this is done by switching `activity-alias` components. Disabling the alias that is
  currently running makes the system finish the task, so the app restarts once (the list is cached,
  so it comes back instantly).
- **Port 5555 closes on reboot.** Run `adb tcpip 5555` once more.
  `persist.adb.tcp.port` cannot be written without root (SELinux blocks the shell user), so there is
  no way to make it survive a reboot.
- **Wireless debugging cannot replace the app's own connection.** The app speaks plain ADB to
  `127.0.0.1:5555`; Android 11+ Wireless debugging uses **TLS + a pairing code** on a random port,
  which the app cannot connect to. Treat Wireless debugging only as a *cable-free way to run*
  `adb tcpip 5555` once, from a PC or another device with adb.
  Verified: port 5555 keeps listening whether Wireless debugging is on or off, and the app works in both cases.
- **Some ROMs are stricter.** On Xiaomi/Redmi, disable the install confirmation first:
  `adb shell settings put global adb_install_need_confirm 0`.
- A frozen app's launcher icon greys out rather than disappearing; hiding it completely needs
  launcher support.

## Sponsorship

If this project helped you, you can buy me a coffee. **Entirely optional — no feature is gated.**

<div align="center">
  <table>
    <tr>
      <td align="center"><b>WeChat Pay</b></td>
      <td align="center"><b>Alipay</b></td>
    </tr>
    <tr>
      <td><img src="docs/sponsor-wechat.png" width="260" alt="WeChat Pay"></td>
      <td><img src="docs/sponsor-alipay.jpg" width="260" alt="Alipay"></td>
    </tr>
  </table>
</div>

Issues and pull requests are just as welcome — they help a lot too.

## License

[GPL-3.0](LICENSE).

In short: use it, modify it, redistribute it — but **derivatives must stay open source**. You may not
repackage it with ads and ship it closed-source. If you need it inside a proprietary product, open an
issue to discuss a licence.

## Disclaimer

This project drives the system `pm` / `am` / `cmd` commands directly through ADB, which makes it
**powerful**. Freezing or restricting system apps can break alarms, push notifications or the
launcher. Only touch apps whose behaviour you understand, and recover with
`adb shell pm enable <package>` if something goes wrong. The author is not responsible for any data
loss or device misbehaviour.
