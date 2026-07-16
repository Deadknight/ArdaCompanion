# ARDA Reverse Companion — RN-2 Compose

Independent Android companion project for the RN-2 dual-network proof.

## Package

```text
com.github.deadknight.ardacompanion
```

Debug application ID:

```text
com.github.deadknight.ardacompanion.debug
```

## UI

The project now uses:

```text
Kotlin
Jetpack Compose
Material 3
single-activity architecture
```

There is no XML layout and no Java Activity.

## RN-2 purpose

The app proves that the phone can use two networks simultaneously:

```text
Wi-Fi Network
  -> AAOS ARDA-RN2 local-only hotspot
  -> local HTTP socket

Cellular Network
  -> public HTTPS
```

It does not use Gearhead and does not call `bindProcessToNetwork()`.

## Toolchain

```text
Android Gradle Plugin: 8.13.2
Gradle: 8.13
Kotlin: 2.3.21
Compose compiler plugin: 2.3.21
Compose BOM: 2026.04.01
Activity Compose: 1.12.4
JDK: 17
compileSdk / targetSdk: 36
minSdk: 29
```

## Build

Open `ArdaCompanion` in Android Studio.

Command line:

```bash
chmod +x ./gradlew ./build-debug-mac.sh ./install-debug-mac.sh
./build-debug-mac.sh
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
./install-debug-mac.sh
```

## RN-2 run order

1. Keep phone mobile data enabled.
2. Run `arda-rn2-aaos-dual-network-proof-usb.sh` on the Mac.
3. Connect the phone to the printed `ARDA-RN2` SSID.
4. Keep the connection when Android warns that Wi-Fi has no internet.
5. Open ARDA Reverse Companion.
6. Leave AAOS host blank for automatic gateway discovery.
7. Keep port `5278`.
8. Press **Run dual-network proof**.
9. Copy the marker log.
10. Return to the Mac script and press Enter for evidence collection/restore.

Expected final markers:

```text
ARDA_RN2_WIFI_NETWORK=PASS
ARDA_RN2_CELLULAR_NETWORK=PASS
ARDA_RN2_WIFI_LOCAL_SOCKET=PASS
ARDA_RN2_CELLULAR_HTTPS=PASS
ARDA_RN2_PHONE_DUAL_NETWORK_GATE=PASS
```

## Scope boundary

This RN-2 app does not yet implement:

```text
automatic Wi-Fi joining
pairing/authentication
persistent reverse tunnel
TUN/VPN
TCP/UDP multiplexing
DNS relay
boot-start service
```
