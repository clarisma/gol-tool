/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update;

import com.clarisma.common.index.IntIndex;
import com.clarisma.common.util.Log;
import com.geodesk.gol.TaskEngine;
import com.geodesk.gol.build.BuildContext;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public class ChangeReader2 extends TaskEngine<ChangeReader2.Task>
{
    private BuildContext context;

    protected ChangeReader2(BuildContext ctx)
    {
        super(new Task(), 1, true);
        this.context = ctx;
    }

    @Override protected TaskEngine<Task>.WorkerThread createWorker() throws Exception
    {
        return new Worker();
    }

    public void read(String file, boolean zipped) throws IOException
    {
        long start = System.currentTimeMillis();
        try (FileInputStream fin = new FileInputStream(file))
        {
            InputStream in = fin;
            if (zipped) in = new GZIPInputStream(fin);

            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser parser = factory.newSAXParser();
            // reader.read(in);
            parser.parse(in, new Handler());
            in.close();
        }
        catch(SAXException | ParserConfigurationException ex)
        {
            throw new IOException("%s: Invalid file (%s)".formatted(file, ex.getMessage()));
        }

        Log.debug("Read %s in %,d ms", file, System.currentTimeMillis() - start);
    }

    protected class Handler extends DefaultHandler
    {

    }

    protected static class Task
    {

    }

    protected class Worker extends WorkerThread
    {
        private final IntIndex nodeIndex;
        private final IntIndex wayIndex;
        private final IntIndex relationIndex;

        Worker() throws IOException
        {
            nodeIndex = context.getNodeIndex();
            wayIndex = context.getWayIndex();
            relationIndex = context.getRelationIndex();
        }

        @Override protected void process(Task task) throws Exception
        {

        }
    }

}
