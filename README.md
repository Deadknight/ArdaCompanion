# ARDA Companion

Android companion application for **ARDA**, a Raspberry Pi / Android Automotive OS integration project.

ARDA Companion provides the phone-side bootstrap and networking path used to connect an Android phone to the ARDA AAOS head unit without relying on Android Auto/Gearhead networking.

The current project contains two related layers:

- **RN-3B companion transport** — Bluetooth bootstrap, Companion Device presence handling, saved local-only Wi-Fi enrollment/auto-join, and an optional cellular-backed reverse HTTP proxy.
- **RN-2 dual-network probe** — an interactive diagnostic screen that proves Wi-Fi-local and cellular-internet sockets can operate at the same time without globally rebinding the application process.

> The repository originally started as the RN-2 “Reverse Companion” proof. The current source has evolved substantially beyond that initial README and now includes the RN-3B background/session architecture.

---

## What it does

The normal RN-3B flow is:

```text
AAOS / ARDA
    │
    │ Bluetooth RFCOMM
    ▼
ARDA Companion
    │
    ├── receives local-only Wi-Fi credentials from AAOS
    │
    ├── saves / joins the ARDA Wi-Fi network
    │
    ├── keeps the local AAOS path bound to Wi-Fi
    │
    └── optionally opens a cellular-backed HTTP proxy
              │
              ▼
           Internet
```

The important networking rule is:

```text
AAOS-local traffic  -> phone Wi-Fi Network
Internet egress     -> phone cellular Network
```

The app does **not** globally bind its process to cellular. Individual sockets are bound to the appropriate Android `Network`.

---

# Architecture

## RN-3B bootstrap path

The RN-3B implementation uses Bluetooth RFCOMM as the bootstrap/control channel.

Service UUID:

```text
e43cd6de-4bb2-49fe-8d43-ecace91038cc
```

High-level session:

```text
Bluetooth association / presence
          │
          ▼
ArdaBootstrapService
          │
          ▼
ArdaRfcommClient
          │
          ├── HELLO
          ├── HELLO_ACK
          ├── PING / PONG
          ├── WIFI_REQUEST
          ├── WIFI_OFFER
          │
          ▼
ReverseProxyController
          │
          ├── saved Wi-Fi / local-only connection
          └── optional cellular Network
                    │
                    ▼
           local HTTP proxy
          │
          ▼
PROXY_READY / PROXY_RESULT
```

Once the session is established, RFCOMM remains open and periodic PING/PONG keepalives are sent.

---

## Main components

### `ArdaBluetoothProtocol`

Defines the framed RFCOMM protocol.

Frame layout:

```text
uint32 little-endian body length
uint8  protocol version
uint8  operation
uint64 request id
bytes   UTF-8 JSON payload
```

Current protocol version:

```text
1
```

Maximum frame size:

```text
64 KiB
```

Defined operations include:

```text
HELLO / HELLO_ACK
PING / PONG
SESSION_STOP

WIFI_REQUEST / WIFI_OFFER

PROXY_READY
PROXY_RESULT
PROXY_STATUS
PROXY_STOP

PLATFORM_WIFI_INFO
PLATFORM_WIFI_ACK

ERROR
```

Not every defined operation is necessarily used by the current phone-side session flow.

---

### `ArdaRfcommClient`

Owns the active phone-side RFCOMM session.

Current sequence:

```text
connect RFCOMM
    ↓
HELLO
    ↓
HELLO_ACK
    ↓
PING
    ↓
PONG
    ↓
WIFI_REQUEST
    ↓
WIFI_OFFER
    ↓
establish local Wi-Fi
    ↓
optional cellular proxy
    ↓
PROXY_READY
    ↓
PROXY_RESULT
    ↓
keepalive PING/PONG
```

The client retries RFCOMM connection attempts before failing the session.

The AAOS peer must report:

```json
{
  "wifi_bootstrap_supported": true
}
```

in the HELLO acknowledgement before the Wi-Fi bootstrap path continues.

---

### `ArdaBootstrapService`

A **session-scoped foreground service** that owns the active transport.

It is deliberately not an always-running Bluetooth listener.

The service can be started from:

- Companion Device presence callbacks
- Bluetooth ACL connection broadcasts
- the bootstrap Activity
- a user notification fallback when Android blocks background foreground-service start

It resolves the target AAOS Bluetooth address using, in order:

```text
explicit Intent address
saved address
CompanionDeviceManager association
bonded ARDA/Raspberry-like Bluetooth device
```

The service stops when the ARDA transport session ends or the associated Bluetooth device disconnects.

---

### `ArdaCompanionDeviceService`

Uses Android `CompanionDeviceService` presence callbacks.

On supported Android versions, this allows the system to notify the application when an already-associated AAOS Bluetooth device appears even if the normal app process is not already running.

```text
device appeared
    ↓
start ARDA session

device disappeared
    ↓
stop ARDA session
```

---

### `ArdaBluetoothConnectionReceiver`

Receives:

```text
BluetoothDevice.ACTION_ACL_CONNECTED
BluetoothDevice.ACTION_ACL_DISCONNECTED
```

Only the saved/associated ARDA device is accepted.

Other Bluetooth devices are ignored.

This acts as an additional process-free trigger for starting and stopping the session.

---

### `ArdaCompanionPresence`

Registers the associated AAOS Bluetooth device for Android-managed companion-device presence observation.

This is used together with `CompanionDeviceService` rather than keeping a permanent foreground Bluetooth listener alive.

---

### `BluetoothAssociationController`

Uses `CompanionDeviceManager` to associate the phone with the AAOS/Raspberry Pi device.

Discovery currently favors Bluetooth device names matching:

```text
Raspberry
RPI
ARDA
Automotive
```

The selected Bluetooth MAC address is persisted for later background sessions.

---

### `BluetoothBootstrapActivity`

Short-lived user-interaction Activity for tasks Android may require foreground UI for.

It handles:

- Bluetooth runtime permissions
- first association
- manual connect/disconnect
- saved Wi-Fi enrollment UI
- automatic continuation when launched by a background trigger
- automatic finish once the ARDA session becomes ready

The Activity is **not** intended to own the long-running transport. That responsibility belongs to `ArdaBootstrapService`.

---

# Local-only Wi-Fi

AAOS sends a `WIFI_OFFER` over RFCOMM.

The phone-side representation contains:

```text
SSID
passphrase
security mode
optional BSSID
AAOS AP IP
requested proxy port
```

`ReverseProxyController` then attempts to establish the local Wi-Fi path.

## Connection strategy

The implementation prefers Android-managed saved-network behavior:

```text
1. Check whether the requested saved network is already connected.
2. If not, ensure the network has been saved/approved.
3. Give Android auto-join a short opportunity.
4. If necessary, issue an explicit local-only WifiNetworkSpecifier request.
```

This is different from the original RN-2 proof, where the user manually connected to the hotspot.

### First-time enrollment

Android's saved-network approval UI must be launched by a foreground Activity.

`ArdaWifiEnrollmentCoordinator` bridges that UI requirement with the background RFCOMM worker:

```text
RFCOMM worker
    │
    │ waits
    ▼
ArdaWifiEnrollmentCoordinator
    │
    ▼
BluetoothBootstrapActivity
    │
    ▼
ACTION_WIFI_ADD_NETWORKS
    │
    ▼
user approves network
    │
    ▼
RFCOMM session continues
```

If no Activity is currently available, ARDA posts an interaction notification so the user can complete Wi-Fi enrollment without discarding the RFCOMM session.

Approved network identity is cached by:

```text
SSID + security + passphrase
```

so later sessions can normally use Android's saved-network behavior.

---

# Cellular reverse proxy

After the local Wi-Fi path is established, ARDA optionally requests a cellular `Network`.

If cellular is available, the phone starts an HTTP proxy bound to the phone's **Wi-Fi-side local address** while outbound sockets are explicitly bound to the **cellular Network**.

Conceptually:

```text
AAOS
  │
  │ local-only Wi-Fi
  ▼
phone proxy listener
  │
  │ socket bound to cellular Network
  ▼
Internet
```

The implementation supports:

- HTTP `CONNECT`
- ordinary HTTP proxy requests
- bidirectional socket relay
- per-destination DNS lookup through the cellular `Network`
- explicit `Network.bindSocket()` for cellular egress

This allows AAOS to keep its local-only Wi-Fi relationship with the phone while the phone supplies Internet egress over cellular.

---

## Local-only fallback

Cellular connectivity is optional.

If no usable cellular subscription/network is available, the ARDA local Wi-Fi session is still considered useful and remains active:

```text
Bluetooth bootstrap = working
local-only Wi-Fi     = working
cellular proxy       = unavailable
```

The app logs:

```text
ARDA_RN3B_PHONE_LOCAL_ONLY_SESSION=PASS
```

rather than treating missing cellular service as a failure of the local transport.

---

# Background behavior

The current source is designed so the normal RN-3B connection does not require manually opening the main Activity every time.

After initial permissions/association/enrollment, the expected path is:

```text
AAOS Bluetooth appears
       │
       ├── CompanionDeviceService callback
       │          or
       └── Bluetooth ACL_CONNECTED broadcast
                  │
                  ▼
         ArdaBootstrapService
                  │
                  ▼
          RFCOMM bootstrap
                  │
                  ▼
        saved ARDA Wi-Fi
                  │
                  ▼
     local-only / reverse-proxy session
```

Android may still require explicit user interaction in some situations.

When background foreground-service launch is denied, the application posts a notification that opens the bootstrap Activity with the required context.

When first-time Wi-Fi enrollment is required, a separate high-priority interaction notification is used.

---

# RN-2 dual-network diagnostic

The original RN-2 proof remains in the application as a useful diagnostic screen.

It independently requests:

```text
Wi-Fi Network
Cellular Network
```

and then proves two simultaneous paths:

```text
Wi-Fi socket
  -> AAOS local endpoint

Cellular socket
  -> public HTTP/HTTPS endpoint
```

No global:

```java
ConnectivityManager.bindProcessToNetwork(...)
```

is used.

Instead, each operation uses its own selected `Network`.

This is useful for diagnosing Android routing independently of the full RN-3B Bluetooth/bootstrap flow.

Default AAOS RN-2 port:

```text
5278
```

Leaving the AAOS host blank causes the app to discover the IPv4 Wi-Fi gateway.

Typical RN-2 success markers:

```text
ARDA_RN2_WIFI_NETWORK=PASS
ARDA_RN2_CELLULAR_NETWORK=PASS
ARDA_RN2_WIFI_LOCAL_SOCKET=PASS
ARDA_RN2_CELLULAR_HTTPS=PASS
ARDA_RN2_PHONE_DUAL_NETWORK_GATE=PASS
```

---

# RN-3B diagnostic markers

The project intentionally emits machine-readable markers to Logcat.

Useful examples:

### Bluetooth bootstrap

```text
ARDA_RN3B_PHONE_RFCOMM_CONNECT=PASS
ARDA_RN3B_PHONE_HELLO_TX=PASS
ARDA_RN3B_PHONE_HELLO_ACK_RX=PASS
ARDA_RN3B_PHONE_PING_TX=PASS
ARDA_RN3B_PHONE_PONG_RX=PASS
ARDA_RN3B_PHONE_HELLO_PING_PONG_GATE=PASS
```

### Wi-Fi

```text
ARDA_RN3B_PHONE_WIFI_REQUEST_TX=PASS
ARDA_RN3B_PHONE_WIFI_OFFER_RX=PASS
ARDA_RN3B_PHONE_LOCAL_WIFI=PASS
ARDA_RN3B_PHONE_LOCAL_WIFI_GATE=PASS
```

Possible connection modes:

```text
SAVED_NETWORK_ALREADY_CONNECTED
SAVED_NETWORK_AUTOJOIN
ACTIVE_LOCAL_ONLY_REQUEST
```

### Cellular / proxy

```text
ARDA_RN3B_PHONE_CELLULAR_NETWORK=PASS
ARDA_RN3B_PHONE_EGRESS_BIND_CELLULAR=PASS
ARDA_RN3B_PHONE_PROXY_LISTEN=<ip>:<port>
ARDA_RN3B_PHONE_PROXY_READY_TX=PASS
ARDA_RN3B_PHONE_REVERSE_PROXY_GATE=PASS
```

### Background session

```text
ARDA_RN3B_PHONE_DEVICE_APPEARED=<address>
ARDA_RN3B_PHONE_BT_BROADCAST=ACL_CONNECTED
ARDA_RN3B_PHONE_SESSION_SERVICE=START_REQUESTED
ARDA_RN3B_PHONE_BACKGROUND_SERVICE=ACTIVE
```

Logcat filter:

```bash
adb logcat -s ARDA_RN3B_PHONE
```

or:

```bash
adb logcat | grep 'ARDA_RN'
```

---

# Build

## Toolchain

Current project configuration:

```text
Android Gradle Plugin  8.6.1
Gradle                 8.9
Kotlin                 2.1.21
Compose plugin         2.1.21
compileSdk             36
targetSdk              36
minSdk                 29
Java / JVM toolchain   17
```

The app uses:

```text
Kotlin
Jetpack Compose
Material 3
single Android application module
```

## Android Studio

Open the repository root in Android Studio and build the `app` module.

## Command line

```bash
./gradlew :app:assembleDebug
```

or use the included helper:

```bash
chmod +x build-debug-mac.sh
./build-debug-mac.sh
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

# Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or:

```bash
chmod +x install-debug-mac.sh
./install-debug-mac.sh
```

Package names:

```text
release:
com.github.deadknight.ardacompanion

debug:
com.github.deadknight.ardacompanion.debug
```

---

# First-time setup

A typical first setup is:

1. Install ARDA Companion on the phone.
2. Make sure Bluetooth is enabled.
3. Open the app.
4. Tap **Bluetooth**.
5. Grant the requested Nearby/Bluetooth permissions.
6. Associate the ARDA/Raspberry Pi AAOS device through Android's Companion Device chooser.
7. Accept Bluetooth pairing if Android requests it.
8. Start/connect the ARDA transport.
9. When the first `WIFI_OFFER` arrives, approve the ARDA Wi-Fi network in Android's system UI.
10. After enrollment, the app continues the RFCOMM session and establishes local Wi-Fi.
11. If cellular is available, the phone starts the reverse proxy automatically.

Later sessions are intended to require substantially less interaction because the Bluetooth association, saved device address, companion presence observation and Wi-Fi approval are persisted.

---

# Permissions

The manifest currently declares permissions for:

## Networking

```text
INTERNET
ACCESS_NETWORK_STATE
CHANGE_NETWORK_STATE
ACCESS_WIFI_STATE
CHANGE_WIFI_STATE
NEARBY_WIFI_DEVICES
ACCESS_FINE_LOCATION (Android <= 12L)
```

## Bluetooth

```text
BLUETOOTH
BLUETOOTH_ADMIN
BLUETOOTH_SCAN
BLUETOOTH_CONNECT
```

Legacy Bluetooth permissions remain declared because some vendor Bluetooth socket implementations still enforce them in addition to the Android 12+ runtime permissions.

## Background/session operation

```text
POST_NOTIFICATIONS
FOREGROUND_SERVICE
FOREGROUND_SERVICE_CONNECTED_DEVICE

REQUEST_COMPANION_RUN_IN_BACKGROUND
REQUEST_COMPANION_USE_DATA_IN_BACKGROUND
REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND
REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE
```

The foreground service type is:

```text
connectedDevice
```

---

# Android components

Manifest-level components:

```text
MainActivity
BluetoothBootstrapActivity
ArdaBluetoothConnectionReceiver
ArdaBootstrapService
ArdaCompanionDeviceService
```

Responsibilities:

| Component | Role |
|---|---|
| `MainActivity` | RN-2 diagnostic UI and entry point to Bluetooth setup |
| `BluetoothBootstrapActivity` | association, permissions, manual bootstrap and Wi-Fi enrollment |
| `ArdaBluetoothConnectionReceiver` | ACL connect/disconnect background trigger |
| `ArdaBootstrapService` | active session-scoped foreground transport |
| `ArdaCompanionDeviceService` | Companion Device presence callbacks |

---

# Source map

```text
app/src/main/java/com/github/deadknight/ardacompanion/
├── ArdaBluetoothConnectionReceiver.kt
├── ArdaBluetoothProtocol.kt
├── ArdaBootstrapLaunchCoordinator.kt
├── ArdaBootstrapService.kt
├── ArdaCompanionDeviceService.kt
├── ArdaCompanionPresence.kt
├── ArdaCompanionState.kt
├── ArdaRfcommClient.kt
├── ArdaTheme.kt
├── ArdaWifiEnrollmentCoordinator.kt
├── BluetoothAssociationController.kt
├── BluetoothBootstrapActivity.kt
├── DualNetworkProbeRunner.kt
├── MainActivity.kt
└── ReverseProxyController.kt
```

---

# Design decisions

## No permanent Bluetooth listener service

ARDA uses Android's Bluetooth broadcasts and Companion Device presence APIs to wake/start the session.

The foreground service exists only while a transport session is active.

## No global network binding

The process is not globally moved between Wi-Fi and cellular.

Sockets are explicitly associated with the network they need.

## RFCOMM remains the bootstrap/control channel

Wi-Fi transport availability does not immediately make the Bluetooth session disposable.

RFCOMM carries bootstrap state and remains open for keepalive/control during the active session.

## Wi-Fi and Internet are separate gates

A local-only ARDA Wi-Fi connection can succeed even if cellular is unavailable.

This prevents Internet availability from incorrectly determining whether the local AAOS transport itself is healthy.

---

# Security notes

This repository is a development/reference companion for ARDA and should not be treated as a hardened public network proxy.

Current characteristics include:

- RFCOMM peer selection based on a saved Companion Device / Bluetooth address
- JSON control payloads over the ARDA RFCOMM framing protocol
- a local HTTP proxy exposed on the phone's ARDA Wi-Fi interface
- cleartext traffic enabled for the local transport path
- no general-purpose VPN/TUN implementation

Before using this architecture outside a controlled ARDA environment, consider:

- explicit cryptographic peer authentication
- session keys / transport integrity
- stronger proxy authorization
- restricting accepted proxy clients
- protocol version negotiation
- credential rotation
- threat modeling around Bluetooth pairing and local Wi-Fi access

---

# Current scope

Implemented in the current source:

```text
RN-2 dual-network proof
Bluetooth Companion Device association
RFCOMM HELLO/PING bootstrap
background presence/ACL session triggers
session-scoped foreground service
AAOS LocalOnlyHotspot offer handling
first-time saved Wi-Fi enrollment
saved-network reconnect / auto-join observation
explicit local-only Wi-Fi request fallback
optional cellular Network acquisition
cellular-bound HTTP / CONNECT reverse proxy
RFCOMM keepalive
notification fallback for required user interaction
machine-readable runtime marker logging
```

Not implemented as a general transport product:

```text
TUN/VPN
generic TCP/UDP multiplexing protocol
DNS relay service
arbitrary multi-peer proxy authorization
end-to-end encrypted ARDA control protocol
```

---

# Project status note

The current source is newer than the original RN-2 README that previously shipped with this repository.

The app/build metadata still contains historical naming such as:

```text
ARDA Reverse Companion
0.2.0-rn2-compose
```

while the source itself includes the later RN-3B background/bootstrap implementation documented above.

Those names are retained here as implementation history; this README describes the behavior of the current source tree.

---

# License

No license file was present in the inspected repository snapshot.

Add an explicit license before treating the repository as an externally reusable open-source project.
