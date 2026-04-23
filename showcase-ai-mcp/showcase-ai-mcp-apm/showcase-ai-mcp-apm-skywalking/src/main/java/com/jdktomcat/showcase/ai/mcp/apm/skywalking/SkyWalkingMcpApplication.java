package com.jdktomcat.showcase.ai.mcp.apm.skywalking;

import com.jdktomcat.showcase.ai.mcp.apm.skywalking.config.SkyWalkingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SkyWalkingProperties.class)
public class SkyWalkingMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkyWalkingMcpApplication.class, args);
    }
}
