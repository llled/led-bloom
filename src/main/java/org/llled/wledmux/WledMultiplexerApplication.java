package org.llled.wledmux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WledMultiplexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WledMultiplexerApplication.class, args);
    }
}
