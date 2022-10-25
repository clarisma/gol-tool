package com.clarisma.common.cli;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 *
 * Format:
 *
 * <name> [ , <alt-name> ]* [ = <param-name> ] [ : <description> ]
 *
 * Examples:
 *
 * format,f=csv|xml|geojson|...: output format
 * limit,l=number: maximum number of features to return
 *
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Option
{
    String value() default "";
}
