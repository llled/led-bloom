package org.llled.ledbloom.api;

import org.llled.ledbloom.discovery.WledDevice;
import org.llled.ledbloom.discovery.WledDeviceRegistry;
import org.llled.ledbloom.discovery.WledDiscoveryRunner;
import org.llled.ledbloom.forwarder.DdpForwarder;
import org.llled.ledbloom.forwarder.DeviceMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class DeviceController {

    private final WledDeviceRegistry registry;
    private final DdpForwarder forwarder;
    private final WledDiscoveryRunner discoveryRunner;

    public DeviceController(WledDeviceRegistry registry, DdpForwarder forwarder,
                            WledDiscoveryRunner discoveryRunner) {
        this.registry = registry;
        this.forwarder = forwarder;
        this.discoveryRunner = discoveryRunner;
    }

    /** Request body for manually adding a device (bypasses discovery). */
    public record AddDeviceRequest(String ip, Integer port, String name,
                                   int ledCount, int width, int height) {}

    @GetMapping("/devices")
    public List<Map<String, Object>> getDevices() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WledDevice device : registry.getAll()) {
            result.add(deviceToMap(device));
        }
        return result;
    }

    @GetMapping("/devices/{id}")
    public ResponseEntity<Map<String, Object>> getDevice(@PathVariable String id) {
        WledDevice device = lookup(id);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(deviceToMap(device));
    }

    @PostMapping("/devices")
    public ResponseEntity<Map<String, Object>> addDevice(@RequestBody AddDeviceRequest req) {
        if (req.ip() == null || req.ip().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ip is required"));
        }
        if (req.ledCount() <= 0 || req.width() <= 0 || req.height() <= 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "ledCount, width and height must be > 0"));
        }
        int port = req.port() != null ? req.port() : WledDevice.DEFAULT_PORT;
        String name = (req.name() != null && !req.name().isBlank()) ? req.name() : req.ip();
        WledDevice device = registry.register(req.ip(), port, name,
                req.ledCount(), req.width(), req.height(), true);
        return ResponseEntity.status(201).body(deviceToMap(device));
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable String id) {
        String key = id.contains(":") ? id : WledDevice.key(id, WledDevice.DEFAULT_PORT);
        WledDevice removed = registry.remove(key);
        return removed != null ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Remove all manually-added (pinned) devices. Used to clean up after a load test. */
    @DeleteMapping("/devices")
    public Map<String, Object> clearPinned() {
        int removed = registry.clearPinned();
        return Map.of("removed", removed);
    }

    @PostMapping("/discovery/trigger")
    public Map<String, String> triggerDiscovery() {
        discoveryRunner.triggerDiscovery();
        return Map.of("status", "discovery triggered");
    }

    private WledDevice lookup(String id) {
        return id.contains(":") ? registry.getByKey(id) : registry.get(id);
    }

    private Map<String, Object> deviceToMap(WledDevice device) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", device.key());
        map.put("ip", device.getIp());
        map.put("port", device.getPort());
        map.put("name", device.getName());
        map.put("ledCount", device.getLedCount());
        map.put("width", device.getWidth());
        map.put("height", device.getHeight());
        map.put("pinned", device.isPinned());
        map.put("lastSeen", device.getLastSeen().toString());

        DeviceMapping mapping = forwarder.getMappings().get(device.key());
        if (mapping != null) {
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("translateX", mapping.getTranslateX());
            pos.put("translateY", mapping.getTranslateY());
            pos.put("deviceWidth", mapping.getDeviceWidth());
            pos.put("deviceHeight", mapping.getDeviceHeight());
            map.put("mapping", pos);
        }

        return map;
    }
}
