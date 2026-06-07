package org.llled.ledbloom.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "ledbloom")
public class LedBloomConfig {

    private int ddpListenPort = 4048;
    private int frameWidth = 64;
    private int frameHeight = 48;
    private String ipBlock = "";
    private int discoveryIntervalSeconds = 60;
    private int deviceTimeoutMinutes = 5;
    private boolean mdnsDiscoveryEnabled = true;
    private boolean ipRangeDiscoveryEnabled = true;
    private List<String> skipIps = new ArrayList<>();

    @PostConstruct
    void validate() {
        if (!mdnsDiscoveryEnabled && !ipRangeDiscoveryEnabled) {
            throw new IllegalStateException(
                "At least one discovery method must be enabled. " +
                "Set ledbloom.mdns-discovery-enabled=true and/or " +
                "ledbloom.ip-range-discovery-enabled=true.");
        }
    }

    public int getDdpListenPort() { return ddpListenPort; }
    public void setDdpListenPort(int ddpListenPort) { this.ddpListenPort = ddpListenPort; }

    public int getFrameWidth() { return frameWidth; }
    public void setFrameWidth(int frameWidth) { this.frameWidth = frameWidth; }

    public int getFrameHeight() { return frameHeight; }
    public void setFrameHeight(int frameHeight) { this.frameHeight = frameHeight; }

    public String getIpBlock() { return ipBlock; }
    public void setIpBlock(String ipBlock) { this.ipBlock = ipBlock; }

    public int getDiscoveryIntervalSeconds() { return discoveryIntervalSeconds; }
    public void setDiscoveryIntervalSeconds(int discoveryIntervalSeconds) { this.discoveryIntervalSeconds = discoveryIntervalSeconds; }

    public int getDeviceTimeoutMinutes() { return deviceTimeoutMinutes; }
    public void setDeviceTimeoutMinutes(int deviceTimeoutMinutes) { this.deviceTimeoutMinutes = deviceTimeoutMinutes; }

    public boolean isMdnsDiscoveryEnabled() { return mdnsDiscoveryEnabled; }
    public void setMdnsDiscoveryEnabled(boolean mdnsDiscoveryEnabled) { this.mdnsDiscoveryEnabled = mdnsDiscoveryEnabled; }

    public boolean isIpRangeDiscoveryEnabled() { return ipRangeDiscoveryEnabled; }
    public void setIpRangeDiscoveryEnabled(boolean ipRangeDiscoveryEnabled) { this.ipRangeDiscoveryEnabled = ipRangeDiscoveryEnabled; }

    public List<String> getSkipIps() { return skipIps; }
    public void setSkipIps(List<String> skipIps) { this.skipIps = skipIps; }
}
