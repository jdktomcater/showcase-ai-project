package com.jdktomcat.showcase.ai.code.chunk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an MQ consumer entry point for impact chain analysis.
 * Applied to methods that consume messages from message queues.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MqEntry {
    /**
     * MQ broker type (e.g., "RABBITMQ", "KAFKA", "ROCKETMQ").
     */
    String broker() default "RABBITMQ";

    /**
     * Queue or topic name(s) this method consumes from.
     */
    String[] destinations() default {};

    /**
     * Logical name for this entry point (optional, defaults to method signature).
     */
    String name() default "";
}
