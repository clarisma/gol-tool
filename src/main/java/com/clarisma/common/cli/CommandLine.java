/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.cli;

import com.clarisma.common.validation.ValidationContext;
import com.clarisma.common.validation.Validator_old;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandLine extends ValidationContext
{
    private String[] commands;
    private final Map<String, Option> options = new HashMap<>();
    private final Map<String, Object> optionValues = new HashMap<>();
    private final List<String> arguments = new ArrayList<>();
    private String command;

    static class Option<T>
    {
        final String fullName;
        final String shortName;
        final String paramName;
        final String desc;
        final Validator_old<T> validator;
        final int commandMask;

        public Option(String fullName, String shortName,
            String desc, Validator_old<T> validator, String paramName, int commandMask)
        {
            this.fullName = fullName;
            this.shortName = shortName;
            this.desc = desc;
            this.validator = validator;
            this.paramName = paramName;
            this.commandMask = commandMask;
        }
    }

    public CommandLine commands(String... commands)
    {
        this.commands = commands;
        return this;
    }

    public String command()
    {
        return command;
    }

    public List<String> arguments()
    {
        return arguments;
    }

    public Object optionValue(String name)
    {
        return optionValues.get(name);
    }

    public CommandLine option(String fullName, String shortName, String desc,
        Validator_old validator, String paramName, int commandMask)
    {
        Option opt = new Option(fullName, shortName, desc, validator, paramName, commandMask);
        options.put(fullName, opt);
        if(shortName != null) options.put(shortName, opt);
        return this;
    }

    public CommandLine option(String fullName, String shortName, String desc)
    {
        return option(fullName, shortName, desc, null, null, -1);
    }

    public CommandLine option(String fullName, String shortName, String desc, int commandMask)
    {
        return option(fullName, shortName, desc, null, null, commandMask);
    }

    private void parseOption(String arg, String nextArg)
    {
        int len = arg.length();
        if (len < 2)
        {
            addError("- must be followed by a flag/option identifier");
            return;
        }
        int nameStart = arg.charAt(1) == '-' ? 2 : 1;
        int nameEnd = arg.indexOf('=');
        String name;
        String param;
        if (nameEnd < 0)
        {
            name = arg.substring(nameStart);
            param = null;
        }
        else
        {
            name = arg.substring(nameStart, nameEnd);
            param = arg.substring(nameEnd + 1);
        }
        Option opt = options.get(name);
        if (opt == null)
        {
            addError("Invalid flag/option: " + name);
            return;
        }
        if (opt.validator == null)
        {
            if (param != null)
            {
                addError(String.format("Flag %s does not take a parameter", name));
            }
            optionValues.put(opt.fullName, Boolean.TRUE);
            return;
        }
        if (param == null) param = nextArg;
        if (param == null)
        {
            addError(String.format(
                "Option %s is missing required parameter <%s>", name, opt.paramName));
            return;
        }
        Object value = optionValues.get(opt.fullName);
        if (value != null)
        {
            addWarning(String.format("Option %s specified more than once", name));
        }
        value = opt.validator.validate(param, this);
        if (value == null) return;
        optionValues.put(opt.fullName, value);
    }

    public boolean parse(String[] args)
    {
        for (int i = 0; i < args.length; i++)
        {
            String arg = args[i];
            if (arg.charAt(0) == '-')
            {
                parseOption(arg, i < args.length-1 ? args[i+1] : null);
            }
            else
            {
                if (arguments.isEmpty() && commands != null)
                {
                    command = arg;
                }
                else
                {
                    arguments.add(arg);
                }
            }
        }
        return true;
    }
}
