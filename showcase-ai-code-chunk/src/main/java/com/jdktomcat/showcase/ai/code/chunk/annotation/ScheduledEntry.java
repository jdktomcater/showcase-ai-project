package com.jdktomcat.showcase.ai.code.chunk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a scheduled task entry point for impact chain analysis.
 * Applied to methods that are executed on a schedule.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ScheduledEntry {
    /**
     * Cron expression for the scheduled task.
     */
    String cron() default "";

    /**
     * Fixed delay in milliseconds.
     */
    long fixedDelay() default -1;

    /**
     * Fixed rate in milliseconds.
     */
    long fixedRate() default -1;

    /**
     * Initial delay in milliseconds.
     */
    long initialDelay() default -1;

    /**
     * Logical name for this entry point (optional, defaults to method signature).
     */
    String name() default "";
}
