# Pocket Relay: first Android field test

## Problem and audience

The user has a Pixel and two Samsung Ultra phones and wants to test an original open-source phone-to-phone messenger. Without internet access, Phone A should send a private message to Phone C through a consenting Phone B.

## Agreed first milestone

Build an Android APK and editable source, save both on the user's C drive, name the app Pocket Relay, and publish the source to the connected GitHub account when available. The scope follows the user's approval of the three-phone Bluetooth prototype.

## Requirements

1. Establish only user-selected Bluetooth connections A–B and B–C.
2. Discover app contacts through these links, and pin the recipient's key after a full fingerprint comparison.
3. Encrypt message content for the recipient and authenticate immutable envelope fields.
4. Forward envelopes on B only after explicit opt-in. B cannot display another recipient's plaintext.
5. Show "Delivered" only after receiving the intended recipient's authenticated receipt.
6. Store envelopes across an app restart and forward them when a neighbor connects later.
7. Bound queues, message sizes, peer/contact counts, history, lifetime, and relay hops; prevent duplicate deliveries and ordinary relay loops.
8. Offer instructions usable with three physical phones, with Wi-Fi/cellular disabled and Bluetooth enabled.

## Acceptance evidence

- Compile an installable APK and verify its signing and ZIP alignment.
- Automated tests exercise delivery, return receipts, unreadable relay frames, offline storage/restart, duplicates, malformed packets, expiry, opt-out, and queue bounds using the actual protocol implementation.
- On the user's phones, A receives C's receipt, C displays the original message, B displays only encrypted forwarding events, and A/C have no direct app link.

Automated protocol evidence does not substitute for physical Bluetooth testing. The physical checks remain pending until the phones are connected and tested.

## Deferred work

Android address-book matching, QR onboarding, opportunistic auto-connection, background services, battery optimization, an iPhone transport, large-network routing, audited modern ratcheting cryptography, and LoRa/Meshtastic compatibility.
