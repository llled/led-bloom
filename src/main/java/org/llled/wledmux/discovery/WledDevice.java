package org.llled.wledmux.discovery;

import java.time.Instant;

public class WledDevice {

    private final String ip;
    private String name;
    private int ledCount;
    private int width;
    private int height;
    private Instant lastSeen;

    public WledDevice(String ip, String name, int ledCount, int width, int height) {
        this.ip = ip;
        this.name = name;
        this.ledCount = ledCount;
        this.width = width;
        this.height = height;
        this.lastSeen = Instant.now();
    }

    public String getIp() { return ip; }
    public String getName() { return name; }
    public int getLedCount() { return ledCount; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Instant getLastSeen() { return lastSeen; }

    public void setName(String name) { this.name = name; }
    public void setLedCount(int ledCount) { this.ledCount = ledCount; }
    public void setWidth(int width) { this.width = width; }
    public void setHeight(int height) { this.height = height; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }

    public void touch() { this.lastSeen = Instant.now(); }

    @Override
    public String toString() {
        return String.format("WledDevice{ip='%s', name='%s', leds=%d, %dx%d}", ip, name, ledCount, width, height);
    }
}
