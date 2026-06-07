package org.llled.ledbloom.discovery;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Advertises this LED Bloom instance over mDNS as "led-bloom" so it can be
 * discovered on the local network (e.g. as led-bloom.local). A service is
 * registered on every non-loopback IPv4 address, mirroring how WLED devices
 * are discovered in {@link WledDiscoveryRunner}.
 */
@Component
public class MdnsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(MdnsRegistrar.class);
    private static final String SERVICE_NAME = "led-bloom";
    private static final String SERVICE_TYPE = "_http._tcp.local.";

    private final List<JmDNS> instances = new ArrayList<>();

    @Value("${server.port:8901}")
    private int serverPort;

    /**
     * Registers once the embedded web server has started so we advertise the
     * actual bound port.
     */
    @EventListener
    public void register(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort() > 0
                ? event.getWebServer().getPort()
                : serverPort;

        for (InetAddress address : getMachineIps()) {
            try {
                log.info("Registering mDNS service '{}' on {}:{}", SERVICE_NAME, address.getHostAddress(), port);
                JmDNS jmdns = JmDNS.create(address, SERVICE_NAME);
                ServiceInfo serviceInfo = ServiceInfo.create(SERVICE_TYPE, SERVICE_NAME, port, "path=/");
                jmdns.registerService(serviceInfo);
                instances.add(jmdns);
            } catch (IOException e) {
                // Don't fail startup if one interface can't be registered.
                log.error("Could not register mDNS service on {}", address.getHostAddress(), e);
            }
        }
    }

    @PreDestroy
    public void unregister() {
        for (JmDNS jmdns : instances) {
            try {
                jmdns.unregisterAllServices();
                jmdns.close();
            } catch (IOException e) {
                log.error("Error closing mDNS instance", e);
            }
        }
        instances.clear();
    }

    private List<InetAddress> getMachineIps() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> ifaceAddresses = iface.getInetAddresses();
                while (ifaceAddresses.hasMoreElements()) {
                    InetAddress addr = ifaceAddresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        addresses.add(addr);
                    }
                }
            }
        } catch (SocketException e) {
            log.error("Failed to enumerate network interfaces for mDNS registration", e);
        }
        return addresses;
    }
}
