package com.jdktomcat.showcase.ai.mcp.apm.skywalking.client;

public class SkyWalkingClientException extends RuntimeException {

    public SkyWalkingClientException(String message) {
        super(message);
    }

    public SkyWalkingClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
