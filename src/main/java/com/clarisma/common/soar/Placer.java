package com.clarisma.common.soar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Queue;

public class Placer
{
    private static final Logger log = LogManager.getLogger();

    private Archive archive;
    private Queue<Struct>[] queues;
    private int maxAlignment;
    private int totalQueued;
    private int maxQueued;
    private byte[] alignmentTable;

    private static final byte[] T_3 = { 2,1,0, 0,1,2, 1,0,2, 0,2,1 };

    public Placer(Archive archive, int maxAlignment, int maxQueued)
    {
        assert maxAlignment == 2;   // TODO

        this.archive = archive;
        this.maxAlignment = (byte)maxAlignment;
        this.maxQueued = maxQueued;
        queues = new Queue[maxAlignment+1];
        for(int i=0; i<queues.length; i++) queues[i] = new ArrayDeque<>();
        alignmentTable = T_3;

        /*
        new byte[maxAlignmentLen * (maxAlignment+1)];
        for(int i=0; i<maxAlignment; i++)
        {
            alignmentTable[i] = (byte)Integer.numberOfTrailingZeros(i | maxAlignment);
        }
         */

    }

    private int tableOfs()
    {
        int pos = archive.size();
        int modulo = pos & ((1 << maxAlignment) - 1);
        return modulo * (maxAlignment+1);
    }

    private void force(int tableOfs)
    {
        for(;;)
        {
            /*
            log.debug("  Checking queue {}... (tableOfs = {})",
                alignmentTable[tableOfs], tableOfs);

             */
            Queue<Struct> candidateQueue = queues[alignmentTable[tableOfs]];
            if(!candidateQueue.isEmpty())
            {
                archive.place(candidateQueue.remove());
                break;
            }
            tableOfs++;
        }
    }

    private boolean shouldPlaceAnyway(Struct s)
    {
        if (totalQueued < maxQueued / 2) return false;
        int pos = archive.size();
        return pos == s.alignedLocation(pos);
    }


    public void place(Struct s)
    {
        assert s.location() <= 0: "Struct has already been placed";
        int alignment = s.alignment();
        int tableOfs = tableOfs();
        if(alignmentTable[tableOfs] == alignment)
        {
            // If the struct can be placed at its natural alignment
            // (a location that requires no padding, where a struct with
            // higher alignment can't be placed without padding), place it now
            archive.place(s);
        }
        else if(shouldPlaceAnyway(s))
        {
            // If the queue is already half-full, place the struct anyway,
            // as long as it won't create a gap
            archive.place(s);
        }
        else
        {
           // Otherwise, put it in the queue for its alignment

            s.setLocation(0x8000_0000);  // TODO
            queues[alignment].add(s);
            if(totalQueued < maxQueued)
            {
                totalQueued++;
                return;
            }

            // log.debug("{} structs queued", totalQueued);

            // If the queue is full, place the first element that requires the
            // least amount of padding (higher-aligned structs first)

            force(tableOfs+1);
        }

        // If we placed any structs, try if any queued structs can be placed
        // at their natural position

        for(;;)
        {
            // log.debug("{} structs queued", totalQueued);

            tableOfs = tableOfs();
            Queue<Struct> candidateQueue = queues[alignmentTable[tableOfs]];
            if (candidateQueue.isEmpty())
            {
                if (totalQueued < maxQueued / 2) break;
                if (queues[0].isEmpty()) break;
                candidateQueue = queues[0];
            }
            archive.place(candidateQueue.remove());
            totalQueued--;
        }
    }

    public void flush()
    {
        while(totalQueued > 0)
        {
            // log.debug("{} structs queued", totalQueued);
            force(tableOfs());
            totalQueued--;
        }
    }
}
