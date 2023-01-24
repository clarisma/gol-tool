/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.tiles;

import com.clarisma.common.soar.Struct;
import com.clarisma.common.soar.StructWriter;
import com.geodesk.geom.*;
import org.eclipse.collections.api.map.primitive.IntIntMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TIndex extends Struct implements SpatialTreeFactory<TIndex.Branch>
{
    private final IndexSettings settings;
    private final IntIntMap keysToCategory;
    private final int maxIndexedKey;
    private final BoxBuilder boxBuilder = new BoxBuilder();
    private final Root[] roots;
    private int rootCount;
    private final Root mixedRoot;

    private final static int MAX_CATEGORIES = 30;

    public TIndex(IndexSettings settings)
    {
        this.settings = settings;
        this.keysToCategory = settings.keysToCategory;
        this.maxIndexedKey = settings.maxKeyIndexes;
        roots = new Root[MAX_CATEGORIES + 1];
        for(int i=0; i<roots.length; i++) roots[i] = new Root();
        mixedRoot = new Root();
        setAlignment(2);        // 4-byte aligned (1 << 2)
    }

    public void add(TFeature feature)
    {
        // Remember: categories start with 1

        int category = 0;
        int indexBits = 0;
        boolean multiCategory = false;
        TTagTable tags = feature.tags;
        int tagCount = tags.tagCount();
        for(int i=0; i<tagCount; i++)
        {
            long tag = tags.getTag(i);
            int k = (int)tag;
            k = (((k & 4) << 16) | k) >>> 3;
                // put the local-key flag into a higher bit position,
                // so tags with local keys always have a number > maxIndexedKey
            int keyCategory = keysToCategory.getIfAbsent(k, 0);
            if(keyCategory > 0)
            {
                if(category != 0) multiCategory = true;
                category = keyCategory;
                assert category >= 1 && category <= MAX_CATEGORIES;
                indexBits |= (1 << (category-1));
            }
            if(k >= maxIndexedKey) break;
        }
        Root root = multiCategory ? mixedRoot : roots[category];
        root.add(feature, indexBits);
    }

    private static TFeature unwrap(Bounds item)
    {
        if (item instanceof TFeature feature) return feature;
        return ((BoundedItem<TFeature>)item).get();
    }

    @Override public Branch createLeaf(List<? extends Bounds> childList, int start, int end)
    {
        TFeature first = unwrap(childList.get(start));
        TFeature prev = first;
        int size = first.size();
        boxBuilder.expandToInclude(first);
        for(int n = start+1; n < end; n++)
        {
            TFeature f = unwrap(childList.get(n));
            size += f.size();
            boxBuilder.expandToInclude(f);
            prev.setNext(f);
            prev = f;
        }
        prev.markAsLast();
        prev.setNext(null);
        Leaf leaf = new Leaf(boxBuilder, first, size);
        boxBuilder.reset();
        return leaf;
    }

    @Override public Branch createBranch(List<Branch> childList, int start, int end)
    {
        Branch[] children = new Branch[end-start];
        for(int i=0; i<children.length; i++)
        {
            Branch child = childList.get(start+i);
            children[i] = child;
            boxBuilder.expandToInclude(child);
        }
        Trunk trunk = new Trunk(boxBuilder, children);
        boxBuilder.reset();
        return trunk;
    }

    protected static class Branch extends Struct implements Bounds
    {
        private final int minX;
        private final int minY;
        private final int maxX;
        private final int maxY;

        Branch(Bounds bbox)
        {
            minX = bbox.minX();
            minY = bbox.minY();
            maxX = bbox.maxX();
            maxY = bbox.maxY();
            setAlignment(2);        // 4-byte aligned (1 << 2)
        }

        public int minX()
	{
		return minX;
	}

	    public int minY()
	{
		return minY;
	}

	    public int maxX()
	{
		return maxX;
	}

	    public int maxY()
	{
		return maxY;
	}
    }

    protected static class Leaf extends Branch
    {
        private final TFeature first;

        Leaf(Bounds bbox, TFeature first, int size)
        {
            super(bbox);
            this.first = first;
            setSize(size);
        }

        @Override public void write(StructWriter out)
        {
            Struct s = first;
            do
            {
                s.write(out);
                s = s.next();
            }
            while(s != null);
        }
    }

    protected static class Trunk extends Branch
    {
        private final Branch[] children;

        Trunk(Bounds bbox, Branch[] children)
        {
            super(bbox);
            this.children = children;
            setSize(20 * children.length);
        }

        @Override public void write(StructWriter out)
        {
            int lastChildIndex = children.length - 1;
            for(int i=0; i <= lastChildIndex; i++)
            {
                Branch child = children[i];
                int flags = (i == lastChildIndex) ? 1 : 0;
                if (child instanceof Leaf) flags |= 2;
                out.writePointer(child, flags);
                out.writeInt(child.minX());
                out.writeInt(child.minY());
                out.writeInt(child.maxX());
                out.writeInt(child.maxY());
            }
        }
    }

    private static class Root implements Comparable<Root>
    {
        int indexBits;
        Trunk trunk;
        TFeature first;
        int count;

        void add(TFeature feature, int indexBits)
        {
            if(first == null)
            {
                first = feature;
                feature.setNext(feature);
            }
            else
            {
                feature.setNext(first.next());
                first.setNext(feature);
            }
            count++;
            this.indexBits |= indexBits;
        }

        void add(Root other)
        {
            assert !isEmpty();
            assert !other.isEmpty();
            indexBits |= other.indexBits;
            count += other.count;
            Struct next = first.next();
            first.setNext(other.first.next());
            other.first.setNext(next);
            other.count = 0;
            other.first = null;
        }

        boolean isEmpty()
        {
            return count == 0;
        }

        ArrayList<TFeature> toFeatureList()
        {
            ArrayList<TFeature> list = new ArrayList<>(count);
            TFeature f = first;
            do
            {
                list.add(f);
                f = (TFeature)f.next();
            }
            while(f != first);
            return list;
        }

        @Override public int compareTo(Root other)
        {
            return Long.compare(other.count, count);
        }

        public Trunk trunk()
        {
            return trunk;
        }
    }

    public void build()
    {
        int maxRoots = settings.maxKeyIndexes;
        int minFeatures = settings.keyIndexMinFeatures;
        // Consolidate the roots

        Arrays.sort(roots);
        while(rootCount < maxRoots-1)
        {
            if(roots[rootCount].count < minFeatures) break;
            rootCount++;
        }
        if(roots[0].isEmpty())
        {
            roots[0] = mixedRoot;
        }
        else
        {
            roots[rootCount].add(mixedRoot);
            for (int i = rootCount + 1; i < roots.length; i++)
            {
                Root other = roots[i];
                if (other.isEmpty()) break;
                roots[rootCount].add(other);
            }
            rootCount++;

            // TODO: constrain feature bboxes to tile bbox
            SpatialTreeBuilder<Branch> builder =
                new OmtTreeBuilder<>(this, settings.rtreeBucketSize);
            for (int i = 0; i < rootCount; i++)
            {
                Root root = roots[i];
                Branch branch = builder.build(root.toFeatureList());
                if(branch instanceof Trunk trunk)
                {
                    root.trunk = trunk;
                }
                else
                {
                    root.trunk = new Trunk(branch, new Branch[]{branch});
                }
            }
        }
        setSize(rootCount * 8);
    }


	@Override public void write(StructWriter out)
	{
        for(int i=0; i<rootCount; i++)
        {
            Root root = roots[i];
            if(root.isEmpty()) break;
            out.writePointer(root.trunk, i == rootCount-1 ? 1 : 0);
            out.writeInt(root.indexBits);
        }
	}
}
