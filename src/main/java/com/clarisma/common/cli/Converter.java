package com.clarisma.common.cli;

import java.lang.reflect.InvocationTargetException;

public class Converter
{
    public static Object convert(String s, Class type)
    {
        if(type==String.class) return s;
        try
        {
            if (type == Integer.class || type == Integer.TYPE)
            {
                return Integer.parseInt(s);
            }
            if (type == Long.class || type == Long.TYPE)
            {
                return Long.parseLong(s);
            }
            if (type == Double.class || type == Double.TYPE)
            {
                return Double.parseDouble(s);
            }
        }
        catch(NumberFormatException ex)
        {
            throw new IllegalArgumentException("Must be a number, not \"" + s + "\"");
        }
        if(type.isEnum())
        {
            return toEnum(s, type);
        }
        throw new RuntimeException(String.format(
            "Unable to convert \"%s\" to %s", s, type));
    }

    public static Object toEnum(String s, Class<Enum> type)
    {
        try
        {
            return Enum.valueOf(type, s.toUpperCase());
        }
        catch(IllegalArgumentException ex)
        {
            throw new IllegalArgumentException(String.format(
                "\"%s\" is not a valid %s", s, type.getSimpleName()), ex);
        }
        /*
        try
        {
            return enumClass.getMethod("valueOf", String.class)
                .invoke(null, s.toUpperCase());
        }
        catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ex)
        {
            throw new IllegalArgumentException(String.format(
                "\"%s\" is not a valid %s", s, enumClass.getSimpleName()));
        }
         */
    }
}
