package org.llled.ledbloom.loadtest;

import org.llled.ddp.DdpClient;
import org.llled.ddp.DdpProtocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Standalone load-test runner for LED Bloom.
 *
 * <p>Registers N virtual devices via the LED Bloom REST API (all pointing at one virtual
 * receiver host across a port range), then streams synthetic DDP frames to the LED Bloom
 * ingress at a target framerate for a fixed duration. Pair it with {@link VirtualReceiver}
 * running on the receiver host.
 *
 * <p>Topology default: this runner runs on the same machine as LED Bloom (so the
 * ingress hop is loopback), and the virtual receiver runs on another machine whose IP is
 * passed as {@code lt.receiverHost}.
 *
 * <p>Configuration (system properties):
 * <ul>
 *   <li>{@code lt.muxHost} (default 127.0.0.1) — LED Bloom host for REST + DDP ingress</li>
 *   <li>{@code lt.apiPort} (default 8901) — LED Bloom REST port</li>
 *   <li>{@code lt.ingressPort} (default 4048) — LED Bloom DDP listen port</li>
 *   <li>{@code lt.receiverHost} (REQUIRED) — IP the virtual devices are registered with
 *       (the VirtualReceiver machine)</li>
 *   <li>{@code lt.devices} (default 100) — number of virtual devices</li>
 *   <li>{@code lt.baseEgressPort} (default 5000) — first egress port; must match VirtualReceiver's
 *       {@code vr.basePort}</li>
 *   <li>{@code lt.devW}/{@code lt.devH} (default 16x16) — per-device matrix size</li>
 *   <li>{@code lt.masterW}/{@code lt.masterH} (default 64x48) — must match LED Bloom's
 *       frame-width/frame-height</li>
 *   <li>{@code lt.fps} (default 60) — target send framerate</li>
 *   <li>{@code lt.durationSeconds} (default 30) — how long to stream</li>
 *   <li>{@code lt.cleanup} (default true) — DELETE the pinned devices when done</li>
 * </ul>
 */
public final class LoadTestRunner {

    private LoadTestRunner() {}

    public static void main(String[] args) throws Exception {
        String muxHost = prop("lt.muxHost", "127.0.0.1");
        int apiPort = intProp("lt.apiPort", 8901);
        int ingressPort = intProp("lt.ingressPort", 4048);
        String receiverHost = System.getProperty("lt.receiverHost");
        int devices = intProp("lt.devices", 100);
        int baseEgressPort = intProp("lt.baseEgressPort", 5000);
        int devW = intProp("lt.devW", 16);
        int devH = intProp("lt.devH", 16);
        int masterW = intProp("lt.masterW", 64);
        int masterH = intProp("lt.masterH", 48);
        int fps = intProp("lt.fps", 60);
        int durationSeconds = intProp("lt.durationSeconds", 30);
        boolean cleanup = Boolean.parseBoolean(prop("lt.cleanup", "true"));

        if (receiverHost == null || receiverHost.isBlank()) {
            System.err.println("ERROR: -Dlt.receiverHost=<receiver-ip> is required " +
                    "(the host running VirtualReceiver).");
            System.exit(2);
        }

        String apiBase = "http://" + muxHost + ":" + apiPort + "/api/v1";
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        System.out.printf("Registering %d devices -> %s:%d..%d (%dx%d each)...%n",
                devices, receiverHost, baseEgressPort, baseEgressPort + devices - 1, devW, devH);
        int ledCount = devW * devH;
        int registered = 0;
        for (int i = 0; i < devices; i++) {
            int port = baseEgressPort + i;
            String body = String.format(
                    "{\"ip\":\"%s\",\"port\":%d,\"name\":\"lt-%d\",\"ledCount\":%d,\"width\":%d,\"height\":%d}",
                    receiverHost, port, i, ledCount, devW, devH);
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/devices"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 201) {
                registered++;
            } else {
                System.err.printf("Register device %d failed: HTTP %d %s%n",
                        i, resp.statusCode(), resp.body());
            }
        }
        System.out.printf("Registered %d/%d devices.%n", registered, devices);
        if (registered == 0) {
            System.err.println("No devices registered; aborting.");
            System.exit(1);
        }

        // Build a synthetic master frame (RGB). A precomputed gradient that we phase-shift per
        // frame keeps generation cheap so it never becomes the bottleneck.
        int pixelCount = masterW * masterH;
        byte[] frame = new byte[pixelCount * 3];
        byte[] packetBuf = new byte[DdpProtocol.MAX_PACKET_SIZE];

        DdpClient ingress = new DdpClient(muxHost, ingressPort);
        System.out.printf("Streaming %dx%d frames to %s:%d at %d fps for %ds...%n",
                masterW, masterH, muxHost, ingressPort, fps, durationSeconds);

        long periodNs = 1_000_000_000L / fps;
        long startNs = System.nanoTime();
        long endNs = startNs + durationSeconds * 1_000_000_000L;
        long next = startNs;
        long framesSent = 0;
        long missedDeadlines = 0;
        int phase = 0;

        try {
            while (System.nanoTime() < endNs) {
                fillGradient(frame, masterW, masterH, phase++);
                ingress.sendRgbFrame(frame, pixelCount, packetBuf);
                framesSent++;

                next += periodNs;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                } else {
                    missedDeadlines++;
                    next = System.nanoTime(); // fell behind; resync to avoid burst catch-up
                }
            }
        } finally {
            ingress.close();
        }

        double elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0;
        double achievedFps = framesSent / elapsedSec;
        long bytesSent = framesSent * (long) frame.length;
        double mbps = (bytesSent / (1024.0 * 1024.0)) / elapsedSec;
        System.out.printf(
                "Done. framesSent=%d achievedFps=%.1f (target %d) missedDeadlines=%d sent=%.1f MB ingress=%.2f MB/s%n",
                framesSent, achievedFps, fps, missedDeadlines, bytesSent / (1024.0 * 1024.0), mbps);
        System.out.println("Expected delivered at receiver ~= achievedFps * deviceCount = "
                + Math.round(achievedFps) * registered + " frames/s");

        if (cleanup) {
            HttpRequest del = HttpRequest.newBuilder(URI.create(apiBase + "/devices"))
                    .DELETE()
                    .build();
            HttpResponse<String> resp = http.send(del, HttpResponse.BodyHandlers.ofString());
            System.out.printf("Cleanup: DELETE /devices -> HTTP %d %s%n", resp.statusCode(), resp.body());
        }
    }

    /** Fill an RGB frame with a moving diagonal gradient (cheap, non-zero, visually verifiable). */
    private static void fillGradient(byte[] frame, int w, int h, int phase) {
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                frame[i++] = (byte) ((x + phase) & 0xFF);
                frame[i++] = (byte) ((y + phase) & 0xFF);
                frame[i++] = (byte) ((x + y + phase) & 0xFF);
            }
        }
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v != null ? v : def;
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key);
        return v != null ? Integer.parseInt(v.trim()) : def;
    }
}
