package org.llled.ledbloom.discovery;

import org.llled.ledbloom.config.LedBloomConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WledDeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(WledDeviceRegistry.class);

    // Keyed by device identity (ip:port).
    private final ConcurrentHashMap<String, WledDevice> devices = new ConcurrentHashMap<>();
    private final LedBloomConfig config;

    private Consumer<WledDevice> onDeviceAdded;
    private Consumer<WledDevice> onDeviceRemoved;

    public WledDeviceRegistry(LedBloomConfig config) {
        this.config = config;
    }

    public void setOnDeviceAdded(Consumer<WledDevice> callback) {
        this.onDeviceAdded = callback;
    }

    public void setOnDeviceRemoved(Consumer<WledDevice> callback) {
        this.onDeviceRemoved = callback;
    }

    /** Backward-compatible registration for discovered devices (port 4048, not pinned). */
    public WledDevice register(String ip, String name, int ledCount, int width, int height) {
        return register(ip, WledDevice.DEFAULT_PORT, name, ledCount, width, height, false);
    }

    public WledDevice register(String ip, int port, String name, int ledCount, int width, int height,
                               boolean pinned) {
        String key = WledDevice.key(ip, port);
        WledDevice existing = devices.get(key);
        if (existing != null) {
            boolean changed = existing.getWidth() != width || existing.getHeight() != height;
            existing.setName(name);
            existing.setLedCount(ledCount);
            existing.setWidth(width);
            existing.setHeight(height);
            // Never un-pin a manually-added device via a later (discovery) re-register.
            existing.setPinned(existing.isPinned() || pinned);
            existing.touch();
            if (changed && onDeviceRemoved != null && onDeviceAdded != null) {
                log.info("Device {} changed dimensions, re-registering", key);
                onDeviceRemoved.accept(existing);
                onDeviceAdded.accept(existing);
            }
            return existing;
        }

        WledDevice device = new WledDevice(ip, port, name, ledCount, width, height);
        device.setPinned(pinned);
        devices.put(key, device);
        log.info("Registered new device: {}", device);
        if (onDeviceAdded != null) {
            onDeviceAdded.accept(device);
        }
        return device;
    }

    public WledDevice getByKey(String key) {
        return devices.get(key);
    }

    public WledDevice get(String ip, int port) {
        return devices.get(WledDevice.key(ip, port));
    }

    /** Legacy lookup by bare IP: returns the first device matching that IP (any port). */
    public WledDevice get(String ip) {
        return devices.values().stream()
                .filter(d -> d.getIp().equals(ip))
                .findFirst()
                .orElse(null);
    }

    public WledDevice remove(String key) {
        WledDevice removed = devices.remove(key);
        if (removed != null && onDeviceRemoved != null) {
            onDeviceRemoved.accept(removed);
        }
        return removed;
    }

    /** Remove all pinned (manually-added) devices. Returns the number removed. */
    public int clearPinned() {
        int[] count = {0};
        devices.entrySet().removeIf(entry -> {
            if (entry.getValue().isPinned()) {
                if (onDeviceRemoved != null) {
                    onDeviceRemoved.accept(entry.getValue());
                }
                count[0]++;
                return true;
            }
            return false;
        });
        return count[0];
    }

    public Collection<WledDevice> getAll() {
        return devices.values();
    }

    public int size() {
        return devices.size();
    }

    public void purgeExpired() {
        Duration timeout = Duration.ofMinutes(config.getDeviceTimeoutMinutes());
        Instant cutoff = Instant.now().minus(timeout);

        devices.entrySet().removeIf(entry -> {
            WledDevice device = entry.getValue();
            if (device.isPinned()) {
                return false; // manually-added devices never expire
            }
            if (device.getLastSeen().isBefore(cutoff)) {
                log.warn("Purging expired device: {}", device);
                if (onDeviceRemoved != null) {
                    onDeviceRemoved.accept(device);
                }
                return true;
            }
            return false;
        });
    }
}
