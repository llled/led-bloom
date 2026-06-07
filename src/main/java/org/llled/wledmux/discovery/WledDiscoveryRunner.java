package org.llled.wledmux.discovery;

import org.llled.wledmux.config.MultiplexerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.IOException;
import java.net.*;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class WledDiscoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(WledDiscoveryRunner.class);

    private final MultiplexerConfig config;
    private final WledDeviceRegistry registry;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private String ipBlock;

    public WledDiscoveryRunner(MultiplexerConfig config, WledDeviceRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${multiplexer.discovery-interval-seconds:60}000",
               initialDelayString = "5000")
    public void runDiscovery() {
        String block = resolveIpBlock();
        if (block == null || block.isEmpty()) {
            log.warn("Could not determine IP block for discovery");
            return;
        }
        this.ipBlock = block;

        log.info("Running WLED discovery on {}", ipBlock);

        Set<String> skipIps = new HashSet<>(config.getSkipIps());

        // mDNS-discovered devices are scanned the moment they resolve, so they
        // are registered without waiting for the (much slower) IP range scan.
        if (config.isMdnsDiscoveryEnabled()) {
            discoverViaMdns(skipIps);
        }
        if (config.isIpRangeDiscoveryEnabled()) {
            for (String ip : discoverViaIpRange()) {
                if (!skipIps.contains(ip)) {
                    executor.execute(new WledScanner(ip, registry));
                }
            }
        }

        registry.purgeExpired();
    }

    public void triggerDiscovery() {
        executor.execute(this::runDiscovery);
    }

    private String resolveIpBlock() {
        String configured = config.getIpBlock();
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        if (ipBlock != null) {
            return ipBlock;
        }
        return detectIpBlock();
    }

    String detectIpBlock() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        String hostAddr = addr.getHostAddress();
                        int lastDot = hostAddr.lastIndexOf('.');
                        if (lastDot > 0) {
                            String block = hostAddr.substring(0, lastDot + 1);
                            log.info("Auto-detected IP block: {}", block);
                            return block;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            log.error("Failed to enumerate network interfaces", e);
        }
        return null;
    }

    void discoverViaMdns(Set<String> skipIps) {
        Set<String> seen = new HashSet<>();
        JmDNS jmdns = null;
        try {
            jmdns = JmDNS.create(InetAddress.getLocalHost());

            jmdns.addServiceListener("_wled._tcp.local.", new ServiceListener() {
                @Override
                public void serviceAdded(ServiceEvent event) {
                    event.getDNS().requestServiceInfo(event.getType(), event.getName(), 1);
                }

                @Override
                public void serviceRemoved(ServiceEvent event) {}

                @Override
                public void serviceResolved(ServiceEvent event) {
                    ServiceInfo info = event.getInfo();
                    if (info.getHostAddresses().length > 0) {
                        String host = info.getHostAddresses()[0];
                        if (skipIps.contains(host) || !seen.add(host)) {
                            return;
                        }
                        log.info("mDNS discovered WLED: {} at {}", info.getName(), host);
                        // Scan immediately so the device is registered without
                        // waiting for the rest of mDNS discovery or the IP scan.
                        executor.execute(new WledScanner(host, registry));
                    }
                }
            });

            Thread.sleep(5000);
        } catch (IOException e) {
            log.error("Error during mDNS discovery", e);
        } catch (InterruptedException e) {
            log.error("mDNS discovery interrupted", e);
            Thread.currentThread().interrupt();
        } finally {
            if (jmdns != null) {
                try { jmdns.close(); } catch (IOException e) { log.error("Error closing mDNS", e); }
            }
        }
    }

    Set<String> discoverViaIpRange() {
        Set<String> ips = new HashSet<>();
        if (ipBlock == null) return ips;
        for (int i = 2; i < 255; i++) {
            ips.add(ipBlock + i);
        }
        return ips;
    }
}
