package org.llled.wledmux.receiver;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.llled.ddp.DdpException;
import org.llled.ddp.DdpFrameListener;
import org.llled.ddp.DdpReceiver;
import org.llled.wledmux.config.MultiplexerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DdpFrameReceiver {

    private static final Logger log = LoggerFactory.getLogger(DdpFrameReceiver.class);

    private final MultiplexerConfig config;
    private final DdpFrameListener frameListener;
    private DdpReceiver receiver;

    public DdpFrameReceiver(MultiplexerConfig config, DdpFrameListener frameListener) {
        this.config = config;
        this.frameListener = frameListener;
    }

    @PostConstruct
    public void start() throws DdpException {
        int port = config.getDdpListenPort();
        int frameBufferSize = config.getFrameWidth() * config.getFrameHeight() * 3;
        receiver = new DdpReceiver(port, frameListener, frameBufferSize);
        receiver.start();
        log.info("DDP receiver started on port {}, expecting {}x{} frames",
                port, config.getFrameWidth(), config.getFrameHeight());
    }

    @PreDestroy
    public void stop() {
        if (receiver != null) {
            receiver.close();
            log.info("DDP receiver stopped");
        }
    }

    public boolean isRunning() {
        return receiver != null && receiver.isRunning();
    }
}
