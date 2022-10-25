package com.clarisma.common.cli;

import com.clarisma.common.text.Strings;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public abstract class Application
{
    protected Command defaultCommand()
    {
        return new DefaultCommand(this);
    }

    protected abstract String version();

    protected Command createCommand(String name)
    {
        Package p = getClass().getPackage();
        String commandClassName = p.getName() + '.' +
            Strings.uppercaseFirst(name) + "Command";
        Class commandClass = null;
        try
        {
            commandClass = Class.forName(commandClassName);
            return (Command)commandClass.getConstructor().newInstance();
        }
        catch (ClassNotFoundException | NoSuchMethodException ex)
        {
            throw new IllegalArgumentException("Unknown command: " + name);
        }
        catch (InvocationTargetException | InstantiationException | IllegalAccessException ex)
        {
            throw new RuntimeException(
                String.format("Unable to instantiate command class %s: %s",
                    commandClassName, ex.getMessage()));
        }
    }

    public int run(Command cmd, String[] args) throws Exception
    {
        CommandLineParser parser = new CommandLineParser();
        List<String> params = new ArrayList<>();
        parser.parse(args);

        try
        {

            CommandConfigurator configurator = null;
            while(parser.next())
            {
                if (configurator == null)
                {
                    if (cmd == null)
                    {
                        String key = parser.key();
                        if (key == null)
                        {
                            String commandName = parser.value();
                            cmd = createCommand(commandName);
                            continue;
                        }
                        else
                        {
                            cmd = defaultCommand();
                        }
                    }
                    configurator = new CommandConfigurator(cmd);
                }

                String key = parser.key();
                String value = parser.value();
                if (key != null)
                {
                    configurator.setOption(key, value);
                }
                else
                {
                    params.add(value);
                }
            }
            if(cmd==null) cmd = defaultCommand();
            if(configurator==null) configurator = new CommandConfigurator(cmd);
            configurator.setParams(params);
            return cmd.perform();
        }
        catch(Throwable ex)
        {
            if(cmd == null) cmd = new DefaultCommand(this);
            return cmd.error(ex);
        }
    }

}
