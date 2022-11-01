/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.cli;

import java.io.IOException;

public class JvmLauncher
{
    public void launch()
    {
        ProcessBuilder pb = new ProcessBuilder();
    }

    public static void main(String[] args)
    {
        try
        {
            long start = System.currentTimeMillis();
            System.out.println("Started main class.");
            System.out.println("Other class: " + Other.class.getName());
            /*
            System.err.println(System.getProperty("java.home"));
            System.err.println(System.getProperty("sun.java.command"));
            System.err.println(System.getProperty("java.class.path"));
            */
            String separator = System.getProperty("file.separator");
            String classPath = System.getProperty("java.class.path");
            String javaPath = System.getProperty("java.home")
                + separator + "bin" + separator + "java";
            System.out.println("Calling: " + javaPath);
            ProcessBuilder pb =
                new ProcessBuilder(javaPath, "-cp",
                    classPath,
                    Other.class.getName());
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor();
            System.out.println("Done.");
            System.out.format("Elapsed time: %d ms", System.currentTimeMillis() - start);
        }
        catch(Exception ex)
        {
            System.err.println(ex);
        }
    }

    static class Other
    {
        public static void main(String[] args)
        {
            System.err.println("Launched other class.");
            long start = System.currentTimeMillis();
            try
            {
                Thread.sleep(2000);
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
            System.err.println("Other class: Ran for " +
                (System.currentTimeMillis() - start) + " ms");
            System.err.println("Other class: Done.");
        }
    }
}
