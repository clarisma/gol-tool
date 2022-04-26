package com.clarisma.common.cli;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface Command
{
    int perform() throws Exception;
    default void setOption(String name, String value)
    {
        throw new IllegalArgumentException("Unknown option: " + name);
    }
}
