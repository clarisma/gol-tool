/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.compiler;

import com.clarisma.common.soar.SString;
import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.feature.FeatureId;
import com.geodesk.core.Tile;
import com.geodesk.core.TileQuad;
import com.geodesk.feature.store.FeatureConstants;
import com.geodesk.core.Box;
import com.geodesk.geom.Bounds;
import com.geodesk.gol.build.TileCatalog;
import org.eclipse.collections.api.set.primitive.MutableIntSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;

public class SRelation extends SFeature
{
    private Bounds bounds;
    private SRelationBody body;
    private Member[] members;

    public static class Member
    {
        public SFeature member;
        // TODO: If we kept a list of local strings, we could refer to it by index
        //  Then we could just have one int field to describe role
        public String role;
        public int roleCode;
        public SString roleString;
        public int tip;
    }

    public SRelation(long id)
    {
        super(id);
        flags |= 2 << FEATURE_TYPE_BITS;
        setSize(32);
        setAnchor(16);
    }

    public void setMembers(Member[] members)
    {
        int localRolesflag = 0;
        boolean isArea = false;
        this.members = members;
        for(Member m: members)
        {
            // We don't add this relation to members yet, because we don't
            // know for sure which ones are local (and we want to avoid
            // needlessly building relatables for foreign features)

            if("outer".equals(m.role)) isArea = true;

            // TODO: more robust check? Maybe do it in build?

            if(m.roleString != null) localRolesflag |= LOCAL_ROLES_FLAG;
        }
        flags = (flags & ~LOCAL_ROLES_FLAG) | localRolesflag;
        setFlag(AREA_FLAG, isArea);
        // TODO: move this to buildFeature?
    }

    /**
     * Adds this relation to the relation tables of its members.
     *
     * Note: Perform this step before the features are built, because
     * build() normalizes and deduplicates the relation tables, which
     * means they cannot be changed at that point.
     */
    public void addToMembers()
    {
        if(members == null) return;
        for(Member m: members)
        {
            SFeature mf = m.member;
            if(mf.isForeign() && !mf.isMissing()) continue;
                // No need to create reltables for foreign members
                // But if a node is considered foreign and missing, this
                // means it may be an untagged node
                // During a later step, it may be upgraded to a local feature
                // TODO: see if there is a better way to resolve this
                //  maybe upgrade in this step instead of SNode.build()?
            mf.addParentRelation(this);
        }
    }

    public Bounds bounds()
    {
        return bounds;
    }

    public void setBounds(Bounds bounds)
    {
        this.bounds = bounds;
    }

    @Override public SFeatureBody body()
    {
        return body;
    }

    private boolean calculateBounds(FeatureTile ft)
    {
        /*
        if(id == 1212338)
        {
            Compiler.log.debug("Tile {}: Calculating bounds of relation/{}",
                Tile.toString(ft.tile()), id);
        }
         */

        setFlag(RELATION_IS_MEASURING_FLAG);
        Box bbox = new Box();
        int quad = 0;
        boolean resolved = true;
        for(Member m: members)
        {
            SFeature mf = m.member;
            if(!mf.isBuilt())
            {
                if (!mf.isMeasuring()) mf.build(ft);
            }
            if(!mf.isBuilt()) resolved = false;

            if(!mf.isMissing())
            {
                Bounds memberBounds = mf.bounds();
                // Remember, bounds of SNode are never null because
                //  SNode derives from Bounds and is never null
                // TODO: better test may be to see if member has been built?

                assert memberBounds != null:
                    String.format("Missing memberBounds for %s, can't calculate bounds for %s",
                        mf, this);

                if(memberBounds.minX() == 0 && memberBounds.minY() == 0)
                {
                    /*
                    Compiler.log.warn("Suspicious bbox of {} (foreign={}, missing={}, quad={}",
                        mf, mf.isForeign(), mf.isMissing(), TileQuad.toString(mf.tileQuad()));
                     */
                }
                bbox.expandToInclude(memberBounds);
                int memberQuad = m.member.tileQuad();
                if(memberQuad != -1)
                {
                    // TODO: use different code for purgatory items
                    quad = TileQuad.addQuad(quad, memberQuad);
                }
            }
        }
        assert quad != -1;

        if(quad != 0)
        {
            bounds = bbox;
            tileQuad = TileQuad.zoomedOut(quad, Tile.zoom(ft.tile()));
            assert tileQuad != -1 : String.format("%s could not zoom %s to level of %s",
                this, TileQuad.toString(quad), Tile.toString(ft.tile()));
        }
        clearFlag(RELATION_IS_MEASURING_FLAG);

        /*
        if(id == 1212338)
        {
            Compiler.log.debug("  Assigned relation/{} to quad {}, resolved = {}",
                id, TileQuad.toString(tileQuad), resolved);
        }
         */

        return resolved;
    }

    @Override public void build(FeatureTile ft)
    {
        /*
        if(id == 3283229)
        {
            Compiler.log.debug("Building relation/{}", id);
        }
         */
        // TODO: empty relations
        boolean resolvable = true;
        if(members != null)
        {
            resolvable = calculateBounds(ft);
        }
        if(resolvable) resolve(ft);
    }

    public void resolve(FeatureTile ft)
    {
        // TODO: empty relations
        if(isLocal())
        {
            if (!TileQuad.containsTile(tileQuad, ft.tile()))
            {
                /*
                Compiler.log.debug("Because {} does not contain {}, we mark {} as foreign.",
                    TileQuad.toString(tileQuad), Tile.toString(ft.tile()), this);
                 */
                markAsForeign();
            }
            else
            {
                calculateMultitileFlags(ft);
            }
            normalize(ft);
            if(members == null) members = new Member[0];    // TODO
            assignTips(ft.tileCatalog());
            body = new SRelationBody();
        }
        setFlag(BUILT_FLAG);
    }

    public void calculateUsage()
    {
        if(isForeign()) return;
        int memberCount = members.length;
        addUsage(memberCount, memberCount * UsageScores.RELATION_MEMBER_BONUS_SCORE);
        if((flags & LOCAL_ROLES_FLAG) != 0)
        {
            for(int i=0; i<memberCount; i++)
            {
                SString role = members[i].roleString;
                if(role != null) role.addUsage(1,UsageScores.ROLE_STRING_RATIO);
            }
        }
        super.calculateUsage();
    }

    /*
    @Override public Iterator<SFeature> iterator()
    {
        if(members == null) return Collections.emptyIterator();
        return new Iterator<>()
        {
            int pos;

            @Override public boolean hasNext()
            {
                return pos < members.length;
            }

            @Override public SFeature next()
            {
                return members[pos++].member;
            }
        };
    }
     */

    /*
    private int commonTip(int q1, int q2)
    {
        int tq1 = f1.tileQuad();

    }

     */

    // TODO: repurpose this method to set empty roles to "outer" if relations is an area
    private void assignTips(TileCatalog tileCatalog)
    {
        int memberCount = members.length;
        if(memberCount == 0) return;

        SFeature prev = members[0].member;
        for(int i=0; i<memberCount; i++)
        {
            Member m = members[i];
            SFeature mf = m.member;
            if(mf.isLocal()) continue;
            int mq = mf.tileQuad();
            /*
            if(TileQuad.tileCount(mq) == 1)
            {
                m.tip = tileCatalog.tipOfTile(TileQuad.northWestTile(mq));
            }
             */
            /*
            if(mf.id() == 5038215 && mf.type() == FeatureType.WAY)
            {
                Compiler.log.debug("relation/{}: Quad of {} {} is {}",
                    id, mf.isForeign() ? "foreign" : "local",
                    mf, TileQuad.toString(mf.tileQuad()));
            }
             */
            m.tip = tileCatalog.tipOfTile(TileQuad.blackTile(mq));
            // TODO: better algo: Ideally, choose a tip common with prev, next,
            //  or first member feature
        }
    }

    public class SRelationBody extends SFeatureBody
    {
        public SRelationBody ()
        {
            int memberCount = members.length;
            int size = Math.max(memberCount * 4, 4);
            int prevRoleCode = 0;
            int prevTip = -1;
            SString prevRoleString = null;
            for(int i=0; i<memberCount; i++)
            {
                Member m = members[i];
                if(m.roleCode != prevRoleCode || m.roleString != prevRoleString)
                // TODO: fix, equals!
                {
                    size += m.roleString != null ? 4 : 2;
                    prevRoleCode = m.roleCode;
                    prevRoleString = m.roleString;
                }
                if(m.member.isForeign())
                {
                    if (prevTip != m.tip)
                    {
                        if (prevTip == -1) prevTip = FeatureConstants.START_TIP;
                        int tipDelta = m.tip - prevTip;
                        size += FeatureTile.isWideTipDelta(tipDelta) ? 4 : 2;
                        prevTip = m.tip;
                    }
                }
            }
            if(isRelationMember())
            {
                size += 4;
                setAlignment(2);
                setAnchor(4);
            }
            else
            {
                setAlignment(1);
            }
            setSize(size);
        }

        // TODO: consolidate these flags
        private static final int MF_LAST = 1;
        private static final int MF_FOREIGN = 2;
        private static final int MF_DIFFERENT_ROLE = 4;
        private static final int MF_DIFFERENT_TILE = 8;

        public void writeTo(StructOutputStream out) throws IOException
        {
            if(isRelationMember()) out.writePointer(relations());
            int memberCount = members.length;
            if(memberCount == 0)
            {
                // empty relation
                out.writeInt(0);
                return;
            }
            int prevRoleCode = 0;
            SString prevRoleString = null;
            int prevTip = -1;
            for(int i=0; i<memberCount; i++)
            {
                Member m = members[i];
                SFeature mf = m.member;
                int flags = i==memberCount-1 ? MF_LAST : 0;
                if(m.roleCode != prevRoleCode || m.roleString != prevRoleString)
                {
                    flags |= MF_DIFFERENT_ROLE;
                }
                if(mf.isLocal())
                {
                    out.writeTaggedPointer(mf, 3, flags);
                }
                else
                {
                    if (m.tip != prevTip)
                    {
                        if (prevTip == -1) prevTip = FeatureConstants.START_TIP;
                        flags |= MF_DIFFERENT_TILE;
                    }
                    long typedId = FeatureId.of(mf.type(), mf.id());
                    out.writeForeignPointer(m.tip, typedId, 2, flags | MF_FOREIGN);
                        // TODO: pointer occupies top 28 bits, but we only
                        //  shift by 2 because it is 4-byte aligned
                        // TODO: unify handling of pointers, this is too confusing
                    if ((flags & MF_DIFFERENT_TILE) != 0)
                    {
                        int tipDelta = m.tip - prevTip;
                        if (FeatureTile.isWideTipDelta(tipDelta))
                        {
                            out.writeInt((tipDelta << 1) | 1);
                        }
                        else
                        {
                            out.writeShort(tipDelta << 1);
                        }
                        prevTip = m.tip;
                    }
                }
                if((flags & MF_DIFFERENT_ROLE) != 0)
                {
                    if(m.roleString != null)
                    {
                        out.writeTaggedPointer(m.roleString, 1, 0);
                    }
                    else
                    {
                        out.writeShort((m.roleCode << 1) | 1);
                    }
                    prevRoleCode = m.roleCode;
                    prevRoleString = m.roleString;
                }
            }
        }

        public int countForeignMembers()
        {
            int count = 0;
            for(Member m: members)
            {
                if(m.member.isForeign()) count++;
            }
            return count;
        }

        public String toString()
        {
            return String.format("BODY of relation/%d with %d member%s", id,
                members.length, members.length != 1 ? "s" : "");
        }

        @Override public Iterator<Struct> iterator()
        {
            return (flags & LOCAL_ROLES_FLAG) != 0 ? new RoleStringIterator() :
                Collections.emptyIterator();
        }
    }

    private class RoleStringIterator implements Iterator<Struct>
    {
        private int n = -1;

        public RoleStringIterator()
        {
            fetchNext();
        }

        private void fetchNext()
        {
            for(;;)
            {
                n++;
                if(n >= members.length) break;
                if(members[n].roleString != null) break;
            }
        }

        @Override public boolean hasNext()
        {
            return n < members.length;
        }

        @Override public Struct next()
        {
            int current = n;
            fetchNext();
            return members[current].roleString;
        }
    }

    // TODO: defer creation of tile set?
    @Override public void export(FeatureTile ft)
    {
        if(isForeign()) return;
        super.export(ft);

        /*
        if(id == 3283229)
        {
            Compiler.log.debug("Exporting relation/{}", id);
        }
         */
        // TODO: empty relations
        MutableIntSet tiles = new IntHashSet();
        for(Member m: members)
        {
            SFeature mf = m.member;
            if(mf.isMissing())
            {
                // Compiler.log.debug("Exporting relation/{} to purgatory", id);
                tiles.add(TileCatalog.PURGATORY_TILE);
            }
            else
            {
                int memberQuad = mf.tileQuad();
                if (TileQuad.zoom(memberQuad) != TileQuad.zoom(tileQuad))
                {
                    TileQuad.forEach(memberQuad, tile -> tiles.add(tile));
                }
            }
        }
        tiles.forEach(tile -> ft.exportFeature(tile, this));
    }

    @Override public void buildInvalid(FeatureTile ft)
    {
        super.buildInvalid(ft);
        if(isForeign()) return;
        bounds = MISSING_BOUNDS;
        if(members == null) members = new Member[0];    // TODO
        // no need to assign TIPs, 0 (purgatory) by default
        body = new SRelationBody();
        // setFlag(BUILT_FLAG);   // already done by SFeature.buildInvalid()
    }

}
