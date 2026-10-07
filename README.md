# Pocket Relay

**Pass it forward.** An experimental, open-source Android messenger that passes encrypted envelopes between participating phones over Bluetooth, without cellular service, Wi-Fi, internet, or external radios.

Version 0.1.0 is a hands-on three-phone prototype. The intended test is **Phone A → Phone B → Phone C**, then a signed delivery receipt traveling back **C → B → A**. Keep the app open on all three phones during testing.

## Install and test

The locally built APK is `dist/PocketRelay-0.1.0.apk`. If published as a release, download the APK from the repository's Releases page. Android 8.0 or later and Classic Bluetooth are required. The APK uses a local test signing certificate.

Follow [the three-phone test guide](docs/THREE-PHONE-TEST.md). Each phone has Messages, Links, and Contacts tabs. Verify contact fingerprints, explicitly select Bluetooth neighbors, and enable forwarding on the middle phone.

## Implemented

- Secure Bluetooth Classic RFCOMM links to explicitly selected paired phones.
- App contacts discovered through connected phones, plus contact-card import/export.
- Manual verification of full public-key fingerprints before sending to a contact.
- Signed envelopes with encrypted message contents; third-party relays have no decryption key.
- Signed, encrypted recipient receipts. "Delivered" means the recipient app decrypted and acknowledged the message, not that a person read it.
- Offline queues saved in private app storage, restored after restart, and replayed when a link reconnects.
- Deduplication, a bounded hop budget, 24-hour expiry, and bounded queues/history.
- A local encryption self-check using the phone's actual Android Keystore identity.
- Original code under the MIT license. No Meshtastic source code is copied; this protocol is not compatible with Meshtastic or LoRa radios.

## Current limits

This prototype uses selected links and app contacts. It does not yet read the Android phone book, automatically connect to strangers, use QR contact exchange, support iPhone, or provide a background service. The screen stays awake while the app is open; relaying can stop if Android kills the process. Test multi-link support on real devices before assuming Phone B can hold both neighbors at once.

Delivery requires a connected path or a later encounter. There is no guarantee of arrival or maximum delivery time. Phones should have reasonably synchronized clocks (within five minutes). A relay that was disabled does not retain incoming third-party envelopes; enable it and retry from the sender. The queue is limited to 128 envelopes per phone; history keeps 200 messages and contact discovery keeps 100 identities. Messages are limited to 4,096 UTF-8 bytes, with up to eight intermediate relay forwards.

## Cryptography and privacy

Each installation has an RSA-3072 identity generated in Android Keystore; the private key is not exported by the app. Every message has a random AES-256 key and a fresh 12-byte nonce. Contents use AES-GCM; the AES key is wrapped to the recipient using RSA-OAEP (SHA-256 digest, MGF1-SHA1 for Android Keystore compatibility). A SHA256withRSA signature binds the sender, recipient, message ID, timestamps, packet type, nonce, wrapped key, and ciphertext. Receipts are signed by the recipient and encrypted to the original sender.

Contact names are labels, not proof of identity. Discovered cards are unverified until you compare complete fingerprints against the person's own phone. Received messages from unverified identities are labeled accordingly. Relays can see routing identities, public keys, timestamps, sizes, and traffic patterns. The mutable hop budget is a cooperation mechanism, not protection from a malicious relay. Relays can drop, delay, or replay envelopes, and can cause denial of service. Keys do not have forward secrecy; compromise of an identity may expose captured older messages. There is no independent security audit.

Private storage contains plaintext on the sender and recipient; intermediate queues contain encrypted envelopes. Android backup is disabled. Clearing data or uninstalling destroys this installation's contact state and may cause a new identity on reinstall. Verify fingerprints again after any identity change. Disabling forwarding deletes stored third-party envelopes.

## Build

Windows prerequisites: JDK 17 or newer (verified here with JDK 21), Python 3, and Android SDK platform 35 / build-tools 35.0.0. No third-party Android library is required.

```powershell
.\build.ps1 -Sdk 'C:\path\to\Android\Sdk'
```

The script runs the protocol checks, compiles the Android sources, creates DEX, signs the test APK, and verifies its signature. To run the protocol checks alone:

```powershell
.\build.ps1 -TestsOnly
```

Some restricted Windows environments crash the native resource tool at ZIP finalization. If this occurs, the script recovers only complete compiled entries whose CRCs and lengths verify, requires exactly this app's expected resource set, rebuilds the ZIP, and verifies alignment. Truncated or unexpected output fails the build. Python uses only its standard library.

For Android Studio, open this folder. Gradle configuration uses AGP 8.9.1, Gradle 8.11.1, Java 8 source compatibility, compile/target SDK 35, and minimum SDK 26. Generate a Gradle wrapper with `gradle wrapper --gradle-version 8.11.1`, then run `gradlew assembleDebug`. The command-line PowerShell build does not require Gradle.

## Project layout

| Path | Purpose |
|---|---|
| `app/` | Android interface, keystore integration, persistence, and Bluetooth transport |
| `core/` | Transport-independent envelope, cryptography, and forwarding logic |
| `core/src/test/` | Executable three-node simulations using the actual shipped protocol |
| `docs/THREE-PHONE-TEST.md` | Installation and physical test procedure |
| `docs/PRD.md` | First prototype requirements and acceptance criteria |
| `docs/VERIFICATION.md` | Actual verification results and remaining device checks |
| `tools/apk_archive.py` | Strict archive recovery and APK alignment |

## Development references

- [Android Bluetooth connections](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

Contributions should preserve the offline-only default and include protocol checks for changes to routing or cryptography. Please report device model, Android version, steps, and the relevant Field Log; do not include private message contents or private keys.
