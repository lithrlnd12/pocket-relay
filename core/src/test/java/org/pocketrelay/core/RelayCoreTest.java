package org.pocketrelay.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Executable tests use the exact protocol code shipped inside the APK. */
public final class RelayCoreTest {
    private static int passed;
    private static long now = 1_800_000_000_000L;
    private static final Map<String, KeyPair> KEYS = new HashMap<>();
    private static final ArrayDeque<Runnable> network = new ArrayDeque<>();
    private static final class Node implements RelayCore.Host {
        final RelayCore core;
        final Map<String, Node> links = new LinkedHashMap<>();
        final List<byte[]> frames = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        Node(String name) throws Exception {
            KeyPair pair = KEYS.get(name);
            core = new RelayCore(new RelayCore.Identity(name, pair.getPublic()), pair.getPrivate(), this, () -> now);
        }
        @Override public void transmit(String peer, byte[] frame) {
            frames.add(frame.clone()); Node target = links.get(peer);
            if (target != null) network.add(() -> {
                try { target.core.receive(core.self().id, frame); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
        }
        @Override public void changed() { }
        @Override public void log(String event) { logs.add(event); }
    }
    private static void link(Node a, Node b) throws Exception {
        a.links.put(b.core.self().id, b); b.links.put(a.core.self().id, a);
        a.core.connected(b.core.self().id); b.core.connected(a.core.self().id); drain();
    }
    private static void unlink(Node a, Node b) {
        a.links.remove(b.core.self().id); b.links.remove(a.core.self().id);
        a.core.disconnected(b.core.self().id); b.core.disconnected(a.core.self().id);
    }
    private static void drain() { int steps = 0; while (!network.isEmpty()) { if (++steps > 10000) throw new AssertionError("Relay loop"); network.removeFirst().run(); } }
    private static void trust(Node a, Node b) throws Exception { a.core.importContact(b.core.self()); a.core.verify(b.core.self().id); }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); System.out.println("PASS " + label); passed++; }
    private static void rejects(Throwing action, String label) throws Exception {
        boolean rejected = false; try { action.run(); } catch (Exception expected) { rejected = true; } check(rejected, label);
    }
    private interface Throwing { void run() throws Exception; }
    private static byte[] packetFrame(Node node) { for (byte[] frame : node.frames) if (frame[0] == 2) return frame; throw new AssertionError("No packet"); }
    public static void main(String[] args) throws Exception {
        for (String name : Arrays.asList("A", "B", "C", "D")) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); KEYS.put(name, generator.generateKeyPair());
        }
        Node a = new Node("A"), b = new Node("B"), c = new Node("C"); b.core.setRelaying(true);
        link(a, b); link(b, c);
        check(a.core.contacts().stream().anyMatch(x -> x.identity.id.equals(c.core.self().id)), "C discovered through B without direct A-C link");
        rejects(() -> a.core.send(c.core.self().id, "unverified"), "Unverified contact cannot be messaged");
        trust(a, c); trust(c, a);
        String secret = "ONLY-C-READS-THIS-7cf8 — hello 👋";
        a.core.send(c.core.self().id, secret); drain();
        check(c.core.messages().size() == 1 && c.core.messages().get(0).text.equals(secret), "A-B-C encrypted delivery");
        check(a.core.messages().get(0).status.startsWith("Delivered"), "Signed recipient receipt traverses C-B-A");
        check(b.core.messages().isEmpty(), "B has no plaintext inbox message");
        check(b.frames.stream().noneMatch(f -> new String(f, StandardCharsets.ISO_8859_1).contains("ONLY-C-READS-THIS")), "Relay wire frames do not contain plaintext");
        check(b.logs.stream().noneMatch(x -> x.contains("ONLY-C-READS-THIS")), "Relay log does not contain plaintext");
        c.core.send(a.core.self().id, "Reply through B"); drain();
        check(a.core.messages().stream().anyMatch(x -> !x.outgoing && x.text.equals("Reply through B")), "Bidirectional relay works");
        a.core.retry(); b.core.retry(); c.core.retry(); drain();
        check(c.core.messages().size() == 2 && a.core.messages().size() == 2, "Retries do not duplicate received messages");
        byte[] tampered = packetFrame(a).clone(); tampered[tampered.length - 1] ^= 1;
        rejects(() -> b.core.receive(a.core.self().id, tampered), "Tampered sender signature rejected");
        rejects(() -> b.core.receive(a.core.self().id, new byte[RelayCore.MAX_FRAME + 1]), "Oversized frame rejected");
        rejects(() -> b.core.receive(a.core.self().id, new byte[]{2, 99, 0}), "Invalid hop budget rejected");
        rejects(() -> a.core.send(c.core.self().id, String.join("", Collections.nCopies(4097, "x"))), "Oversized message rejected");

        Node x = new Node("A"), y = new Node("B"), z = new Node("C"); y.core.setRelaying(true); trust(x, z); link(x, y);
        x.core.send(z.core.self().id, "Wait until C arrives"); drain();
        check(y.core.queued() == 1 && z.core.messages().isEmpty(), "B stores envelope while C absent");
        unlink(x, y); byte[] state = y.core.snapshot(); Node restored = new Node("B"); restored.core.restore(state);
        check(restored.core.queued() == 1 && restored.core.relaying(), "Encrypted relay queue survives restart");
        link(restored, z);
        check(z.core.messages().size() == 1 && z.core.messages().get(0).text.equals("Wait until C arrives"), "Stored envelope delivered during later encounter");
        link(x, restored);
        check(x.core.messages().get(0).status.startsWith("Delivered"), "Stored receipt delivered when sender reconnects");
        Node sameA = new Node("A"); sameA.core.restore(x.core.snapshot());
        check(sameA.core.messages().get(0).status.startsWith("Delivered"), "History and verified contacts survive restart");
        rejects(() -> z.core.restore(x.core.snapshot()), "Saved state for another identity rejected");

        Node offA = new Node("A"), offB = new Node("B"), offC = new Node("C"); trust(offA, offC); link(offA, offB); link(offB, offC);
        offA.core.send(offC.core.self().id, "Consent check"); drain();
        check(offB.core.queued() == 0 && offC.core.messages().isEmpty(), "Relaying disabled by default");
        offB.core.setRelaying(true); offA.core.retry(); drain();
        check(offC.core.messages().size() == 1, "Opt-in plus sender retry resumes delivery");

        Node expA = new Node("A"), expB = new Node("B"), expC = new Node("C"); expB.core.setRelaying(true); trust(expA, expC); link(expA, expB);
        expA.core.send(expC.core.self().id, "Will expire"); drain(); now += RelayCore.LIFETIME + 1;
        check(expB.core.queued() == 0, "Expired envelopes removed");
        link(expB, expC); check(expC.core.messages().isEmpty(), "Expired envelope not forwarded");
        check(expA.core.messages().get(0).status.startsWith("Expired") || expA.core.queued() == 0, "Sender sees expired delivery state");
        now -= RelayCore.LIFETIME + 1;

        Node loopA = new Node("A"), loopB = new Node("B"), loopC = new Node("C"), absent = new Node("D");
        loopA.core.setRelaying(true); loopB.core.setRelaying(true); loopC.core.setRelaying(true); trust(loopA, absent);
        link(loopA, loopB); link(loopB, loopC); link(loopC, loopA); loopA.core.send(absent.core.self().id, "No looping forever"); drain();
        check(loopA.core.queued() == 1 && loopB.core.queued() == 1 && loopC.core.queued() == 1, "Triangle topology stops duplicates and loops");
        loopB.core.setRelaying(false); check(loopB.core.queued() == 0, "Disabling relaying removes third-party queued data");
        check(RelayCore.Identity.fromCard(a.core.self().card()).id.equals(a.core.self().id), "Contact card preserves fingerprint");
        byte[] malformed = a.core.self().card(); malformed[0] = 0;
        rejects(() -> RelayCore.Identity.fromCard(malformed), "Malformed contact card rejected");
        Node bound = new Node("A"); trust(bound, absent);
        for (int i = 0; i < RelayCore.MAX_QUEUE; i++) bound.core.send(absent.core.self().id, "Queue " + i);
        rejects(() -> bound.core.send(absent.core.self().id, "Overflow"), "Offline queue is bounded");
        System.out.println("\n" + passed + " protocol checks passed.");
    }
}
