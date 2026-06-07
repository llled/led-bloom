package org.llled.wledmux.api;

import org.llled.wledmux.config.MultiplexerConfig;
import org.llled.wledmux.discovery.WledDeviceRegistry;
import org.llled.wledmux.forwarder.DdpForwarder;
import org.llled.wledmux.receiver.DdpFrameReceiver;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class StatusController {

    private final DdpForwarder forwarder;
    private final DdpFrameReceiver frameReceiver;
    private final WledDeviceRegistry registry;
    private final MultiplexerConfig config;

    public StatusController(DdpForwarder forwarder, DdpFrameReceiver frameReceiver,
                            WledDeviceRegistry registry, MultiplexerConfig config) {
        this.forwarder = forwarder;
        this.frameReceiver = frameReceiver;
        this.registry = registry;
        this.config = config;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        status.put("uptime", Duration.ofMillis(uptimeMs).toString());
        status.put("receiverRunning", frameReceiver.isRunning());
        status.put("ddpListenPort", config.getDdpListenPort());
        status.put("frameWidth", config.getFrameWidth());
        status.put("frameHeight", config.getFrameHeight());
        status.put("deviceCount", registry.size());
        status.put("activeForwarders", forwarder.getActiveDeviceCount());
        status.put("framesReceived", forwarder.getFrameCount());
        status.put("forwardedPackets", forwarder.getForwardedPackets());
        status.put("framesPerSecond", forwarder.getFramesPerSecond());
        status.put("packetsPerSecond", forwarder.getPacketsPerSecond());
        status.put("fanoutMicrosLast", forwarder.getLastFanoutMicros());
        status.put("fanoutMicrosAvg", forwarder.getAvgFanoutMicros());
        status.put("fanoutMicrosMax", forwarder.getMaxFanoutMicros());
        status.put("frameIntervalMicrosAt60", 16667);
        status.put("errors", forwarder.getErrorCount());
        return status;
    }
}
