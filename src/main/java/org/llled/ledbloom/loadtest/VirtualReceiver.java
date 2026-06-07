package org.llled.ledbloom.loadtest;

import org.llled.ddp.DdpException;
import org.llled.ddp.DdpFrameListener;
import org.llled.ddp.DdpReceiver;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Standalone virtual DDP receiver for scale testing LED Bloom.
 *
 * <p>Binds a contiguous range of UDP ports ({@code basePort .. basePort+count-1}); each port
 * stands in for one virtual WLED device. It counts the DDP frames delivered to each port and
 * logs aggregate + per-port FPS once a second, so you can see the framerate LED Bloom is
 * actually able to push out and spot any single device that is lagging the rest.
 *
 * <p>Reuses the project's {@code ddp-lighting-java} {@link DdpReceiver}: {@code onFrameReceived}
 * fires exactly once per fully-received (PUSHed) frame.
 *
 * <p>Caveat: each {@link DdpReceiver} opens one UDP socket and runs one daemon thread, so the
 * count is bounded by OS file-descriptor / thread limits. ~256 is comfortable on a desktop; to
 * push further, run multiple receiver machines, each binding a slice of the port range.
 *
 * <p>Configuration (system properties):
 * <ul>
 *   <li>{@code vr.basePort} (default 5000) — first port to bind</li>
 *   <li>{@code vr.count} (default 100) — number of consecutive ports / virtual devices</li>
 *   <li>{@code vr.bufferSize} (default 9216 = 64*48*3) — per-frame buffer; must be &ge; the
 *       largest device frame or frames are truncated</li>
 * </ul>
 */
public final class VirtualReceiver {

    private VirtualReceiver() {}

    public static void main(String[] args) throws Exception {
        int basePort = intProp("vr.basePort", 5000);
        int count = intProp("vr.count", 100);
        int bufferSize = intProp("vr.bufferSize", 64 * 48 * 3);

        System.out.printf("VirtualReceiver: binding %d ports %d..%d (bufferSize=%d)%n",
                count, basePort, basePort + count - 1, bufferSize);

        final LongAdder[] frames = new LongAdder[count];
        final LongAdder[] bytes = new LongAdder[count];
        final DdpReceiver[] receivers = new DdpReceiver[count];
        for (int i = 0; i < count; i++) {
            frames[i] = new LongAdder();
            bytes[i] = new LongAdder();
        }

        int bound = 0;
        for (int i = 0; i < count; i++) {
            final int idx = i;
            int port = basePort + i;
            DdpFrameListener listener = new DdpFrameListener() {
                @Override
                public void onFrameReceived(byte[] data, int dataLength, byte dataType) {
                    frames[idx].increment();
                    bytes[idx].add(dataLength);
                }
            };
            try {
                DdpReceiver receiver = new DdpReceiver(port, listener, bufferSize);
                receiver.start();
                receivers[i] = receiver;
                bound++;
            } catch (DdpException e) {
                System.err.printf("Failed to bind port %d: %s%n", port, e.getMessage());
            }
        }
        System.out.printf("VirtualReceiver: %d/%d ports bound, waiting for frames...%n", bound, count);

        final long[] prevFrames = new long[count];
        final long[] prevBytes = new long[count];

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vr-reporter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            long totalDeltaFrames = 0;
            long totalDeltaBytes = 0;
            long totalFrames = 0;
            int activePorts = 0;
            long minFps = Long.MAX_VALUE;
            long maxFps = 0;
            for (int i = 0; i < count; i++) {
                long f = frames[i].sum();
                long b = bytes[i].sum();
                long df = f - prevFrames[i];
                long db = b - prevBytes[i];
                prevFrames[i] = f;
                prevBytes[i] = b;
                totalFrames += f;
                totalDeltaFrames += df;
                totalDeltaBytes += db;
                if (df > 0) {
                    activePorts++;
                    minFps = Math.min(minFps, df);
                    maxFps = Math.max(maxFps, df);
                }
            }
            if (activePorts == 0) {
                minFps = 0;
            }
            double mbps = totalDeltaBytes / (1024.0 * 1024.0);
            System.out.printf(
                    "aggregate=%d fps over %d/%d active ports | per-port fps min=%d max=%d | %.2f MB/s | total frames=%d%n",
                    totalDeltaFrames, activePorts, count, minFps, maxFps, mbps, totalFrames);
        }, 1, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            for (DdpReceiver r : receivers) {
                if (r != null) r.close();
            }
        }));

        // Block forever; receivers run on their own daemon threads.
        Thread.currentThread().join();
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key);
        return v != null ? Integer.parseInt(v.trim()) : def;
    }
}
