package org.llled.ledbloom.forwarder;

import jakarta.annotation.PreDestroy;
import org.llled.ddp.DdpClient;
import org.llled.ddp.DdpException;
import org.llled.ddp.DdpFrameListener;
import org.llled.ddp.DdpProtocol;
import org.llled.ledbloom.config.LedBloomConfig;
import org.llled.ledbloom.discovery.WledDevice;
import org.llled.ledbloom.discovery.WledDeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DdpForwarder implements DdpFrameListener {

    private static final Logger log = LoggerFactory.getLogger(DdpForwarder.class);

    private final LedBloomConfig config;
    private final WledDeviceRegistry registry;
    // All maps keyed by device identity (ip:port).
    private final ConcurrentHashMap<String, DdpClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceMapping> mappings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> deviceBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> packetBuffers = new ConcurrentHashMap<>();

    private final AtomicLong frameCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong forwardedPackets = new AtomicLong();
    private final Random random = new Random();

    // Fan-out timing. These are written only on the single DDP receiver thread,
    // so plain fields + volatile reads are sufficient (no locking on the hot path).
    private volatile long lastFanoutNanos;
    private volatile long maxFanoutNanos;
    private volatile long fanoutSumNanos;
    private volatile long fanoutSamples;

    // Per-second rates, snapshotted off the hot path by a scheduled task.
    private volatile long framesPerSecond;
    private volatile long packetsPerSecond;
    private long lastFrameSnapshot;
    private long lastPacketSnapshot;

    public DdpForwarder(LedBloomConfig config, WledDeviceRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.setOnDeviceAdded(this::addDevice);
        registry.setOnDeviceRemoved(this::removeDevice);
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(DdpClient::close);
        clients.clear();
        mappings.clear();
        deviceBuffers.clear();
        packetBuffers.clear();
    }

    void addDevice(WledDevice device) {
        String key = device.key();

        // Remove old resources if any
        removeDevice(device);

        int frameWidth = config.getFrameWidth();
        int frameHeight = config.getFrameHeight();
        int devW = device.getWidth();
        int devH = device.getHeight();

        // Ensure device fits within frame
        if (devW > frameWidth || devH > frameHeight) {
            log.warn("Device {} ({}x{}) is larger than frame ({}x{}), clamping",
                    key, devW, devH, frameWidth, frameHeight);
            devW = Math.min(devW, frameWidth);
            devH = Math.min(devH, frameHeight);
        }

        int maxTx = frameWidth - devW;
        int maxTy = frameHeight - devH;
        int tx = maxTx > 0 ? random.nextInt(maxTx) : 0;
        int ty = maxTy > 0 ? random.nextInt(maxTy) : 0;

        DeviceMapping mapping = new DeviceMapping(device.getIp(), devW, devH, tx, ty, frameWidth);
        mappings.put(key, mapping);

        int pixelCount = devW * devH;
        deviceBuffers.put(key, new byte[pixelCount * 3]);
        packetBuffers.put(key, new byte[DdpProtocol.MAX_PACKET_SIZE]);

        try {
            DdpClient client = new DdpClient(device.getIp(), device.getPort());
            clients.put(key, client);
            log.info("Added device {} at position ({},{}) size {}x{}", key, tx, ty, devW, devH);
        } catch (DdpException e) {
            log.error("Failed to create DDP client for {}: {}", key, e.getMessage());
            mappings.remove(key);
            deviceBuffers.remove(key);
            packetBuffers.remove(key);
        }
    }

    void removeDevice(WledDevice device) {
        String key = device.key();
        DdpClient old = clients.remove(key);
        if (old != null) {
            old.close();
            log.info("Removed device {}", key);
        }
        mappings.remove(key);
        deviceBuffers.remove(key);
        packetBuffers.remove(key);
    }

    @Override
    public void onFrameReceived(byte[] frameData, int dataLength, byte dataType) {
        frameCount.incrementAndGet();

        long start = System.nanoTime();
        int sent = 0;

        for (var entry : mappings.entrySet()) {
            String key = entry.getKey();
            DeviceMapping mapping = entry.getValue();
            DdpClient client = clients.get(key);
            byte[] devBuf = deviceBuffers.get(key);
            byte[] pktBuf = packetBuffers.get(key);

            if (client == null || devBuf == null || pktBuf == null) continue;

            mapping.extractPixels(frameData, dataLength, devBuf);

            try {
                client.sendRgbFrame(devBuf, mapping.getPixelCount(), pktBuf);
                sent++;
            } catch (DdpException e) {
                errorCount.incrementAndGet();
                log.debug("Failed to forward frame to {}: {}", key, e.getMessage());
            }
        }

        long dur = System.nanoTime() - start;
        forwardedPackets.addAndGet(sent);
        lastFanoutNanos = dur;
        fanoutSumNanos += dur;
        fanoutSamples++;
        if (dur > maxFanoutNanos) {
            maxFanoutNanos = dur;
        }
    }

    /** Snapshot per-second rates off the hot path. */
    @Scheduled(fixedRate = 1000)
    public void snapshotRates() {
        long frames = frameCount.get();
        long packets = forwardedPackets.get();
        framesPerSecond = frames - lastFrameSnapshot;
        packetsPerSecond = packets - lastPacketSnapshot;
        lastFrameSnapshot = frames;
        lastPacketSnapshot = packets;
    }

    @Override
    public void onError(DdpException e) {
        errorCount.incrementAndGet();
        log.warn("DDP receive error: {}", e.getMessage());
    }

    public long getFrameCount() { return frameCount.get(); }
    public long getErrorCount() { return errorCount.get(); }
    public int getActiveDeviceCount() { return clients.size(); }
    public long getForwardedPackets() { return forwardedPackets.get(); }
    public long getFramesPerSecond() { return framesPerSecond; }
    public long getPacketsPerSecond() { return packetsPerSecond; }
    public long getLastFanoutMicros() { return lastFanoutNanos / 1000; }
    public long getMaxFanoutMicros() { return maxFanoutNanos / 1000; }
    public long getAvgFanoutMicros() {
        long samples = fanoutSamples;
        return samples > 0 ? (fanoutSumNanos / samples) / 1000 : 0;
    }

    public ConcurrentHashMap<String, DeviceMapping> getMappings() { return mappings; }
}
