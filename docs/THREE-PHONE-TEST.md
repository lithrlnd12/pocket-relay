# Your first three-phone test

Use the Pixel as **A**, one Ultra as **B**, and the other Ultra as **C**. The choice is interchangeable. Keep all three apps open, with phones close together, during the first test. Disable Wi-Fi and mobile data; leave Bluetooth enabled. You can also enable airplane mode and then explicitly turn Bluetooth back on.

## 1. Install the APK

Copy `dist/PocketRelay-0.1.0.apk` to each phone using a USB cable. Open it in the phone's Files app. Android may ask you to allow installation from that file manager. Install Pocket Relay. If Android rejects it, keep the exact error for troubleshooting; do not disable device protection to work around an unexplained rejection.

If using USB debugging instead, install to a specific connected phone:

```powershell
adb devices
adb -s YOUR_DEVICE_SERIAL install -r .\dist\PocketRelay-0.1.0.apk
```

Use the same APK on all three phones. A later APK signed with a different local test certificate will not install as an update; preserve the project's local signing key for updates. The app supports Android 8.0 and newer. The exact Android versions of the user's Pixel and Ultras have not yet been supplied.

## 2. Set up the phones

1. Open Contacts → Name this phone. Name the phones `Phone A`, `Phone B`, and `Phone C`.
2. Open Links → Start Bluetooth links. Allow Nearby devices permission if prompted, then tap Start again.
3. On each phone, tap **Check this phone's encryption**. Confirm the success message before proceeding. This tests the real phone's Keystore encryption, decryption, and signature support.
4. Pair **A with B**, and **B with C**, in Android Bluetooth settings. Keep the other phone's Bluetooth settings visible or use Pocket Relay's Make this phone discoverable button while pairing. Return to Pocket Relay after pairing.
5. On B, enable **Help relay encrypted messages**.

There is no location or internet permission in this APK. It lists already-paired phones, so the first version does not need an in-app location-based Bluetooth scan.

## 3. Build only the two intended links

1. Keep Pocket Relay listening on all three phones.
2. On A: Links → Connect a paired phone → choose B.
3. On B: Links → Connect a paired phone → choose C.
4. Wait for A to show **1 link**, B **2 links**, and C **1 link**.
5. Check the active-link buttons: A lists B, B lists A and C, C lists B. **Do not connect A directly to C.**

All three phones may remain on the same table. There is no automatic app connection to A/C, so the deliberately selected link topology proves forwarding without relying on physical distance.

## 4. Verify the endpoints

A's Contacts tab should show C after B shares the public contact directory. C's Contacts tab should similarly show A.

On A, tap Verify contact for C. Compare **every group** of the displayed fingerprint with C's own identity fingerprint at the top of C's Contacts tab. If they match, tap Fingerprints match. Repeat on C to verify A. Neither a phone name nor a Bluetooth pairing name proves the person's cryptographic identity.

If a contact does not appear, stop/start the affected app links and reconnect. As a fallback, C can export its contact card and you can transfer the `.prcontact` file over USB to A, then use Import contact card. Always compare fingerprints afterward.

## 5. Send A → B → C

On A, open Messages, choose verified Phone C, and send:

> Hello C — this message came through B.

Expected evidence:

| Phone | Expected result |
|---|---|
| A | Outgoing message changes to **Delivered · recipient receipt** |
| B | Field Log says **Relaying encrypted message**, then **Relaying encrypted receipt** |
| C | Messages displays the text from A |

B's Messages tab should remain empty if B has not sent/received its own chats. It has no private key to decrypt the A–C message. Forwarding logs show IDs and events, not contents. Delivery acknowledges receipt by the app, not reading by the person.

Now reply from C to A. Expect the same behavior in the reverse direction.

## 6. Prove that B is required

Disable Help relay encrypted messages on B. Send a new message A → C. It should remain queued on A and not appear on C. Enable relaying on B again and tap **Retry queued envelopes** on A. C should now receive it.

## 7. Prove storage across a restart

1. Disconnect B–C using the active-link Disconnect button; keep A–B connected.
2. Send another A → C message. B should show a queued envelope; A should have no delivery receipt yet.
3. Disconnect A–B and close/reopen Pocket Relay on B. Keep a moment between receipt of the queued envelope and closing B so its save task finishes. B should still have its queued envelope.
4. Start Bluetooth links again on B; connect B–C. C should receive the saved message.
5. Reconnect A–B. The saved recipient receipt should reach A.

## Troubleshooting

- **Connection fails:** the other phone must have Pocket Relay open and its Bluetooth links started. Both phones must be paired. Check the Field Log. Do not choose headphones or other peripherals.
- **B cannot show two links:** note which link fails and the precise model/Android version. The transport uses two distinct RFCOMM connections; simultaneous support must be verified on the physical devices.
- **No verified recipient:** open Contacts and compare its fingerprint. Discovered people are not automatically trusted.
- **Message queued:** verify both links, relaying enabled on B, reasonable clock synchronization, and the recipient contact identity. Try Retry queued envelopes on the involved phones.
- **App recreated after changing tabs/settings:** Android can restart an Activity or kill its process. Queues are persisted, but Bluetooth links need restarting. This prototype intentionally keeps the screen awake while open.
- **Encryption check fails:** save the exact Field Log message and phone/Android version. Stop that phone's test until the compatibility problem is fixed.

Report the outcome as: phone models + Android versions; A/B/C link counts; A's message status; C's received text (use a harmless test message); B's forwarding event; any connection or cryptography errors. Screenshots are useful but not required.
