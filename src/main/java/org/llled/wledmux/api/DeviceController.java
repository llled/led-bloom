package org.llled.wledmux.api;

import org.llled.wledmux.discovery.WledDevice;
import org.llled.wledmux.discovery.WledDeviceRegistry;
import org.llled.wledmux.discovery.WledDiscoveryRunner;
import org.llled.wledmux.forwarder.DdpForwarder;
import org.llled.wledmux.forwarder.DeviceMapping;
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

    @GetMapping("/devices")
    public List<Map<String, Object>> getDevices() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WledDevice device : registry.getAll()) {
            result.add(deviceToMap(device));
        }
        return result;
    }

    @GetMapping("/devices/{ip}")
    public ResponseEntity<Map<String, Object>> getDevice(@PathVariable String ip) {
        WledDevice device = registry.get(ip);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(deviceToMap(device));
    }

    @PostMapping("/discovery/trigger")
    public Map<String, String> triggerDiscovery() {
        discoveryRunner.triggerDiscovery();
        return Map.of("status", "discovery triggered");
    }

    private Map<String, Object> deviceToMap(WledDevice device) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ip", device.getIp());
        map.put("name", device.getName());
        map.put("ledCount", device.getLedCount());
        map.put("width", device.getWidth());
        map.put("height", device.getHeight());
        map.put("lastSeen", device.getLastSeen().toString());

        DeviceMapping mapping = forwarder.getMappings().get(device.getIp());
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
