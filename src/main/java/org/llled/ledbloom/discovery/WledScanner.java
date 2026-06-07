package org.llled.ledbloom.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.fluent.Request;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;

public class WledScanner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WledScanner.class);
    private static final int REACHABLE_TIMEOUT = 500;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String ip;
    private final WledDeviceRegistry registry;

    public WledScanner(String ip, WledDeviceRegistry registry) {
        this.ip = ip;
        this.registry = registry;
    }

    @Override
    public void run() {
        try {
            if (!InetAddress.getByName(ip).isReachable(REACHABLE_TIMEOUT)) {
                log.debug("IP {} not reachable", ip);
                return;
            }
        } catch (IOException e) {
            log.debug("IP {} reachability check failed", ip);
            return;
        }

        String name = ip;
        int ledCount = 0;
        int width = 0;
        int height = 0;

        try {
            String infoString = getApiContents("http://" + ip + "/json/info");
            JsonNode infoRoot = mapper.readTree(infoString);

            JsonNode nameNode = infoRoot.get("name");
            if (nameNode != null) {
                name = nameNode.asText();
            }

            JsonNode leds = infoRoot.get("leds");
            if (leds != null) {
                JsonNode countNode = leds.get("count");
                if (countNode != null) {
                    ledCount = countNode.asInt();
                }

                JsonNode matrix = leds.get("matrix");
                if (matrix != null) {
                    JsonNode w = matrix.get("w");
                    JsonNode h = matrix.get("h");
                    if (w != null && h != null) {
                        width = w.asInt();
                        height = h.asInt();
                    }
                }
            }

            // Try ledmap.json for more detailed info
            try {
                String ledMapString = getApiContents("http://" + ip + "/ledmap.json");
                JsonNode ledMapRoot = mapper.readTree(ledMapString);

                JsonNode map = ledMapRoot.get("map");
                if (map != null && map.isArray()) {
                    ledCount = map.size();
                }

                JsonNode wNode = ledMapRoot.get("width");
                JsonNode hNode = ledMapRoot.get("height");
                if (wNode != null && hNode != null) {
                    width = wNode.asInt();
                    height = hNode.asInt();
                }
            } catch (Exception e) {
                // ledmap.json is optional
            }
        } catch (Exception e) {
            log.debug("Failed to query WLED API at {}: {}", ip, e.getMessage());
            return;
        }

        // Fallback: calculate square from LED count
        if (width == 0 && height == 0 && ledCount > 0) {
            double sqrt = Math.sqrt(ledCount);
            width = (int) Math.ceil(sqrt);
            height = (int) Math.ceil(sqrt);
        }

        if (ledCount > 0) {
            registry.register(ip, name, ledCount, width, height);
        }
    }

    private String getApiContents(String url) throws IOException {
        return Request.get(url)
                .connectTimeout(Timeout.ofMilliseconds(1000))
                .responseTimeout(Timeout.ofMilliseconds(2000))
                .execute()
                .returnContent()
                .asString();
    }
}
