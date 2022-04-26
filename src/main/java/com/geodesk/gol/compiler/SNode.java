package com.geodesk.gol.compiler;

import com.geodesk.core.TileQuad;
import com.geodesk.core.XY;
import com.geodesk.geom.Bounds;


// TODO: need to deal with references to missing nodes
//  (should only affect relation members)
//  Node with x=0, y=0 and no tags is invalid
//  (but should have been marked as purgatory node!)
//  Does the Validator export nodes from purgatory? Does it export anything?
public class SNode extends SFeature implements Bounds
{
    private int x;
    private int y;

    public SNode(long id)
    {
        super(id);
        setSize(20);
        setAnchor(8);
    }

    public void setXY(int x, int y)
    {
        this.x = x;
        this.y = y;
    }

    public Bounds bounds()
    {
        return this;
    }

    public int x()
    {
        return x;
    }

    public int y()
    {
        return y;
    }

    public int minX()
    {
        return x;
    }

    public int minY()
    {
        return y;
    }

    public int maxX()
    {
        return x;
    }

    public int maxY()
    {
        return y;
    }

    @Override public void addParentRelation(SRelation relation)
    {
        super.addParentRelation(relation);
        setSize(24);
        // TODO: if the node is untagged:
        //  If it is local, we need to turn it into a proper feature
    }

    @Override public void build(FeatureTile ft)
    {
        if(id == 945568320)
        {
            Compiler.log.debug("Building node/945568320");
        }

        if(isMissing())
        {
            if (x == 0 && y == 0)
            {
                long xy = ft.getCoordinates(id);
                if (xy == 0)
                {
                    // Compiler.log.warn("{} is missing coordinates", this);
                    // TODO: set tile to purgatory?
                }
                else
                {
                    // setTile(TileQuad.fromSingleTile(ft.tile()));
                    //  --> we do this below
                    x = XY.x(xy);
                    y = XY.y(xy);
                    markAsLocal();
                }
            }
        }
        if(isLocal()) setTileQuad(TileQuad.fromSingleTile(ft.tile()));
        normalize(ft);
        setSize(isRelationMember() ? 24 : 20);
        setFlag(BUILT_FLAG);
    }

    @Override public void buildInvalid(FeatureTile ft)
    {
        super.buildInvalid(ft);
        // if (isForeign()) return;     // TODO: can foreign nodes live in purgatory?
        assert !isForeign(): "Why is a foreign node in the Purgatory?";
        setSize(isRelationMember() ? 24 : 20);
    }

    public int getTip(FeatureTile ft)
    {
        return ft.tileCatalog().tipOfTile(tileQuad() & 0x0fff_ffff);
    }

    /*
    // TODO
    public String toString()
    {
        if(relations() == null) return super.toString();
        return String.format("node/%d (%d relations: %08X)", id,
            relations().relationCount(), relations().location());
    }
     */

    /*
    @Override public Iterator<SFeature> iterator()
    {
        return Collections.emptyIterator();
    }

     */
}
