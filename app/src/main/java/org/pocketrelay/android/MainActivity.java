package org.pocketrelay.android;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.security.keystore.*;
import android.view.*;
import android.widget.*;
import org.pocketrelay.core.RelayCore;
import java.io.*;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG = 0xff101c20, PANEL = 0xff1c2b30, GREEN = 0xffa6f4c5, WHITE = 0xffedf5ef, MUTED = 0xffa4b7b8;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private RelayCore core;
    private BluetoothTransport transport;
    private LinearLayout root, content, inbox, contactList, linksList;
    private TextView status, logView;
    private EditText composer;
    private Spinner recipient;
    private final List<String> recipients = new ArrayList<>();
    private String recipientVersion = "";
    private int tab;
    private boolean destroyed;
    private static final String KEY_ALIAS = "pocket-relay-identity-v1";
    private final Runnable refresh = () -> { if (!destroyed && core != null) updateLive(); };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = column(); root.setPadding(dp(20), dp(12), dp(20), dp(12)); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(root); root.addView(text("POCKET RELAY", 12, GREEN));
        root.addView(text("A little closer.\nOne phone at a time.", 28, WHITE));
        status = text("Creating your private identity…", 13, MUTED); root.addView(status);
        worker.execute(() -> {
            try { initialize(); main.post(() -> { if (!destroyed) render(); }); }
            catch (Exception error) { main.post(() -> fatal(error)); }
        });
    }
    private void initialize() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_SIGN)
                    .setKeySize(3072).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build()); generator.generateKeyPair();
        }
        String name = getPreferences(MODE_PRIVATE).getString("name", Build.MODEL);
        if (name.length() > 40) name = name.substring(0, 40);
        RelayCore.Identity identity = new RelayCore.Identity(name, store.getCertificate(KEY_ALIAS).getPublicKey());
        core = new RelayCore(identity, (PrivateKey) store.getKey(KEY_ALIAS, null), new RelayCore.Host() {
            @Override public void transmit(String peer, byte[] frame) { if (transport != null) transport.send(peer, frame); }
            @Override public void changed() { scheduleSave(); main.removeCallbacks(refresh); main.postDelayed(refresh, 150); }
            @Override public void log(String event) { addLog(event); }
        }, System::currentTimeMillis);
        File state = new File(getFilesDir(), "relay-state.bin");
        if (state.exists()) try { core.restore(readLimited(new FileInputStream(state), 4 * 1024 * 1024)); }
        catch (Exception error) { addLog("Saved state could not be loaded. Identity preserved; contact checks and queue may need rebuilding."); }
        transport = new BluetoothTransport(this, new BluetoothTransport.Events() {
            @Override public void connected(String peer) { worker.execute(() -> { try { core.connected(peer); } catch (Exception error) { addLog("Link rejected: " + safe(error)); transport.disconnect(peer); } }); }
            @Override public void disconnected(String peer) { worker.execute(() -> core.disconnected(peer)); }
            @Override public void frame(String peer, byte[] bytes) { worker.execute(() -> { try { core.receive(peer, bytes); } catch (Exception error) { addLog("Rejected packet: " + safe(error)); } }); }
            @Override public void log(String event) { addLog(event); }
        });
    }
    private void scheduleSave() {
        if (destroyed) return;
        worker.execute(() -> {
            try {
                byte[] bytes = core.snapshot(); File tmp = new File(getFilesDir(), "relay-state.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
                if (!tmp.renameTo(new File(getFilesDir(), "relay-state.bin"))) addLog("Could not save queue; keep app open");
            } catch (Exception error) { addLog("Could not save queue: " + safe(error)); }
        });
    }
    private void render() {
        root.removeAllViews(); root.addView(text("POCKET RELAY  /  FIELD TEST 01", 11, GREEN));
        root.addView(text("Pass it forward.", 29, WHITE));
        status = text("", 13, MUTED); root.addView(status);
        LinearLayout tabs = new LinearLayout(this);
        String[] names = {"Messages", "Links", "Contacts"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; Button button = button(names[i], () -> { tab = index; render(); });
            button.setTextColor(tab == i ? BG : WHITE); button.setBackground(shape(tab == i ? GREEN : PANEL));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1); p.setMargins(0, dp(8), dp(4), dp(12)); tabs.addView(button, p);
        }
        root.addView(tabs);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); content = column(); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        composer = null; inbox = null; recipient = null; contactList = null; linksList = null; logView = null;
        if (tab == 0) messagesTab(); else if (tab == 1) linksTab(); else contactsTab();
        updateLive();
    }
    private void messagesTab() {
        content.addView(text("SEND A PRIVATE MESSAGE", 11, GREEN));
        content.addView(text("Choose a verified contact. Nearby phones can carry the encrypted envelope.", 14, MUTED));
        recipient = new Spinner(this); content.addView(recipient); recipientVersion = "";
        composer = input("Your message", false); composer.setMinLines(2); composer.setMaxLines(5); content.addView(composer);
        content.addView(button("Send message", () -> {
            int position = recipient.getSelectedItemPosition();
            if (position < 0 || position >= recipients.size() || recipients.get(position).isEmpty()) { toast("Add and verify a contact first"); return; }
            String id = recipients.get(position), body = composer.getText().toString();
            worker.execute(() -> {
                try { core.send(id, body); main.post(() -> { if (composer != null && composer.getText().toString().equals(body)) composer.setText(""); toast("Queued for delivery"); }); }
                catch (Exception error) { main.post(() -> toast(safe(error))); }
            });
        }));
        content.addView(text("CONVERSATIONS", 11, GREEN)); inbox = column(); content.addView(inbox);
    }
    private void linksTab() {
        content.addView(text("BLUETOOTH LINKS", 11, GREEN));
        content.addView(text("For A → B → C: connect A to B, then B to C. Leave A and C unconnected.", 15, WHITE));
        content.addView(button(transport.running() ? "Stop all Bluetooth links" : "Start Bluetooth links", () -> {
            if (transport.running()) { transport.stop(); render(); }
            else startLinks();
        }));
        content.addView(button("Connect a paired phone", this::choosePhone));
        content.addView(button("Pair phones in Android settings", () -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
        content.addView(button("Make this phone discoverable", () -> {
            if (!permissions()) { requestBluetooth(); return; }
            if (!transport.enabled()) { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE); intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120); startActivity(intent);
        }));
        Switch relay = new Switch(this); relay.setText("Help relay encrypted messages"); relay.setTextColor(WHITE);
        relay.setChecked(core.relaying()); relay.setPadding(0, dp(12), 0, dp(12));
        relay.setOnCheckedChangeListener((v, checked) -> worker.execute(() -> core.setRelaying(checked))); content.addView(relay);
        content.addView(text("Enable this on Phone B. Relays can see routing identities and timing, but cannot decrypt another person's message.", 13, MUTED));
        content.addView(button("Retry queued envelopes", () -> worker.execute(core::retry)));
        content.addView(button("Check this phone's encryption", () -> worker.execute(() -> {
            try { core.selfTest(); addLog("Local encryption / decryption / signature check passed"); main.post(() -> toast("Encryption check passed on this phone")); }
            catch (Exception error) { addLog("Encryption check FAILED: " + safe(error)); main.post(() -> toast("Encryption check failed. Do not send until resolved.")); }
        })));
        linksList = column(); content.addView(linksList);
        content.addView(text("FIELD LOG", 11, GREEN)); logView = text("", 12, MUTED); logView.setTextIsSelectable(true); content.addView(logView);
    }
    private void contactsTab() {
        content.addView(text("YOUR IDENTITY", 11, GREEN)); content.addView(text(core.self().name, 22, WHITE));
        TextView fingerprint = text(core.self().fingerprint(), 13, MUTED); fingerprint.setTextIsSelectable(true); content.addView(fingerprint);
        content.addView(text("Compare the complete fingerprint on both phones before trusting a contact.", 13, MUTED));
        content.addView(button("Name this phone", () -> {
            EditText name = input("Phone A / Phone B / Phone C", true); name.setText(core.self().name);
            new AlertDialog.Builder(this).setTitle("Phone name").setView(name).setPositiveButton("Save", (d, w) -> {
                String value = name.getText().toString().trim(); worker.execute(() -> {
                    try { core.rename(value); getPreferences(MODE_PRIVATE).edit().putString("name", value).apply(); main.post(this::render); }
                    catch (Exception error) { main.post(() -> toast(safe(error))); }
                });
            }).setNegativeButton("Cancel", null).show();
        }));
        content.addView(button("Export my contact card", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.setType("application/octet-stream");
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.putExtra(Intent.EXTRA_TITLE, "PocketRelay-" + core.self().shortId() + ".prcontact"); startActivityForResult(intent, 20);
        }));
        content.addView(button("Import contact card", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, 21);
        }));
        content.addView(text("APP CONTACTS", 11, GREEN));
        content.addView(text("Contacts appear as phones connect. A contact discovered through B still needs a fingerprint check against C. This version uses app contacts; phone-book integration comes later.", 13, MUTED));
        contactList = column(); content.addView(contactList);
    }
    private void updateLive() {
        status.setText(core.self().name + "  ·  " + core.peers() + " links  ·  " + core.queued() + " queued envelopes");
        if (recipient != null) {
            List<RelayCore.Contact> contacts = core.contacts(); StringBuilder version = new StringBuilder();
            for (RelayCore.Contact c : contacts) if (c.verified) version.append(c.identity.id).append(c.identity.name);
            if (!version.toString().equals(recipientVersion) || recipient.getAdapter() == null) {
                String selected = recipient.getSelectedItemPosition() >= 0 && recipient.getSelectedItemPosition() < recipients.size()
                        ? recipients.get(recipient.getSelectedItemPosition()) : "";
                recipients.clear(); List<String> labels = new ArrayList<>();
                for (RelayCore.Contact c : contacts) if (c.verified) { recipients.add(c.identity.id); labels.add(c.identity.name + " · " + c.identity.shortId()); }
                if (labels.isEmpty()) { labels.add("Verify a contact in Contacts first"); recipients.add(""); }
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); recipient.setAdapter(adapter);
                int selectedIndex = recipients.indexOf(selected); if (selectedIndex >= 0) recipient.setSelection(selectedIndex);
                recipientVersion = version.toString();
            }
        }
        if (inbox != null) {
            inbox.removeAllViews(); List<RelayCore.Message> messages = core.messages();
            if (messages.isEmpty()) inbox.addView(text("No messages yet.\nStart your links, verify a contact, and send your first message.", 15, MUTED));
            for (int i = messages.size() - 1; i >= 0; i--) {
                RelayCore.Message m = messages.get(i); RelayCore.Contact c = find(m.otherId);
                LinearLayout card = card(); card.addView(text((m.outgoing ? "To " : "From ") + (c == null ? m.otherId.substring(0, 12) : c.identity.name)
                        + (c != null && c.verified ? "  ✓" : "  · unverified identity"), 12, GREEN));
                card.addView(text(m.text, 17, WHITE));
                card.addView(text(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(m.time)) + " · " + m.status, 11, MUTED));
                inbox.addView(card);
            }
        }
        if (contactList != null) {
            contactList.removeAllViews();
            for (RelayCore.Contact c : core.contacts()) {
                LinearLayout card = card(); card.addView(text(c.identity.name, 18, WHITE)); card.addView(text(c.identity.shortId(), 12, MUTED));
                card.addView(button(c.verified ? "Verified · view fingerprint" : "Verify contact", () -> verify(c))); contactList.addView(card);
            }
            if (core.contacts().isEmpty()) contactList.addView(text("Connect phones or import a contact card to find people.", 15, MUTED));
        }
        if (linksList != null) {
            linksList.removeAllViews(); List<String> links = transport.descriptions();
            if (links.isEmpty()) linksList.addView(text("No active links", 14, MUTED));
            for (String description : links) {
                String address = description.substring(description.lastIndexOf(" · ") + 3);
                linksList.addView(button(description + "  /  Disconnect", () -> transport.disconnect(address)));
            }
        }
        if (logView != null) { synchronized (logs) { logView.setText(String.join("\n", logs)); } }
    }
    private RelayCore.Contact find(String id) { for (RelayCore.Contact c : core.contacts()) if (c.identity.id.equals(id)) return c; return null; }
    private void verify(RelayCore.Contact contact) {
        new AlertDialog.Builder(this).setTitle("Verify " + contact.identity.name)
                .setMessage("Ask this person to open Contacts on their phone. Compare every group below with their own identity fingerprint:\n\n"
                        + contact.identity.fingerprint() + "\n\nOnly confirm if they match. A matching display name is not sufficient.")
                .setPositiveButton("Fingerprints match", (d, w) -> worker.execute(() -> core.verify(contact.identity.id)))
                .setNegativeButton("Cancel", null).show();
    }
    private void startLinks() {
        if (!transport.supported()) { toast("This device has no Bluetooth adapter"); return; }
        if (!permissions()) { requestBluetooth(); return; }
        if (!transport.enabled()) { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
        try { transport.start(); render(); } catch (Exception error) { toast(safe(error)); }
    }
    private void choosePhone() {
        if (!permissions()) { requestBluetooth(); return; }
        if (!transport.running()) { toast("Start Bluetooth links first"); return; }
        List<BluetoothDevice> devices = transport.paired();
        if (devices.isEmpty()) { toast("Pair the phones in Android Bluetooth settings first"); return; }
        String[] labels = new String[devices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = devices.get(i).getName() + "\n" + devices.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("Choose a paired phone").setItems(labels, (d, w) -> transport.connect(devices.get(w)))
                .setNegativeButton("Cancel", null).show();
    }
    private boolean permissions() {
        return Build.VERSION.SDK_INT < 31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED);
    }
    private void requestBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE}, 10);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 10) toast(permissions() ? "Permission granted. Tap Start Bluetooth links." : "Nearby devices permission is required for Bluetooth links");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null || data.getData() == null || core == null) return;
        worker.execute(() -> {
            try {
                if (request == 20) {
                    try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { if (out == null) throw new IOException("File could not be opened"); out.write(core.self().card()); }
                    main.post(() -> toast("Contact card exported. Share the file, then compare fingerprints."));
                } else if (request == 21) {
                    core.importContact(RelayCore.Identity.fromCard(readLimited(getContentResolver().openInputStream(data.getData()), 2048)));
                    main.post(() -> { toast("Contact imported. Verify its fingerprint before sending."); render(); });
                }
            } catch (Exception error) { main.post(() -> toast(safe(error))); }
        });
    }
    private void addLog(String event) {
        synchronized (logs) {
            logs.addFirst(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()) + "  " + event);
            while (logs.size() > 60) logs.removeLast();
        }
        main.removeCallbacks(refresh); main.postDelayed(refresh, 150);
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card() {
        LinearLayout view = column(); view.setBackground(shape(PANEL)); view.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(8), 0, dp(6)); view.setLayoutParams(p); return view;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8)); if (color == GREEN) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private Button button(String value, Runnable action) {
        Button view = new Button(this); view.setText(value); view.setAllCaps(false); view.setTextSize(14); view.setTextColor(WHITE);
        view.setBackground(shape(PANEL)); view.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(5), 0, dp(5)); view.setLayoutParams(p);
        view.setOnClickListener(v -> action.run()); return view;
    }
    private EditText input(String hint, boolean single) {
        EditText view = new EditText(this); view.setHint(hint); view.setSingleLine(single); view.setTextColor(WHITE); view.setHintTextColor(MUTED);
        view.setTextSize(16); view.setPadding(dp(12), dp(12), dp(12), dp(12)); view.setBackground(shape(PANEL)); return view;
    }
    private GradientDrawable shape(int color) { GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(12)); return background; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void fatal(Exception error) { status.setText("Setup failed: " + safe(error)); }
    private static String safe(Exception error) { String message = error.getMessage(); return message == null ? error.getClass().getSimpleName() : message; }
    private static byte[] readLimited(InputStream source, int max) throws IOException {
        if (source == null) throw new IOException("File could not be opened");
        try (InputStream in = source; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) { if (bytes.size() + n > max) throw new IOException("File too large"); bytes.write(buffer, 0, n); }
            return bytes.toByteArray();
        }
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null);
        if (transport != null) transport.destroy();
        worker.shutdown(); super.onDestroy();
    }
}
