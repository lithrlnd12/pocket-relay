package org.pocketrelay.android;

import android.bluetooth.*;
import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Secure Classic Bluetooth RFCOMM links. Connections are deliberately selected by the user. */
public final class BluetoothTransport {
    public interface Events {
        void connected(String peer);
        void disconnected(String peer);
        void frame(String peer, byte[] data);
        void log(String event);
    }
    private static final UUID SERVICE = UUID.fromString("5ea88783-4ad6-4a20-aa37-64ee28bf40c2");
    private final BluetoothAdapter adapter;
    private final Events events;
    private final ConcurrentHashMap<String, Link> links = new ConcurrentHashMap<>();
    private final Set<BluetoothSocket> pending = ConcurrentHashMap.newKeySet();
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private volatile BluetoothServerSocket server;
    private volatile boolean running;
    private volatile int generation;

    public BluetoothTransport(Context context, Events events) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter(); this.events = events;
    }
    public boolean supported() { return adapter != null; }
    public boolean enabled() { return adapter != null && adapter.isEnabled(); }
    public boolean running() { return running; }
    public List<BluetoothDevice> paired() {
        List<BluetoothDevice> result = new ArrayList<>(adapter.getBondedDevices());
        result.sort(Comparator.comparing(d -> String.valueOf(d.getName()))); return result;
    }
    public List<String> descriptions() {
        List<String> result = new ArrayList<>();
        for (Link link : links.values()) result.add(link.name + " · " + link.peer);
        Collections.sort(result); return result;
    }
    public synchronized void start() throws IOException {
        if (running) return;
        if (!enabled()) throw new IOException("Turn Bluetooth on first");
        BluetoothServerSocket first = adapter.listenUsingRfcommWithServiceRecord("Pocket Relay", SERVICE);
        server = first; running = true; int epoch = ++generation;
        threads.execute(() -> acceptLoop(first, epoch)); events.log("Listening for Pocket Relay connections");
    }
    private void acceptLoop(BluetoothServerSocket initial, int epoch) {
        BluetoothServerSocket listener = initial;
        try {
            while (running && generation == epoch) {
                BluetoothSocket socket = listener.accept();
                close(listener);
                if (!running || generation != epoch) { close(socket); break; }
                attach(socket, epoch);
                synchronized (this) {
                    if (!running || generation != epoch) break;
                    listener = adapter.listenUsingRfcommWithServiceRecord("Pocket Relay", SERVICE); server = listener;
                }
            }
        } catch (Exception error) {
            if (running && generation == epoch) {
                events.log("Listening stopped: " + safe(error)); stop();
            }
        } finally { close(listener); }
    }
    public void connect(BluetoothDevice device) {
        if (!running) { events.log("Start Bluetooth links first"); return; }
        String address = device.getAddress();
        if (links.containsKey(address) || !connecting.add(address)) { events.log("Already connected or connecting"); return; }
        int epoch = generation;
        threads.execute(() -> {
            BluetoothSocket socket = null; ScheduledFuture<?> timeout = null;
            try {
                adapter.cancelDiscovery(); socket = device.createRfcommSocketToServiceRecord(SERVICE); pending.add(socket);
                BluetoothSocket candidate = socket;
                timeout = timers.schedule(() -> close(candidate), 25, TimeUnit.SECONDS);
                events.log("Connecting to " + device.getName()); socket.connect();
                timeout.cancel(false); pending.remove(socket);
                if (running && generation == epoch) attach(socket, epoch); else close(socket);
            } catch (Exception error) { close(socket); events.log("Connection failed: " + safe(error) + ". Keep Pocket Relay listening on the other phone."); }
            finally { if (timeout != null) timeout.cancel(false); if (socket != null) pending.remove(socket); connecting.remove(address); }
        });
    }
    private void attach(BluetoothSocket socket, int epoch) throws IOException {
        Link link = new Link(socket);
        synchronized (this) {
            if (!running || generation != epoch || links.size() >= 8 || links.putIfAbsent(link.peer, link) != null) {
                close(socket); return;
            }
        }
        // The writer must be ready before the core enqueues its identity and saved packets.
        threads.execute(link::writeLoop); events.connected(link.peer); threads.execute(link::readLoop);
    }
    public void send(String peer, byte[] bytes) {
        Link link = links.get(peer);
        if (link == null) return;
        if (bytes.length > org.pocketrelay.core.RelayCore.MAX_FRAME || !link.outbound.offer(bytes.clone())) {
            events.log("Link queue full; reconnect or use Retry queue"); link.shutdown();
        }
    }
    public void disconnect(String address) { Link link = links.get(address); if (link != null) link.shutdown(); }
    public synchronized void stop() {
        running = false; generation++; close(server); server = null;
        for (BluetoothSocket socket : pending) close(socket);
        for (Link link : new ArrayList<>(links.values())) link.shutdown();
    }
    public void destroy() { stop(); timers.shutdownNow(); threads.shutdownNow(); }
    private final class Link {
        final BluetoothSocket socket;
        final String peer, name;
        final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(256);
        final DataInputStream in;
        final DataOutputStream out;
        volatile boolean open = true;
        Link(BluetoothSocket socket) throws IOException {
            this.socket = socket; peer = socket.getRemoteDevice().getAddress(); name = String.valueOf(socket.getRemoteDevice().getName());
            in = new DataInputStream(socket.getInputStream()); out = new DataOutputStream(socket.getOutputStream());
        }
        void readLoop() {
            try {
                while (open) {
                    int size = in.readInt();
                    if (size <= 0 || size > org.pocketrelay.core.RelayCore.MAX_FRAME) throw new IOException("Invalid Bluetooth frame size");
                    byte[] bytes = new byte[size]; in.readFully(bytes); events.frame(peer, bytes);
                }
            } catch (Exception error) { if (open) events.log("Link ended: " + safe(error)); }
            finally { shutdown(); }
        }
        void writeLoop() {
            try {
                while (open) {
                    byte[] bytes = outbound.poll(1, TimeUnit.SECONDS); if (bytes == null) continue;
                    out.writeInt(bytes.length); out.write(bytes); out.flush();
                }
            } catch (Exception error) { if (open) events.log("Send failed: " + safe(error)); }
            finally { shutdown(); }
        }
        synchronized void shutdown() {
            if (!open) return; open = false; close(socket);
            if (links.remove(peer, this)) events.disconnected(peer);
        }
    }
    private static void close(Closeable object) { if (object != null) try { object.close(); } catch (IOException ignored) { } }
    private static String safe(Exception error) { String text = error.getMessage(); return text == null ? error.getClass().getSimpleName() : text; }
}
