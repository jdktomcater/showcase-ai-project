package com.jdktomcat.showcase.ai.aletheia;

import com.jdktomcat.showcase.ai.aletheia.config.AletheiaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AletheiaProperties.class)
public class AletheiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AletheiaApplication.class, args);
    }
}
