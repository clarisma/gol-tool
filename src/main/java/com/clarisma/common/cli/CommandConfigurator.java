package com.clarisma.common.cli;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

public class CommandConfigurator
{
    private final Command command;
    private final List<Setter> params = new ArrayList<>();
    private final Map<String, Setter> options = new HashMap<>();

    public CommandConfigurator(Command command)
    {
        this.command = command;
        inspect(command.getClass());
    }

    static class Setter implements Comparable<Setter>
    {
        Field field;
        Method method;
        String paramName;
        String description;
        int position;
        boolean optional;
        boolean used;
        boolean usedMultiple;

        @Override public int compareTo(Setter other)
        {
            assert position != other.position : "Parameters have same position";
            return (position < other.position) ? -1 : 1;
        }
    }

    private static String processAnnotationDescription(Setter setter, String string)
    {
        int n = string.indexOf(':');
        if (n >= 0)
        {
            setter.description = string.substring(n + 1);
            string = string.substring(0, n);
        }
        n = string.indexOf('=');
        if (n >= 0)
        {
            setter.paramName = string.substring(n + 1);
            string = string.substring(0, n);
        }
        return string;
    }

    private void processAnnotation(Annotation a, Field field, Method method)
    {
        if (a instanceof Option)
        {
            Setter s = new Setter();
            s.field = field;
            s.method = method;
            String name = processAnnotationDescription(s, ((Option) a).value());
            if (name.isEmpty())
            {
                name = field != null ? field.getName() : method.getName();
                options.put(name, s);
                return;
            }
            for (; ; )
            {
                int n = name.indexOf(',');
                if (n < 0)
                {
                    options.put(name, s);
                    return;
                }
                options.put(name.substring(0, n), s);
                name = name.substring(n + 1);
            }
        }
        if (a instanceof Parameter)
        {
            Setter s = new Setter();
            s.field = field;
            s.method = method;
            String name = processAnnotationDescription(s, ((Parameter) a).value());
            if (!name.isEmpty())
            {
                s.position = Integer.parseInt(name);
            }
            params.add(s);
            return;
        }
    }

    private void inspect(Class clazz)
    {
        do
        {
            for (Field f : clazz.getDeclaredFields())
            {
                Annotation[] annotations = f.getAnnotations();
                for (Annotation a : annotations)
                {
                    processAnnotation(a, f, null);
                }
            }
            for (Method m : clazz.getDeclaredMethods())
            {
                Annotation[] annotations = m.getAnnotations();
                for (Annotation a : annotations)
                {
                    processAnnotation(a, null, m);
                }
            }
            clazz = clazz.getSuperclass();
        }
        while (clazz != Object.class);
        Collections.sort(params);
    }

    /*
    public void run(Class clazz)
    {
        inspect(clazz);
        try
        {
            Command cmd = (Command)clazz.getDeclaredConstructor().newInstance();
            cmd.perform();
        }
        catch (InstantiationException | IllegalAccessException |
            InvocationTargetException | NoSuchMethodException ex)
        {
            throw new RuntimeException(
                String.format("Cannot instantiate command class %s: %s",
                    clazz.getName(), ex));
        }
        catch(Exception ex)
        {
            throw new RuntimeException(
                String.format("Command %s failed: %s",
                    clazz.getName(), ex));
        }
    }
     */

    protected Object convert(String s, Class<?> type)
    {
        return Converter.convert(s, type);
    }

    private void set(Setter setter, String value) throws IllegalAccessException, InvocationTargetException
    {
        Field f = setter.field;
        if (f != null)
        {
            Class type = f.getType();
            Object v;
            if (type == Boolean.TYPE)
            {
                // TODO: cannot have value
                v = true;
            }
            else
            {
                v = convert(value, type);
            }
            f.setAccessible(true);
            f.set(command, v);
            return;
        }
        Method m = setter.method;
        Class[] methodParams = m.getParameterTypes();
        if (methodParams.length == 0)
        {
            if(value != null)
            {
                throw new IllegalArgumentException("Cannot have a value");
            }
            m.invoke(command);
            return;
        }
        assert methodParams.length == 1;
        m.invoke(command, convert(value, methodParams[0]));
    }

    public void setOption(String name, String value) throws Exception
    {
        try
        {
            Setter setter = options.get(name);
            if (setter == null)
            {
                command.setOption(name, value);
                return;
            }
            set(setter, value);
            setter.used = true;
        }
        catch(InvocationTargetException ex)
        {
            Throwable cause = ex.getCause();
            throw new IllegalArgumentException(
                "Option " + name + ": " + cause.getMessage(), cause);
        }
    }

    private static Type collectionType(Class<?> type)
    {
        if(type.isArray()) return type.getComponentType();
        if(Collection.class.isAssignableFrom(type))
        {
            Type superType = type.getGenericSuperclass();
            return String.class;
        }
        return null;
    }

    private Object toParamValue(Setter setter, Class<?> type, List<String> args)
    {
        int pos = setter.position;
        if(type.isArray())
        {
            int len = args.size() - pos;
            Class<?> itemType = type.getComponentType();
            Object a = Array.newInstance(itemType, len);
            for(int i=0; i<len; i++)
            {
                Array.set(a,i,convert(args.get(pos++), itemType));
            }
            setter.usedMultiple = true;
            return a;
        }
        return convert(args.get(pos), type);
    }

    protected String formatParamName(Setter setter)
    {
        return String.format("<%s>", setter.paramName);
    }

    public void setParams(List<String> args) throws Exception
    {
        int paramPos = 0;
        int argPos = 0;
        final int paramCount = params.size();
        final int argCount = args.size();

        while (paramPos < paramCount && argPos < argCount)
        {
            Setter setter = params.get(paramPos);
            Field f = setter.field;
            try
            {
                if (f != null)
                {
                    f.setAccessible(true);
                    f.set(command, toParamValue(setter, f.getType(), args));
                }
                else
                {
                    Method m = setter.method;
                    Class[] methodParams = m.getParameterTypes();
                    assert methodParams.length == 1;
                    m.invoke(command, toParamValue(setter, methodParams[0], args));
                }
            }
            catch(Exception ex)
            {
                throw new IllegalArgumentException(String.format("%s: %s",
                    formatParamName(setter), ex.getMessage(), ex));
            }
            if(setter.usedMultiple)
            {
                paramPos = argPos = argCount;
            }
            else
            {
                argPos++;
                paramPos++;
            }
        }

        if(paramPos < argCount)
        {
            throw new IllegalArgumentException(
                "Extraneous argument(s): " + String.join(" ",
                    args.subList(argPos, argCount)));
        }
        if(argCount < paramCount)
        {
            // TODO: optionals
            throw new IllegalArgumentException(formatParamName(
                params.get(paramPos)) + ": Missing argument");
        }
    }
}
