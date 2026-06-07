package org.llled.ledbloom.api;

import org.llled.ledbloom.config.LedBloomConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ConfigController {

    private final LedBloomConfig config;

    public ConfigController(LedBloomConfig config) {
        this.config = config;
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ddpListenPort", config.getDdpListenPort());
        m.put("frameWidth", config.getFrameWidth());
        m.put("frameHeight", config.getFrameHeight());
        m.put("ipBlock", config.getIpBlock());
        m.put("discoveryIntervalSeconds", config.getDiscoveryIntervalSeconds());
        m.put("deviceTimeoutMinutes", config.getDeviceTimeoutMinutes());
        m.put("mdnsDiscoveryEnabled", config.isMdnsDiscoveryEnabled());
        m.put("ipRangeDiscoveryEnabled", config.isIpRangeDiscoveryEnabled());
        m.put("skipIps", config.getSkipIps());
        return m;
    }
}
