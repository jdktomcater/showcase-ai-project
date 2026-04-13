package com.jdktomcat.showcase.ai.code.chunk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an RPC entry point for impact chain analysis.
 * Applied to methods that handle RPC calls (Dubbo, gRPC, etc.).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RpcEntry {
    /**
     * RPC protocol type (e.g., "DUBBO", "GRPC", "THRIFT").
     */
    String protocol() default "DUBBO";

    /**
     * Service interface name.
     */
    String service() default "";

    /**
     * Logical name for this entry point (optional, defaults to method signature).
     */
    String name() default "";
}
