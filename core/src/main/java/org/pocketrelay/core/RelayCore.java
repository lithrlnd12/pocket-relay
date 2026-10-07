package org.pocketrelay.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Small bounded store-and-forward protocol, independent of Android and transport. */
public final class RelayCore {
    public static final int MAX_FRAME = 32768, MAX_QUEUE = 128, MAX_HISTORY = 200;
    public static final long LIFETIME = 24L * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);

    public interface Host {
        void transmit(String peer, byte[] frame);
        void changed();
        void log(String event);
    }
    public interface Clock { long now(); }

    public static final class Identity {
        public final String name, id;
        public final PublicKey key;
        public Identity(String name, PublicKey key) throws GeneralSecurityException {
            if (name == null || name.isEmpty() || name.length() > 40) throw new IllegalArgumentException("Name must be 1–40 characters");
            if (!(key instanceof java.security.interfaces.RSAPublicKey)
                    || ((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() < 2048
                    || ((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() > 4096)
                throw new GeneralSecurityException("Unsupported identity key");
            this.name = name; this.key = key;
            this.id = hex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        }
        public String shortId() { return id.substring(0, 12); }
        public String fingerprint() { return id.replaceAll("(.{4})(?!$)", "$1 "); }
        void write(DataOutputStream out) throws IOException { out.writeUTF(name); blob(out, key.getEncoded()); }
        static Identity read(DataInputStream in) throws IOException, GeneralSecurityException {
            String name = in.readUTF(); byte[] bytes = blob(in, 1024);
            return new Identity(name, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes)));
        }
        public byte[] card() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0x50524331); write(out); return bytes.toByteArray();
        }
        public static Identity fromCard(byte[] bytes) throws Exception {
            if (bytes.length > 2048) throw new IOException("Contact card too large");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0x50524331) throw new IOException("Not a Pocket Relay card");
            Identity identity = read(in); end(in); return identity;
        }
    }
    public static final class Contact {
        public final Identity identity;
        public boolean verified;
        Contact(Identity identity, boolean verified) { this.identity = identity; this.verified = verified; }
    }
    public static final class Message {
        public final String id, otherId, text;
        public final boolean outgoing;
        public final long time;
        public String status;
        Message(String id, String otherId, String text, boolean outgoing, long time, String status) {
            this.id = id; this.otherId = otherId; this.text = text; this.outgoing = outgoing; this.time = time; this.status = status;
        }
    }
    private static final class Packet {
        final String id, recipient;
        final Identity sender;
        final long created, expires;
        final int type;
        final byte[] wrapped, nonce, ciphertext, signature;
        Packet(String id, Identity sender, String recipient, long created, long expires, int type,
                byte[] wrapped, byte[] nonce, byte[] ciphertext, byte[] signature) {
            this.id = id; this.sender = sender; this.recipient = recipient; this.created = created;
            this.expires = expires; this.type = type; this.wrapped = wrapped; this.nonce = nonce;
            this.ciphertext = ciphertext; this.signature = signature;
        }
        byte[] body() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeUTF(id); sender.write(out); out.writeUTF(recipient);
            out.writeLong(created); out.writeLong(expires); out.writeByte(type);
            blob(out, wrapped); blob(out, nonce); blob(out, ciphertext); return bytes.toByteArray();
        }
        void validate(long now) throws Exception {
            UUID.fromString(id);
            if (!recipient.matches("[0-9a-f]{64}") || (type != 1 && type != 2)
                    || expires <= now || expires < created || expires - created > LIFETIME
                    || created > now + 5 * 60 * 1000 || nonce.length != 12 || ciphertext.length < 16)
                throw new IOException("Invalid or expired packet");
            Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(sender.key);
            verifier.update(body()); if (!verifier.verify(signature)) throw new GeneralSecurityException("Invalid sender signature");
        }
        static Packet read(byte[] body, byte[] signature) throws Exception {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != 1) throw new IOException("Unsupported packet version");
            String id = in.readUTF(); Identity sender = Identity.read(in); String recipient = in.readUTF();
            long created = in.readLong(), expires = in.readLong(); int type = in.readUnsignedByte();
            Packet result = new Packet(id, sender, recipient, created, expires, type,
                    blob(in, 512), blob(in, 12), blob(in, 8192), signature); end(in); return result;
        }
    }
    private static final class Envelope {
        final Packet packet;
        final int hops;
        Envelope(Packet packet, int hops) { this.packet = packet; this.hops = hops; }
        byte[] encode() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(2); out.writeByte(hops); blob(out, packet.body()); blob(out, packet.signature);
            return bytes.toByteArray();
        }
    }

    private Identity self;
    private final PrivateKey privateKey;
    private final Host host;
    private final Clock clock;
    private final LinkedHashMap<String, Contact> contacts = new LinkedHashMap<>();
    private final LinkedHashMap<String, Envelope> queue = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    private final List<Message> messages = new ArrayList<>();
    private final LinkedHashMap<String, Set<String>> peers = new LinkedHashMap<>();
    private boolean relayEnabled = false;

    public RelayCore(Identity self, PrivateKey privateKey, Host host, Clock clock) {
        this.self = self; this.privateKey = privateKey; this.host = host; this.clock = clock;
    }
    public synchronized Identity self() { return self; }
    /** Exercises this phone's real keystore key without creating a chat or transmitting data. */
    public synchronized void selfTest() throws Exception {
        Packet packet = encrypt(self, "Pocket Relay local cryptography check", 1);
        packet.validate(clock.now());
        if (!decrypt(packet).equals("Pocket Relay local cryptography check")) throw new GeneralSecurityException("Cryptography self-test failed");
    }
    public synchronized void rename(String name) throws Exception {
        self = new Identity(name, self.key); host.changed();
        for (String peer : peers.keySet()) host.transmit(peer, hello(self));
    }
    public synchronized List<Contact> contacts() { return new ArrayList<>(contacts.values()); }
    public synchronized List<Message> messages() { return new ArrayList<>(messages); }
    public synchronized int queued() { prune(); return queue.size(); }
    public synchronized int peers() { return peers.size(); }
    public synchronized boolean relaying() { return relayEnabled; }
    public synchronized void setRelaying(boolean enabled) {
        relayEnabled = enabled; prune();
        if (!enabled) queue.values().removeIf(e -> !e.packet.sender.id.equals(self.id));
        else for (String peer : peers.keySet()) flush(peer);
        host.changed();
    }
    public synchronized void verify(String id) {
        Contact contact = contacts.get(id); if (contact == null) return;
        contact.verified = true; host.changed();
    }
    public synchronized void importContact(Identity identity) throws Exception { learn(identity); host.changed(); }
    private void learn(Identity identity) throws IOException {
        if (identity.id.equals(self.id)) return;
        if (!contacts.containsKey(identity.id)) {
            if (contacts.size() >= 100) throw new IOException("Contact limit reached");
            contacts.put(identity.id, new Contact(identity, false));
        }
    }
    public synchronized void connected(String peer) throws Exception {
        if (peers.size() >= 8) throw new IOException("Link limit reached");
        peers.put(peer, new HashSet<>()); host.transmit(peer, hello(self));
        for (Contact contact : contacts.values()) host.transmit(peer, hello(contact.identity));
        flush(peer); host.log("Bluetooth link opened"); host.changed();
    }
    public synchronized void disconnected(String peer) { peers.remove(peer); host.log("Bluetooth link closed"); host.changed(); }
    private static byte[] hello(Identity identity) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(1); identity.write(out); return bytes.toByteArray();
    }
    public synchronized void receive(String peer, byte[] frame) throws Exception {
        if (frame.length == 0 || frame.length > MAX_FRAME || !peers.containsKey(peer)) throw new IOException("Invalid frame or closed peer");
        prune(); DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame)); int kind = in.readUnsignedByte();
        if (kind == 1) {
            Identity identity = Identity.read(in); end(in);
            boolean isNew = !identity.id.equals(self.id) && !contacts.containsKey(identity.id);
            learn(identity);
            if (isNew) {
                for (String other : peers.keySet()) if (!other.equals(peer)) host.transmit(other, hello(identity));
                host.log("Found contact " + identity.name + " · verify fingerprint before sending"); host.changed();
            }
            return;
        }
        if (kind != 2) throw new IOException("Unknown frame");
        int hops = in.readUnsignedByte(); if (hops > 8) throw new IOException("Invalid hop budget");
        Packet packet = Packet.read(blob(in, 16384), blob(in, 512)); end(in); packet.validate(clock.now());
        peers.get(peer).add(packet.id);
        if (seen.containsKey(packet.id)) return;
        if (packet.recipient.equals(self.id)) {
            String text = decrypt(packet);
            if (packet.type == 1) {
                learn(packet.sender);
                markSeen(packet);
                addMessage(new Message(packet.id, packet.sender.id, text, false, clock.now(), "Received"));
                host.log("Decrypted message addressed to this phone");
                try {
                    Packet ack = encrypt(packet.sender, packet.id, 2);
                    if (queue.size() < MAX_QUEUE) { queue.put(ack.id, new Envelope(ack, 8)); markSeen(ack); broadcast(); }
                    else host.log("Receipt queue full; receipt could not be sent");
                } catch (Exception error) { host.log("Receipt failed: " + error.getClass().getSimpleName()); }
            } else {
                Message original = null;
                for (Message message : messages) if (message.outgoing && message.id.equals(text)
                        && message.otherId.equals(packet.sender.id)) original = message;
                if (original != null) {
                    original.status = "Delivered · recipient receipt"; queue.remove(text);
                    markSeen(packet); host.log("Recipient delivery receipt verified");
                }
            }
            host.changed(); return;
        }
        if (!relayEnabled || hops == 0) return;
        if (queue.size() >= MAX_QUEUE) { host.log("Relay queue full; packet not accepted"); return; }
        markSeen(packet); queue.put(packet.id, new Envelope(packet, hops - 1));
        host.log("Relaying encrypted " + (packet.type == 1 ? "message" : "receipt") + " · "
                + packet.sender.shortId() + " → " + packet.recipient.substring(0, 12));
        broadcast(); host.changed();
    }
    public synchronized String send(String recipient, String text) throws Exception {
        prune(); Contact contact = contacts.get(recipient);
        if (contact == null || !contact.verified) throw new GeneralSecurityException("Verify the recipient's fingerprint first");
        if (text == null || text.trim().isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("Message must contain 1–4096 UTF-8 bytes");
        if (queue.size() >= MAX_QUEUE) throw new IOException("Queue full; wait for messages to expire");
        Packet packet = encrypt(contact.identity, text, 1); queue.put(packet.id, new Envelope(packet, 8)); markSeen(packet);
        addMessage(new Message(packet.id, recipient, text, true, clock.now(), "Queued · awaiting recipient receipt"));
        broadcast(); host.changed(); return packet.id;
    }
    private Packet encrypt(Identity recipient, String text, int type) throws Exception {
        byte[] aes = new byte[32], nonce = new byte[12]; RANDOM.nextBytes(aes); RANDOM.nextBytes(nonce);
        String id = UUID.randomUUID().toString(); long now = clock.now();
        Cipher wrapper = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        wrapper.init(Cipher.ENCRYPT_MODE, recipient.key, OAEP); byte[] wrapped = wrapper.doFinal(aes);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(id, self.id, recipient.id, type)); byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        Arrays.fill(aes, (byte) 0);
        Packet unsigned = new Packet(id, self, recipient.id, now, now + LIFETIME, type, wrapped, nonce, encrypted, new byte[0]);
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(privateKey); signer.update(unsigned.body());
        return new Packet(id, self, recipient.id, now, now + LIFETIME, type, wrapped, nonce, encrypted, signer.sign());
    }
    private String decrypt(Packet packet) throws Exception {
        Cipher wrapper = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding"); wrapper.init(Cipher.DECRYPT_MODE, privateKey, OAEP);
        byte[] aes = wrapper.doFinal(packet.wrapped); if (aes.length != 32) throw new GeneralSecurityException("Invalid AES key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"), new GCMParameterSpec(128, packet.nonce));
        cipher.updateAAD(aad(packet.id, packet.sender.id, packet.recipient, packet.type));
        try { return new String(cipher.doFinal(packet.ciphertext), StandardCharsets.UTF_8); }
        finally { Arrays.fill(aes, (byte) 0); }
    }
    private static byte[] aad(String id, String sender, String recipient, int type) {
        return (id + "|" + sender + "|" + recipient + "|" + type).getBytes(StandardCharsets.UTF_8);
    }
    private void markSeen(Packet packet) {
        seen.put(packet.id, packet.expires); while (seen.size() > 2048) seen.remove(seen.keySet().iterator().next());
    }
    private void addMessage(Message message) {
        messages.add(message); while (messages.size() > MAX_HISTORY) messages.remove(0);
    }
    private void prune() {
        long now = clock.now(); queue.values().removeIf(e -> e.packet.expires <= now); seen.values().removeIf(expiry -> expiry <= now);
        for (Message message : messages) if (message.outgoing && !message.status.startsWith("Delivered")
                && message.time + LIFETIME <= now) message.status = "Expired · no recipient receipt";
    }
    public synchronized void retry() { prune(); for (Set<String> ids : peers.values()) ids.clear(); broadcast(); host.changed(); }
    private void broadcast() { for (String peer : peers.keySet()) flush(peer); }
    private void flush(String peer) {
        for (Envelope envelope : queue.values()) {
            if (!relayEnabled && !envelope.packet.sender.id.equals(self.id)) continue;
            if (peers.get(peer).add(envelope.packet.id)) {
                try { host.transmit(peer, envelope.encode()); }
                catch (IOException error) { host.log("Encoding failed"); }
            }
        }
    }
    public synchronized byte[] snapshot() throws IOException {
        prune(); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x50525331); out.writeUTF(self.id); out.writeBoolean(relayEnabled);
        out.writeInt(contacts.size()); for (Contact contact : contacts.values()) { contact.identity.write(out); out.writeBoolean(contact.verified); }
        out.writeInt(queue.size()); for (Envelope envelope : queue.values()) blob(out, envelope.encode());
        out.writeInt(seen.size()); for (Map.Entry<String, Long> entry : seen.entrySet()) { out.writeUTF(entry.getKey()); out.writeLong(entry.getValue()); }
        out.writeInt(messages.size()); for (Message message : messages) {
            out.writeUTF(message.id); out.writeUTF(message.otherId); out.writeUTF(message.text); out.writeBoolean(message.outgoing);
            out.writeLong(message.time); out.writeUTF(message.status);
        }
        return bytes.toByteArray();
    }
    public synchronized void restore(byte[] bytes) throws Exception {
        if (bytes.length > 4 * 1024 * 1024) throw new IOException("State too large");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0x50525331 || !in.readUTF().equals(self.id)) throw new IOException("Saved identity mismatch");
        boolean enabled = in.readBoolean(); LinkedHashMap<String, Contact> newContacts = new LinkedHashMap<>();
        for (int i = count(in, 100); i > 0; i--) { Identity identity = Identity.read(in); newContacts.put(identity.id, new Contact(identity, in.readBoolean())); }
        LinkedHashMap<String, Envelope> newQueue = new LinkedHashMap<>();
        for (int i = count(in, MAX_QUEUE); i > 0; i--) {
            DataInputStream frame = new DataInputStream(new ByteArrayInputStream(blob(in, MAX_FRAME)));
            if (frame.readUnsignedByte() != 2) throw new IOException("Invalid saved envelope");
            int hops = frame.readUnsignedByte(); if (hops > 8) throw new IOException("Invalid saved hops");
            Packet packet = Packet.read(blob(frame, 16384), blob(frame, 512)); end(frame);
            if (packet.expires > clock.now()) { packet.validate(clock.now()); newQueue.put(packet.id, new Envelope(packet, hops)); }
        }
        LinkedHashMap<String, Long> newSeen = new LinkedHashMap<>();
        for (int i = count(in, 2048); i > 0; i--) newSeen.put(in.readUTF(), in.readLong());
        List<Message> newMessages = new ArrayList<>();
        for (int i = count(in, MAX_HISTORY); i > 0; i--)
            newMessages.add(new Message(in.readUTF(), in.readUTF(), in.readUTF(), in.readBoolean(), in.readLong(), in.readUTF()));
        end(in); contacts.clear(); contacts.putAll(newContacts); queue.clear(); queue.putAll(newQueue);
        seen.clear(); seen.putAll(newSeen); messages.clear(); messages.addAll(newMessages); relayEnabled = enabled; prune();
    }
    private static int count(DataInputStream in, int max) throws IOException { int n = in.readInt(); if (n < 0 || n > max) throw new IOException("Invalid count"); return n; }
    private static void blob(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] blob(DataInputStream in, int max) throws IOException {
        int size = in.readInt(); if (size < 0 || size > max || size > in.available()) throw new IOException("Invalid field length");
        byte[] bytes = new byte[size]; in.readFully(bytes); return bytes;
    }
    private static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("Unexpected trailing bytes"); }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
}
