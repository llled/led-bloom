package org.llled.wledmux.discovery;

import java.time.Instant;

public class WledDevice {

    public static final int DEFAULT_PORT = 4048;

    private final String ip;
    private final int port;
    private String name;
    private int ledCount;
    private int width;
    private int height;
    private Instant lastSeen;
    private boolean pinned;

    public WledDevice(String ip, String name, int ledCount, int width, int height) {
        this(ip, DEFAULT_PORT, name, ledCount, width, height);
    }

    public WledDevice(String ip, int port, String name, int ledCount, int width, int height) {
        this.ip = ip;
        this.port = port;
        this.name = name;
        this.ledCount = ledCount;
        this.width = width;
        this.height = height;
        this.lastSeen = Instant.now();
    }

    /** Unique identity of a device: ip:port. */
    public String key() {
        return key(ip, port);
    }

    public static String key(String ip, int port) {
        return ip + ":" + port;
    }

    public String getIp() { return ip; }
    public int getPort() { return port; }
    public String getName() { return name; }
    public int getLedCount() { return ledCount; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Instant getLastSeen() { return lastSeen; }
    public boolean isPinned() { return pinned; }

    public void setName(String name) { this.name = name; }
    public void setLedCount(int ledCount) { this.ledCount = ledCount; }
    public void setWidth(int width) { this.width = width; }
    public void setHeight(int height) { this.height = height; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public void touch() { this.lastSeen = Instant.now(); }

    @Override
    public String toString() {
        return String.format("WledDevice{ip='%s', port=%d, name='%s', leds=%d, %dx%d, pinned=%b}",
                ip, port, name, ledCount, width, height, pinned);
    }
}
