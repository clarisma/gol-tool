/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.update_old;

import java.util.HashSet;
import java.util.Set;

public class ChangeSetAnalyzer extends ChangeSetReader
{
    private long createdNodes;
    private long modifiedNodes;
    private long deletedNodes;
    private long createdWays;
    private long modifiedWays;
    private long nodesInCreatedWays;
    private long nodesInModifiedWays;
    private long deletedWays;
    private long createdRelations;
    private long modifiedRelations;
    private long membersInCreatedRelations;
    private long membersInModifiedRelations;
    private long deletedRelations;
    private final Set<String> users= new HashSet<>();

    @Override protected void node(ChangeType change, long id, double lon, double lat, String[] tags)
    {
        switch(change)
        {
        case CREATE:
            createdNodes++;
            break;
        case MODIFY:
            modifiedNodes++;
            break;
        case DELETE:
            deletedNodes++;
            break;
        }
        users.add(userName);
    }

    @Override protected void way(ChangeType change, long id, String[] tags, long[] nodeIds)
    {
        switch(change)
        {
        case CREATE:
            createdWays++;
            nodesInCreatedWays += nodeIds.length;
            break;
        case MODIFY:
            modifiedWays++;
            nodesInModifiedWays += nodeIds.length;
            break;
        case DELETE:
            deletedWays++;
            break;
        }
        users.add(userName);
    }

    @Override protected void relation(ChangeType change, long id, String[] tags, long[] memberIds, String[] roles)
    {
        switch(change)
        {
        case CREATE:
            createdRelations++;
            membersInCreatedRelations += memberIds.length;
            break;
        case MODIFY:
            modifiedRelations++;
            membersInModifiedRelations += memberIds.length;
            break;
        case DELETE:
            deletedRelations++;
            break;
        }
        users.add(userName);
    }

    public void report()
    {
        System.out.format(
            "%d users\n" +
            "Nodes:\n" +
            "  %,d created\n" +
            "  %,d modified\n" +
            "  %,d deleted\n" +
            "Ways:\n" +
            "  %,d created (%,d nodes)\n" +
            "  %,d modified (%,d nodes)\n" +
            "  %,d deleted\n" +
            "Relations:\n" +
            "  %,d created (%,d members)\n" +
            "  %,d modified (%,d members)\n" +
            "  %,d deleted\n",
            users.size(),
            createdNodes, modifiedNodes, deletedNodes,
            createdWays, nodesInCreatedWays,
            modifiedWays, nodesInModifiedWays,
            deletedWays,
            createdRelations, membersInCreatedRelations,
            modifiedRelations, membersInModifiedRelations,
            deletedRelations);
    }
}
