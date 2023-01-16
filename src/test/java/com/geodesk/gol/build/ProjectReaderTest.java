package com.geodesk.gol.build;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;

import static org.junit.Assert.*;

public class ProjectReaderTest
{
    @Test public void testProjectReader() throws Exception
    {
        InputStream in = getClass().getResourceAsStream("/test-config.fab");
        ProjectReader projectReader = new ProjectReader();
        projectReader.read(in);
        in.close();
    }
}