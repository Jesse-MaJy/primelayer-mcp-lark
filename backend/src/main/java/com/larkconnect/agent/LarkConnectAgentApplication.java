package com.larkconnect.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LarkConnectAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(LarkConnectAgentApplication.class, args);
    }
}
