/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.info;

import com.clarisma.common.util.ProgressListener;
import com.geodesk.feature.FeatureType;
import com.geodesk.feature.Tags;
import com.clarisma.common.util.ProgressReporter;
import com.geodesk.io.osm.HeaderData;
import com.geodesk.io.osm.Members;
import com.geodesk.io.osm.Nodes;
import com.geodesk.io.osm.OsmPbfReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class OsmPbfInfoReader extends OsmPbfReader
{
    //
    // Global counters for general statistics.
    //
    private long totalBytesProcessed;
    private long totalNodeCount;
    private long totalTaggedNodeCount;
    private long totalWayCount;
    private long totalWayNodeCount;
    private long totalRelationCount;
    private long totalSuperRelationCount;
    private long totalEmptyRelationCount;
    private long totalMemberCount;
    private long totalTagCount;
    private long totalBlockCount;
    private long globalMaxBlockSize;
    private long globalMaxNodeId;
    private long globalMaxWayId;
    private long globalMaxRelationId;

    private ProgressListener progress;

    @Override protected WorkerThread createWorker()
    {
        return new InfoThread();
    }

    private class InfoThread extends WorkerThread
    {
        private long nodeCount;
        private long taggedNodeCount;
        private long wayCount;
        private long wayNodeCount;
        private long relationCount;
        private long superRelationCount;
        private long emptyRelationCount;
        private long memberCount;
        private long tagCount;
        private long maxNodeId;
        private long maxWayId;
        private long maxRelationId;
        private long blockCount;
        private long maxBlockSize;
        private boolean sorted = true;

        @Override protected void header(HeaderData hd)
        {
            synchronized (OsmPbfInfoReader.this)
            {
                reportHeader(System.out, hd);
            }
        }

        @Override protected void node(long id, int lon, int lat, Tags tags)
        {
            nodeCount++;
            if(id <= maxNodeId)
            {
                sorted = false;
            }
            else
            {
                maxNodeId = id;
            }
            int nodeTagCount = tags.size();
            if(nodeTagCount > 0) taggedNodeCount++;
            tagCount += nodeTagCount;
        }

        @Override protected void way(long id, Tags tags, Nodes nodes)
        {
            wayCount++;
            wayNodeCount += nodes.size();
            tagCount += tags.size();
            if(id <= maxWayId)
            {
                sorted = false;
            }
            else
            {
                maxWayId = id;
            }
        }

        @Override protected void relation(long id, Tags tags, Members members)
        {
            relationCount++;
            tagCount += tags.size();
            if(id <= maxRelationId)
            {
                sorted = false;
            }
            else
            {
                maxRelationId = id;
            }
            boolean isSuperRelation = false;
            int thisMemberCount = 0;
            while(members.next())
            {
                if(members.type() == FeatureType.RELATION)
                {
                    if (members.id() != id) isSuperRelation = true;
                    // We ignore self-references, since they are
                    // removed by subsequent steps
                }
                thisMemberCount++;
            }
            memberCount += thisMemberCount;
            if(thisMemberCount == 0) emptyRelationCount++;
            if(isSuperRelation) superRelationCount++;
        }

        @Override protected void endBlock(Block block)
        {
            maxBlockSize = Math.max(maxBlockSize, block.length);
            blockCount++;
            progress.progress(block.length);
        }

        @Override protected void postProcess()
        {
            synchronized (OsmPbfInfoReader.this)
            {
                totalNodeCount += nodeCount;
                totalTaggedNodeCount += taggedNodeCount;
                totalWayCount += wayCount;
                totalWayNodeCount += wayNodeCount;
                totalRelationCount += relationCount;
                totalSuperRelationCount += superRelationCount;
                totalEmptyRelationCount += emptyRelationCount;
                totalMemberCount += memberCount;
                totalTagCount += tagCount;
                totalBlockCount += blockCount;
                if (maxNodeId > globalMaxNodeId) globalMaxNodeId = maxNodeId;
                if (maxWayId > globalMaxWayId) globalMaxWayId = maxWayId;
                if (maxRelationId > globalMaxRelationId) globalMaxRelationId = maxRelationId;
                if (maxBlockSize > globalMaxBlockSize) globalMaxBlockSize = maxBlockSize;
            }
        }
    }

    public void reportHeader(PrintStream out, HeaderData header)
    {
        out.format("source:          %s\n", header.source);
        out.format("writing-program: %s\n", header.writingProgram);
        out.format("required-features:\n");
        for(String f : header.requiredFeatures)
        {
            out.format("  - %s\n", f);
        }
        out.format("optional-features:\n");
        for(String f : header.optionalFeatures)
        {
            out.format("  - %s\n", f);
        }

        LocalDateTime timestamp = LocalDateTime.ofEpochSecond(
            header.replicationTimestamp, 0, ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        out.format("replication:\n");
        out.format("  timestamp:     %s\n", timestamp.format(formatter));
        out.format("  sequence:      %d\n", header.replicationSequence);
        out.format("  url:           %s\n", header.replicationUrl);
    }

    public void report(PrintStream out)
    {
        out.format("blocks:          %,d\n", totalBlockCount);
        out.format("largest-block:   %,d\n", globalMaxBlockSize);
        out.format("nodes:           %,d\n", totalNodeCount);
        out.format("tagged-nodes:    %,d\n", totalTaggedNodeCount);
        out.format("ways:            %,d\n", totalWayCount);
        out.format("way-nodes:       %,d\n", totalWayNodeCount);
        out.format("relations:       %,d\n", totalRelationCount);
        out.format("super-relations: %,d\n", totalSuperRelationCount);
        out.format("empty-relations: %,d\n", totalEmptyRelationCount);
        out.format("members:         %,d\n", totalMemberCount);
        out.format("tags:            %,d\n", totalTagCount);
        out.format("max-node-id:     %d\n", globalMaxNodeId);
        out.format("max-way-id:      %d\n", globalMaxWayId);
        out.format("max-relation-id: %d\n", globalMaxRelationId);
        // out.flush();
    }

    public void analyze(Path file) throws IOException
    {
        long fileSize = Files.size(file);
        progress = new ProgressReporter(fileSize, "bytes",
            "Analyzing", "Analyzed");
        read(file.toFile());
        report(System.out);
        progress.finished();
    }

    public static void main(String[] args) throws Exception
    {
        OsmPbfInfoReader analyzer = new OsmPbfInfoReader();
        analyzer.analyze(Path.of("c:\\geodesk\\mapdata\\de-2022-11-28.osm.pbf"));
        // analyzer.analyze(Path.of("c:\\geodesk\\mapdata\\planet-2022-10-17.osm.pbf"));
        // analyzer.analyze(Path.of("c:\\geodesk\\mapdata\\planet.osm.pbf"));
    }
}
