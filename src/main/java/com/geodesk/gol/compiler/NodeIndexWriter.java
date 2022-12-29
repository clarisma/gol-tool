/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

import com.clarisma.common.io.FileUtils;
import com.clarisma.common.pbf.PbfOutputStream;
import com.clarisma.common.util.Bytes;
import com.geodesk.feature.store.Tip;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.DeflaterOutputStream;

public class NodeIndexWriter
{
    protected PbfOutputStream buf;
    protected PbfOutputStream nodesBuf;
    protected Path path;
    protected long prevWayId;

    public NodeIndexWriter(Path rootPath, int tip)
    {
        buf = new PbfOutputStream();
        path = Tip.path(rootPath, tip, ".nix");
    }

    public void writeWay(long id, long[] nodeIds)
    {
        buf.writeSignedVarint(id - prevWayId);
        long prevNodeId = 0;
        for(long nodeId: nodeIds)
        {
            nodesBuf.writeSignedVarint(nodeId - prevNodeId);
            prevNodeId = nodeId;
        }
        buf.writeString(nodesBuf);
        nodesBuf.reset();
        prevWayId = id;
    }

    public void close() throws IOException
    {
        String fileName = FileUtils.replaceExtension(path.toString(), ".tmp");
        FileOutputStream out = new FileOutputStream(fileName);
        byte[] header = new byte[4];
        int size = buf.size();
        Bytes.putInt(header, 0, size);
        DeflaterOutputStream zipOut = new DeflaterOutputStream(out);
        zipOut.write(buf.buffer(), 0, size);
        zipOut.close();
        Files.move(Path.of(fileName), path, StandardCopyOption.REPLACE_EXISTING);
        buf = null;
        nodesBuf = null;
    }
}
