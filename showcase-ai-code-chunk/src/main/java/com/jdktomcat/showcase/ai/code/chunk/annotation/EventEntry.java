package com.jdktomcat.showcase.ai.code.chunk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event-driven entry point for impact chain analysis.
 * Applied to methods that handle application events.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventEntry {
    /**
     * Event type(s) this method handles.
     */
    Class<?>[] eventTypes() default {};

    /**
     * Event name pattern(s) this method handles.
     */
    String[] eventNames() default {};

    /**
     * Logical name for this entry point (optional, defaults to method signature).
     */
    String name() default "";
}
