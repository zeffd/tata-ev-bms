# Tata EV BMS

A deliberately small Android app (**~125 KB APK, zero dependencies**) that reads
live battery data from Tata electric vehicles over a cheap Bluetooth ELM327
adapter — and helps find a weak cell group before it strands you.

Reverse-engineered on a 2023 Nexon EV Max; designed to work across Tata's EV
range (Nexon, Tiago, Punch, Curvv) because nothing model-specific is hardcoded.

> **Unofficial.** This project is not affiliated with or endorsed by Tata Motors.
> It is a diagnostic tool you run against your own car, at your own risk.

## What it shows

- **Live dashboard** — SOC, SOH, pack voltage, current, min/max cell voltage
  and which groups hold them, temperatures, the 12 V rail. Every tile is a real
  reading and carries the DID it was read from.
- **Pack map** — the app's reason to exist. The BMS only ever names its weakest
  and strongest cell group per sample; accumulated over a drive, that is enough
  to tell **charge imbalance** (fixed by a full charge) from **high internal
  resistance** (a service item) and to put a measured milliohm figure on the
  bad group. Verdicts are plain-language: *weak module*, *watch*, *low charge*.
- **All drives** — every logged drive replayed and tallied per group, so "the
  same group in 3 of 3 drives" is one screen, with a shareable findings report.
- **Alerts** while you drive: cell spread, weakest-cell floor, the *knee* (a
  group collapsing near empty), and a sagging 12 V rail — tone + vibration on
  the alarm stream, so Do Not Disturb doesn't eat them.
- **CSV logging** in a foreground service (screen off is fine). Every row also
  records the raw hex of every DID, so scalings can be re-derived later without
  re-driving, plus a `layout` marker so a replay knows how to read the file
  regardless of what version wrote it.
- **Multi-car** — profiles keyed by VIN, switched automatically on connect.

## Read-only by construction

The app never writes to the vehicle. Only these leave the phone:

- ELM327 `AT…` setup (adapter-local, never reaches the car)
- UDS **`0x22`** ReadDataByIdentifier — every actual measurement
- UDS **`0x10 0x03`** extended session, **`0x3E`** TesterPresent, and
  `0x10 0x01` on disconnect to leave the BMS in its default session

Enforcement is an **allowlist at the single choke point** every command passes
through (`ElmClient.raw()` → `CommandGuard`). Writes (`0x2E`), DTC clears
(`0x14`), resets (`0x11`), routines (`0x31`), IO control (`0x2F`) and
SecurityAccess (`0x27`) are refused before they reach the socket — even by typo.

## Requirements

- A Tata EV (developed on a Nexon EV Max; see below for other models)
- A Bluetooth **ELM327** OBD adapter (classic Bluetooth / SPP). WiFi and
  Bluetooth-LE-only adapters are not supported yet — see *Adapters*.
- Android 7.0+ (minSdk 24)

## Install

Grab the APK from the [Releases](../../releases) page, or build it yourself
(below). Then:

1. Pair the adapter in **Android's Bluetooth settings** first — the app
   connects to an already-paired device.
2. Close any other OBD app (Car Scanner etc.): these adapters accept **one**
   connection at a time.
3. Open the app with the car awake. The BMS is auto-detected; the car is
   named by VIN on first connect.

The APK is debug-signed on purpose (it is a sideloaded diagnostic tool);
installing a build signed by a different machine requires uninstalling first,
which deletes on-device logs — share them off the phone before switching.

## Adapters

Most "does not work" reports so far have been the adapter, not the car. Every
ELM327 sold under $10 is a clone, and some clones refuse commands the genuine
chip accepts. The app copes with the common ones — a missing receive filter
(replies are filtered in software) and missing flow control (long replies are
read as far as they arrive) — and records what the adapter refused at the top
of the **detection report**, so a failure can be diagnosed from one shared file.

If the dashboard stops at *Could not find a battery controller*, tap **Share
detection report** on that card and send the file. It names the adapter, the
commands it rejected, every protocol tried and every address that answered. It
contains no VIN.

What the app tries, in order, before giving up: the Nexon's 0x7xx addresses on
11-bit CAN at 500 kbaud, a broadcast to the whole bus, then 29-bit CAN at the
addresses Tata's own service tool uses for its battery controllers. The
250 kbaud variants of both come only on the retry, and only after a sweep in
which the car said nothing at all — a car that is not switched on yet looks
exactly like a 250 kbaud one, and probing 250 kbaud on a live 500 kbaud bus
puts error frames on it. A car whose battery controller answers on 29-bit is
remembered and reconnects directly next time.

Two kinds of battery controller are understood without any mapping: the
Gotion controller (Nexon EV and EV Max) and the TacoGotion / CESL / Kratos
family, which serves the same readings at different codes and scales. A model
with something else answers in unknown codes, and **Scan vehicle** maps it.

## On a Tata model with a different DID map

Nothing breaks: unknown DIDs read `--`, the cell-group count is **derived**
(pack voltage ÷ mean cell voltage), and **Scan vehicle** sweeps the BMS's DID
space on the car itself, suggests a mapping for the roles it can identify
reliably, and leaves genuinely ambiguous choices (pack volts vs cell millivolts
overlap numerically) to a human, with the coherent candidates listed.

## How the pack map works, in one paragraph

Each sample is one measurement: *group g sat d mV from the pack average while
I amps flowed*. Fitting a resistance to every group would be wrong — a group is
only sampled while it *is* an extreme, and that selection bias manufactures
negative resistances. So a milliohm figure is only fitted for groups seen at
**both** extremes across a wide current range (a resistive group must read
lowest under discharge and highest under regen — Ohm's law makes the swap
unfakeable), and everything else is classified by *when* it holds the minimum:
under load → suspect; at rest and never the maximum → low charge. Groups never
named at all are shown as exactly that, not painted a reassuring colour.

## Build

JDK 17; the Gradle wrapper fetches the rest (Android SDK 35 via `ANDROID_HOME`).

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Run the self-test — pure `javac`/`java`, no JUnit, no network, no device:

```bash
./run-selftest.sh   # 694 assertions against real captured frames
```

Pushing a `v*` tag builds the APK in CI and attaches it to a GitHub release.

## Project layout

```
app/src/main/java/com/tataev/bms/   the app - UI, service, protocol, pack map
selftest/                           test doubles + SelfTest.java
.github/workflows/release.yml       tag -> APK release
```

The UI is built programmatically (no XML layouts, no AndroidX, no Compose) —
the dashboard is generated from the field map, and that is how the APK stays
around 125 KB.

## License

[MIT](LICENSE).
