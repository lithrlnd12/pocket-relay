# Verification — October 7, 2026

## Completed locally

- JDK 21 compiled the transport-independent protocol and its executable tests.
- `build.ps1` ran **29 passing protocol checks**: indirect contact discovery, verified-contact gate, A–B–C encrypted delivery, C–B–A authenticated receipt, relay without plaintext inbox/wire/log contents, reverse messaging, duplicate suppression, invalid signatures, oversized/malformed packets, offline queue/restart, later encounters, saved receipts, restored history/contacts, identity mismatch, relay opt-in/out, expiry, loops, contact cards, and bounded queues.
- Java compiled all Android application and Bluetooth source against Android SDK 35. D8 generated the shipped DEX with minimum API 26.
- Resource entries were recovered after the installed Windows AAPT tool crashed during archive finalization. All expected compiled entries passed length and CRC verification; the recovery script rejected any missing/unexpected entry.
- The APK was signed with a local test certificate. `apksigner verify --verbose` returned success with APK signature schemes **v2 and v3**.
- `zipalign -c -v 4` returned **Verification succesful**. The manifest, icon, resource table, and DEX were aligned.
- `apkanalyzer manifest permissions` listed only Bluetooth permissions. There is **no INTERNET, Wi-Fi, address-book, or location permission**.

## Pending physical verification

No phones were attached to ADB during this build. The local Android emulator did not boot in this execution environment, so app startup/UI, Android Keystore compatibility, and concurrent Bluetooth links have not been observed on a device here. The in-app encryption check and three-phone guide cover these first checks on the user's phones.

The source/protocol checks are passing and the signed APK is built; successful radio delivery on the user's Pixel and Ultras is not yet claimed. This is a prototype, with background operation, phone-book integration, and independent security review deferred.
