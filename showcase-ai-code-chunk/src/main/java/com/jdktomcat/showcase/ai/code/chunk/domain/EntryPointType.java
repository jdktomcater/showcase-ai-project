package com.jdktomcat.showcase.ai.code.chunk.domain;

/**
 * Entry point types for multi-layer data flow analysis.
 */
public enum EntryPointType {
    /**
     * HTTP interface entry point (REST controllers, etc.)
     */
    HTTP,

    /**
     * RPC interface entry point (Dubbo, gRPC, etc.)
     */
    RPC,

    /**
     * Message Queue consumer entry point (RabbitMQ, Kafka, etc.)
     */
    MQ,

    /**
     * Scheduled task entry point
     */
    SCHEDULED,

    /**
     * Event-driven entry point (Application events)
     */
    EVENT
}
