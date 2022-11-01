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
