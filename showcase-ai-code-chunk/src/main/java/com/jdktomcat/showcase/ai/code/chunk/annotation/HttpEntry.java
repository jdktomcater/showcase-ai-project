package com.jdktomcat.showcase.ai.code.chunk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an HTTP entry point for impact chain analysis.
 * Applied to methods that handle HTTP requests.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HttpEntry {
    /**
     * HTTP method(s) handled by this entry point.
     */
    String[] methods() default {"GET", "POST", "PUT", "DELETE", "PATCH"};

    /**
     * URL path pattern(s) handled by this entry point.
     */
    String[] paths() default {};

    /**
     * Logical name for this entry point (optional, defaults to method signature).
     */
    String name() default "";
}
