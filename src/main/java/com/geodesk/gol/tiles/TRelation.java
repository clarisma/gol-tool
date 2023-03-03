/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructWriter;
import com.geodesk.feature.FeatureId;
import com.geodesk.feature.match.TypeBits;
import com.geodesk.feature.store.FeatureConstants;
import com.geodesk.feature.store.Tip;

import java.util.List;

import static com.geodesk.feature.store.FeatureFlags.FEATURE_TYPE_BITS;

public class TRelation extends TFeature2D<TRelation.Body>
{
    private TTile tile;
    private TFeature[] members;
    private int[] roles;
    private int[] tips;         // not needed; store in foreign TFeature

    public TRelation(TTile tile, long id)
    {
        super(id);
        this.tile = tile;
        flags |= 2 << FEATURE_TYPE_BITS;
    }

    @Override public void readBody(TileReader reader)
    {
        body = new Body(reader, readBodyPointer(reader));
    }

    class Body extends Struct
    {
        public Body(TileReader reader, int pBody)
        {
            // TODO: empty relation

            int bodySize = 0;
            if (isRelationMember())
            {
                relations = reader.readRelationTableIndirect(pBody - 4);
                bodySize = 4;
                setAnchor(4);
            }
            bodySize += reader.readFeatureTable(pBody, 3, 1,
                TypeBits.ALL & TypeBits.RELATION_MEMBER, true) - pBody;
            members = reader.getCurrentFeatures();
            tips = reader.getCurrentTips();
            roles = reader.getCurrentRoles();
            reader.resetTables();
            setSize(bodySize);
            setAlignment(1);    // 2-byte algined (1 << 1)
        }

        @Override public void write(StructWriter out)
        {
            if(isRelationMember()) out.writePointer(relations);

            int prevRole = 0;
            int prevTip = Integer.MIN_VALUE;
            int truePrevTip = FeatureConstants.START_TIP;

            // Remember: first foreign ref must always indicate tile change even
            // if its tip is START_TIP

            int lastItem = members.length - 1;
            for (int i = 0; i <= lastItem; i++)
            {
                TFeature member = members[i];
                int tip = tips[i];
                int role = roles[i];
                int flags = (i == lastItem) ? 1 : 0;   // last-item flag
                if(role != prevRole) flags |= TileReader.DIFFERENT_ROLE_FLAG;
                if (tip == TileReader.LOCAL_TILE)
                {
                    assert !member.isForeign();
                    out.writeTaggedPointer(member, 3, flags);
                }
                else
                {
                    assert member.isForeign();
                    flags |= TileReader.FOREIGN_FLAG;
                    if (tip != prevTip) flags |= TileReader.DIFFERENT_TILE_FLAG;
                    long typedId = FeatureId.of(member.typeCode(), member.id());
                    out.writeForeignPointer(tip, typedId, 2, flags);
                    // TODO: pointer occupies top 28 bits, but we only
                    //  shift by 2 because it is 4-byte aligned
                    // TODO: unify handling of pointers, this is too confusing
                    if (tip != prevTip)
                    {
                        int tipDelta = tip - truePrevTip;
                        if (Tip.isWideTipDelta(tipDelta))
                        {
                            out.writeInt((tipDelta << 1) | 1);
                        }
                        else
                        {
                            out.writeShort((short) (tipDelta << 1));
                        }
                        prevTip = tip;
                        truePrevTip = tip;
                    }
                }
                if(role != prevRole)
                {
                    if((role & 1) == 0)
                    {
                        // global string (flag is reversed)
                        out.writeShort((short)(role | 1));
                    }
                    else
                    {
                        // local string
                        out.writeTaggedPointer(tile.localStringStruct(
                            role >>> 1), 1, 0);
                    }
                    prevRole = role;
                }
            }
        }
    }

    public void gatherStrings(List<? super SString> strings)
    {
        for(int i=0;i < roles.length; i++)
        {
            int role = roles[i];
            if((role & 1) != 0) strings.add(tile.localStringStruct(role >>> 1));
        }
    }
}

