package org.llled.wledmux.discovery;

import org.llled.wledmux.config.MultiplexerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class WledDeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(WledDeviceRegistry.class);

    private final ConcurrentHashMap<String, WledDevice> devices = new ConcurrentHashMap<>();
    private final MultiplexerConfig config;

    private Consumer<WledDevice> onDeviceAdded;
    private Consumer<WledDevice> onDeviceRemoved;

    public WledDeviceRegistry(MultiplexerConfig config) {
        this.config = config;
    }

    public void setOnDeviceAdded(Consumer<WledDevice> callback) {
        this.onDeviceAdded = callback;
    }

    public void setOnDeviceRemoved(Consumer<WledDevice> callback) {
        this.onDeviceRemoved = callback;
    }

    public WledDevice register(String ip, String name, int ledCount, int width, int height) {
        WledDevice existing = devices.get(ip);
        if (existing != null) {
            boolean changed = existing.getWidth() != width || existing.getHeight() != height;
            existing.setName(name);
            existing.setLedCount(ledCount);
            existing.setWidth(width);
            existing.setHeight(height);
            existing.touch();
            if (changed && onDeviceRemoved != null && onDeviceAdded != null) {
                log.info("Device {} changed dimensions, re-registering", ip);
                onDeviceRemoved.accept(existing);
                onDeviceAdded.accept(existing);
            }
            return existing;
        }

        WledDevice device = new WledDevice(ip, name, ledCount, width, height);
        devices.put(ip, device);
        log.info("Registered new device: {}", device);
        if (onDeviceAdded != null) {
            onDeviceAdded.accept(device);
        }
        return device;
    }

    public WledDevice get(String ip) {
        return devices.get(ip);
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
            if (entry.getValue().getLastSeen().isBefore(cutoff)) {
                log.warn("Purging expired device: {}", entry.getValue());
                if (onDeviceRemoved != null) {
                    onDeviceRemoved.accept(entry.getValue());
                }
                return true;
            }
            return false;
        });
    }
}
