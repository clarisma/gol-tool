package com.clarisma.common.cli;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An Attribute that marks a field or method as a parameter of a CLI command.
 *
 * Its string value has the following format:
 *
 * <position> = [ ? ] <param-name> [ : <description> ]
 *
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Parameter
{
    String value() default "";
}
