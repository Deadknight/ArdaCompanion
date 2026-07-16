# ARDA Companion RN-3B changed files

Apply over the existing Compose companion project.

Replaced:

```text
app/build.gradle
app/src/main/AndroidManifest.xml
app/src/main/java/com/github/deadknight/ardacompanion/MainActivity.kt
```

New:

```text
app/src/main/java/com/github/deadknight/ardacompanion/ArdaBluetoothProtocol.kt
app/src/main/java/com/github/deadknight/ardacompanion/ArdaCompanionState.kt
app/src/main/java/com/github/deadknight/ardacompanion/ArdaRfcommClient.kt
app/src/main/java/com/github/deadknight/ardacompanion/ArdaBootstrapService.kt
app/src/main/java/com/github/deadknight/ardacompanion/ArdaCompanionDeviceService.kt
app/src/main/java/com/github/deadknight/ardacompanion/BluetoothAssociationController.kt
app/src/main/java/com/github/deadknight/ardacompanion/BluetoothBootstrapActivity.kt
```

Existing RN-2 files remain unchanged.

UUID:

```text
e43cd6de-4bb2-49fe-8d43-ecace91038cc
```

Runtime order:

```text
1. AAOS app: Grant Nearby -> Discoverable -> Start server.
2. Phone app: Bluetooth button -> Grant Nearby -> Associate Pi.
3. Accept pairing if asked.
4. Tap HELLO / PING.
```

Expected phone marker:

```text
ARDA_RN3B_PHONE_HELLO_PING_PONG_GATE=PASS
```
