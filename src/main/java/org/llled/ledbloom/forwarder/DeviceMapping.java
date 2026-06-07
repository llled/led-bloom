package org.llled.ledbloom.forwarder;

public class DeviceMapping {

    private final String ip;
    private final int deviceWidth;
    private final int deviceHeight;
    private final int translateX;
    private final int translateY;
    private final int[] sourceOffsets; // pre-computed byte offsets into the source frame

    public DeviceMapping(String ip, int deviceWidth, int deviceHeight,
                         int translateX, int translateY, int frameWidth) {
        this.ip = ip;
        this.deviceWidth = deviceWidth;
        this.deviceHeight = deviceHeight;
        this.translateX = translateX;
        this.translateY = translateY;

        // Pre-compute source byte offsets for each device pixel
        this.sourceOffsets = new int[deviceWidth * deviceHeight];
        for (int row = 0; row < deviceHeight; row++) {
            for (int col = 0; col < deviceWidth; col++) {
                int srcX = translateX + col;
                int srcY = translateY + row;
                int srcPixelIndex = srcY * frameWidth + srcX;
                sourceOffsets[row * deviceWidth + col] = srcPixelIndex * 3; // 3 bytes per RGB pixel
            }
        }
    }

    public String getIp() { return ip; }
    public int getDeviceWidth() { return deviceWidth; }
    public int getDeviceHeight() { return deviceHeight; }
    public int getTranslateX() { return translateX; }
    public int getTranslateY() { return translateY; }
    public int getPixelCount() { return deviceWidth * deviceHeight; }
    public int[] getSourceOffsets() { return sourceOffsets; }

    public void extractPixels(byte[] sourceFrame, int sourceLength, byte[] destBuffer) {
        for (int i = 0; i < sourceOffsets.length; i++) {
            int srcOffset = sourceOffsets[i];
            int destOffset = i * 3;
            if (srcOffset + 2 < sourceLength) {
                destBuffer[destOffset] = sourceFrame[srcOffset];
                destBuffer[destOffset + 1] = sourceFrame[srcOffset + 1];
                destBuffer[destOffset + 2] = sourceFrame[srcOffset + 2];
            }
        }
    }
}
