package org.llled.wledmux.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class WledScannerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testParseJsonInfoWithMatrix() throws IOException {
        String json = Files.readString(Paths.get("src/test/resources/wled/jsonInfoWidthHeight.json"));
        JsonNode root = mapper.readTree(json);

        String name = root.get("name").asText();
        assertEquals("Candle3", name);

        JsonNode leds = root.get("leds");
        assertEquals(143, leds.get("count").asInt());

        JsonNode matrix = leds.get("matrix");
        assertNotNull(matrix);
        assertEquals(11, matrix.get("w").asInt());
        assertEquals(13, matrix.get("h").asInt());
    }

    @Test
    void testParseJsonInfoWithoutMatrix() throws IOException {
        String json = Files.readString(Paths.get("src/test/resources/wled/infoJsonNoWidthHeight.json"));
        JsonNode root = mapper.readTree(json);

        String name = root.get("name").asText();
        assertEquals("Bathroom mirror", name);

        JsonNode leds = root.get("leds");
        assertEquals(324, leds.get("count").asInt());
        assertNull(leds.get("matrix"));

        // Verify fallback calculation
        double sqrt = Math.sqrt(324);
        int expected = (int) Math.ceil(sqrt);
        assertEquals(18, expected);
    }

    @Test
    void testParseLedMap() throws IOException {
        String json = Files.readString(Paths.get("src/test/resources/wled/ledmap1.json"));
        JsonNode root = mapper.readTree(json);

        JsonNode map = root.get("map");
        assertNotNull(map);
        assertTrue(map.isArray());
        assertEquals(12, map.size());

        assertEquals(4, root.get("width").asInt());
        assertEquals(3, root.get("height").asInt());
    }
}
