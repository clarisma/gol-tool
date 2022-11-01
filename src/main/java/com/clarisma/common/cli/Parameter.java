/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

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
