package com.clarisma.common.validation;

import com.clarisma.common.math.MathUtils;

// TODO: power of two
public class NumberValidator implements Validator<Number>
{
    private double min = Double.MIN_VALUE;
    private double max = Double.MAX_VALUE;
    private double modulo;
    private boolean units;
    private boolean powerOfTwo;

    public NumberValidator()
    {
    }

    public NumberValidator(double min, double max)
    {
        this.min = min;
        this.max = max;
    }

    public NumberValidator min(double min)
    {
        this.min = min;
        return this;
    }

    public NumberValidator max(double max)
    {
        this.max = max;
        return this;
    }

    public NumberValidator powerOfTwo()
    {
        this.powerOfTwo = true;
        return this;
    }

    public NumberValidator modulo(double modulo)
    {
        this.modulo = modulo;
        return this;
    }

    private static int skipWhitespace(String s, int ofs)
    {
        int len = s.length();
        for (; ofs < len; ofs++)
        {
            if (!Character.isWhitespace(s.charAt(ofs))) break;
        }
        return ofs;
    }

    public double getUnitMultiplier(String units)
    {
        switch (units)
        {
        case "k", "K":
            return 1024L;
        case "m", "M":
            return 1024L * 1024;
        case "g", "G":
            return 1024L * 1024 * 1024;
        case "t", "T":
            return 1024L * 1024 * 1024 * 1024;
        case "p", "P":
            return 1024L * 1024 * 1024 * 1024 * 1024;
        default:
            return 0;
        }
    }

    @Override public Number validate(String s)
    {
        int n = MathUtils.countNumberChars(s);
        if (n == 0)
        {
            throw new NumberFormatException(
                String.format("\"%s\" is not a valid number", s));
        }
        int len = s.length();
        n = skipWhitespace(s, n);
        double val = MathUtils.doubleFromString(s);
        if (n < len)
        {
            String units = s.substring(n);
            double unitMultiplier = getUnitMultiplier(units);
            if (unitMultiplier == 0)
            {
                throw new IllegalArgumentException(
                    String.format("\"%s\" is not a valid unit", s));
            }
            val *= unitMultiplier;
        }

        if (val < min)
        {
            throw new IllegalArgumentException(
                String.format("\"%s\": Must not be less than %f", s, min));
        }
        if (val > max)
        {
            throw new IllegalArgumentException(
                String.format("\"%s\": Must not be greater than %f", s, max));
        }
        if (modulo != 0 && val % modulo != 0)
        {
            if (modulo == 1)
            {
                throw new IllegalArgumentException(
                    String.format("\"%s\": Must be an integer", s));
            }
            throw new IllegalArgumentException(
                String.format("\"%s\": Must be evenly divisible by %f", s, modulo));
        }
        return val;
    }

    public static NumberValidator ofInteger(long min, long max)
    {
        return new NumberValidator(min, max).modulo(1);
    }

    /*
    public static void main(String[] args)
    {
        long peta = 1 * 1024L * 1024 * 1024 * 1024 * 1024;
        double petaDouble = (double)peta;
        long peta1024 = 128 * 1024L * 1024 * 1024 * 1024 * 1024;
        double peta1024Double = (double)peta1024;

        System.out.println(peta);
        System.out.println(petaDouble);
        System.out.println(peta1024);
        System.out.println(peta1024Double);
    }
     */

    public static void main(String[] args)
    {
        System.out.println((double)new NumberValidator().validate("0.5g") / (1024 * 1024));
    }
}
