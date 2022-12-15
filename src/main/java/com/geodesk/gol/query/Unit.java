/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

public enum Unit
{
    M(1,"m",1, "m²"),
    KM(0.001d,"km", 0.000001d,"km²"),
    MI(0.0006213711922373339d,"mi", 3.861021585424458e-7d, "sq mi"),
    FT(3.28084d,"ft", 10.76391d, "sq ft"),
    YD(1.093613d,"yd", 1.19599d, "sq yd"),
    HA(1,"m", 0.0001d, "ha"),
    AC(1,"m", 2.471053814671653e-4d, "ac");


    // HA and AC default to meters if used as length unit

    public final double lengthFactor;
    public final double areaFactor;
    public final String lengthUnit;
    public final String areaUnit;

    Unit(double lengthFactor, String lengthUnit, double areaFactor, String areaUnit)
    {
        this.lengthFactor = lengthFactor;
        this.areaFactor = areaFactor;
        this.lengthUnit = lengthUnit;
        this.areaUnit = areaUnit;
    }
}
