/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.geodesk.feature.Feature;

import java.io.PrintStream;
import java.io.PrintWriter;

// TODO: rename to Formatter
public interface FeaturePrinter
{
    default void printHeader() {};
    void print(Feature feature);
    default void printFooter() {};
}
