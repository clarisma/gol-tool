/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.util.Bytes;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.store.FeatureConstants;
import com.geodesk.feature.store.FeatureStore;
import com.geodesk.feature.store.StoredFeature;

import java.nio.ByteBuffer;

public class MemberReader
{
    private final FeatureStore store;
    private ByteBuffer buf;
    private int pos;
    private int entry;
    private int foreignTip;
    private String role;
    private ByteBuffer foreignBuf;
    private ByteBuffer memberBuf;
    private int pMember;
    private int memberTip;
    private int pForeignTile;


    // TODO: consolidate these flags?
    private static final int MF_LAST = 1;
    private static final int MF_FOREIGN = 2;
    private static final int MF_DIFFERENT_ROLE = 4;
    private static final int MF_DIFFERENT_TILE = 8;

    public static final int LOCAL_TIP = -1;

    public MemberReader(FeatureStore store)
    {
        this.store = store;
    }

    public void start(ByteBuffer buf, int pMembers)
    {
        this.buf = buf;
        this.pos = pMembers;
        foreignTip = FeatureConstants.START_TIP;
        entry = buf.getInt(pMembers);
        if(entry == 0) entry = MF_LAST;
    }

    public boolean next()
    {
        if((entry & MF_LAST) != 0) return false;
        entry = buf.getInt(pos);
        if((entry & MF_FOREIGN) != 0)
        {
            pos += 4;
            if((entry & MF_DIFFERENT_TILE) != 0)
            {
                int tipDelta = buf.getShort(pos);
                if ((tipDelta & 1) != 0)
                {
                    // wide TIP delta
                    tipDelta = buf.getInt(pos);
                    pos += 2;
                }
                tipDelta >>= 1;     // signed
                pos += 2;
                foreignTip += tipDelta;
                int page = store.fetchTile(foreignTip);
                foreignBuf = store.bufferOfPage(page);
                pForeignTile = store.offsetOfPage(page);
            }
            memberBuf = foreignBuf;
            pMember = pForeignTile + ((entry >>> 4) << 2);
            memberTip = foreignTip;
        }
        else
        {
            memberBuf = buf;
            pMember = (pos & 0xffff_fffc) + ((entry >> 3) << 2);
            memberTip = LOCAL_TIP;
            pos += 4;
        }
        if ((entry & MF_DIFFERENT_ROLE) != 0)
        {
            int rawRole = buf.getChar(pos);
            if ((rawRole & 1) != 0)
            {
                role = store.stringFromCode(rawRole >>> 1);
                pos += 2;
            }
            else
            {
                rawRole = buf.getInt(pos);
                role = Bytes.readString(buf, pos + (rawRole >> 1)); // signed
                pos += 4;
            }
        }
        return true;
    }

    public ByteBuffer memberBuf()
    {
        return memberBuf;
    }

    public int memberPointer()
    {
        return pMember;
    }

    public long typedMemberId()
    {
        return FeatureId.of(StoredFeature.typeCode(memberBuf, pMember),
            StoredFeature.id(memberBuf, pMember));
    }

    public String role()
    {
        return role;
    }

    public int tip()
    {
        return memberTip;
    }

    public int memberOffset()
    {
        assert memberTip != LOCAL_TIP;
        return pMember - pForeignTile;
    }
}
