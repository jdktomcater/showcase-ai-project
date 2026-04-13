package com.jdktomcat.showcase.ai.code.chunk.domain;

/**
 * Represents an entry point in the code impact chain.
 */
public record EntryPoint(
        String id,
        EntryPointType type,
        String className,
        String methodName,
        String methodSignature,
        String filePath,
        String module,
        Integer lineNumber,
        /**
         * Additional metadata based on entry point type:
         * - HTTP: path, methods
         * - RPC: protocol, service
         * - MQ: broker, destinations
         * - SCHEDULED: cron, fixedDelay, fixedRate
         * - EVENT: eventTypes
         */
        String metadata
) {
}
