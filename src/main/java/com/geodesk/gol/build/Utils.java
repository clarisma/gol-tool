/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.build;

import com.clarisma.common.io.FileUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class Utils
{
    public static final String SETTINGS_FILE = "settings.bin";

    public static Path pathWithExtension(String filename, String ext)
    {
        if(FileUtils.getExtension(filename).isEmpty()) filename += ext;
        return Path.of(filename);
    }

    public static Path golPath(String filename)
    {
        return pathWithExtension(filename, ".gol");
    }

    public static Map<String,Object> readSettings(Path path) throws Exception
    {
        FileInputStream fin = new FileInputStream(path.toFile());
        ObjectInputStream in = new ObjectInputStream(fin);
        Map<String,Object> settings = (Map<String,Object>)in.readObject();
        in.close();
        return settings;
    }

    public static void writeSettings(Path path, Map<String,Object> settings) throws Exception
    {
        FileOutputStream fout = new FileOutputStream(path.toFile());
        ObjectOutputStream out = new ObjectOutputStream(fout);
        out.writeObject(settings);
        out.close();
    }

    public static void delete(Path folder, String... files) throws IOException
    {
        for(String file: files) Files.deleteIfExists(folder.resolve(file));
    }
}
