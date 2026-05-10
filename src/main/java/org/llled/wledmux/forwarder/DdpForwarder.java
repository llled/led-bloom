package org.llled.wledmux.forwarder;

import jakarta.annotation.PreDestroy;
import org.llled.ddp.DdpClient;
import org.llled.ddp.DdpException;
import org.llled.ddp.DdpFrameListener;
import org.llled.ddp.DdpProtocol;
import org.llled.wledmux.config.MultiplexerConfig;
import org.llled.wledmux.discovery.WledDevice;
import org.llled.wledmux.discovery.WledDeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DdpForwarder implements DdpFrameListener {

    private static final Logger log = LoggerFactory.getLogger(DdpForwarder.class);

    private final MultiplexerConfig config;
    private final WledDeviceRegistry registry;
    private final ConcurrentHashMap<String, DdpClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceMapping> mappings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> deviceBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> packetBuffers = new ConcurrentHashMap<>();

    private final AtomicLong frameCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final Random random = new Random();

    public DdpForwarder(MultiplexerConfig config, WledDeviceRegistry registry) {
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
        String ip = device.getIp();

        // Remove old resources if any
        removeDevice(device);

        int frameWidth = config.getFrameWidth();
        int frameHeight = config.getFrameHeight();
        int devW = device.getWidth();
        int devH = device.getHeight();

        // Ensure device fits within frame
        if (devW > frameWidth || devH > frameHeight) {
            log.warn("Device {} ({}x{}) is larger than frame ({}x{}), clamping",
                    ip, devW, devH, frameWidth, frameHeight);
            devW = Math.min(devW, frameWidth);
            devH = Math.min(devH, frameHeight);
        }

        int maxTx = frameWidth - devW;
        int maxTy = frameHeight - devH;
        int tx = maxTx > 0 ? random.nextInt(maxTx) : 0;
        int ty = maxTy > 0 ? random.nextInt(maxTy) : 0;

        DeviceMapping mapping = new DeviceMapping(ip, devW, devH, tx, ty, frameWidth);
        mappings.put(ip, mapping);

        int pixelCount = devW * devH;
        deviceBuffers.put(ip, new byte[pixelCount * 3]);
        packetBuffers.put(ip, new byte[DdpProtocol.MAX_PACKET_SIZE]);

        try {
            DdpClient client = new DdpClient(ip);
            clients.put(ip, client);
            log.info("Added device {} at position ({},{}) size {}x{}", ip, tx, ty, devW, devH);
        } catch (DdpException e) {
            log.error("Failed to create DDP client for {}: {}", ip, e.getMessage());
            mappings.remove(ip);
            deviceBuffers.remove(ip);
            packetBuffers.remove(ip);
        }
    }

    void removeDevice(WledDevice device) {
        String ip = device.getIp();
        DdpClient old = clients.remove(ip);
        if (old != null) {
            old.close();
            log.info("Removed device {}", ip);
        }
        mappings.remove(ip);
        deviceBuffers.remove(ip);
        packetBuffers.remove(ip);
    }

    @Override
    public void onFrameReceived(byte[] frameData, int dataLength, byte dataType) {
        frameCount.incrementAndGet();

        for (var entry : mappings.entrySet()) {
            String ip = entry.getKey();
            DeviceMapping mapping = entry.getValue();
            DdpClient client = clients.get(ip);
            byte[] devBuf = deviceBuffers.get(ip);
            byte[] pktBuf = packetBuffers.get(ip);

            if (client == null || devBuf == null || pktBuf == null) continue;

            mapping.extractPixels(frameData, dataLength, devBuf);

            try {
                client.sendRgbFrame(devBuf, mapping.getPixelCount(), pktBuf);
            } catch (DdpException e) {
                errorCount.incrementAndGet();
                log.debug("Failed to forward frame to {}: {}", ip, e.getMessage());
            }
        }
    }

    @Override
    public void onError(DdpException e) {
        errorCount.incrementAndGet();
        log.warn("DDP receive error: {}", e.getMessage());
    }

    public long getFrameCount() { return frameCount.get(); }
    public long getErrorCount() { return errorCount.get(); }
    public int getActiveDeviceCount() { return clients.size(); }

    public ConcurrentHashMap<String, DeviceMapping> getMappings() { return mappings; }
}
