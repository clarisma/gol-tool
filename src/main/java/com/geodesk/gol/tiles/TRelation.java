/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.feature.match.TypeBits;

import java.io.IOException;

import static com.geodesk.feature.store.FeatureFlags.FEATURE_TYPE_BITS;

public class TRelation extends TFeature2D<TRelation.Body>
{
    private TFeature[] members;
    private int[] roles;
    private int[] tips;

    public TRelation(long id)
    {
        super(id);
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
            if(isRelationMember())
            {
                relations = reader.readRelationTableIndirect(pBody - 4);
                bodySize = 4;
                setAnchor(4);
            }
            bodySize += reader.readTable(pBody, 3, 1,
                TypeBits.ALL & TypeBits.RELATION_MEMBER, true);
            members = reader.getCurrentFeatures();
            tips = reader.getCurrentTips();
            roles = reader.getCurrentRoles();
            reader.resetTables();
            setSize(bodySize);
            setAlignment(1);    // 2-byte algined (1 << 1)
        }

        @Override public void writeTo(StructOutputStream out) throws IOException
        {
            // TODO
        }
    }
}

