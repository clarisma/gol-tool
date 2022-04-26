package com.geodesk.gol.compiler;

import com.clarisma.common.soar.StructGroup;
import com.clarisma.common.soar.StructOutputStream;
import com.geodesk.core.Box;
import com.geodesk.feature.query.IndexBits;  // TODO: check
import com.geodesk.geom.*;
import com.geodesk.gol.build.KeyIndexSchema;
import com.geodesk.gol.build.Project;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.io.IOException;
import java.util.*;

public abstract class SIndexTree extends StructGroup<SFeature> implements Bounds
{
    private final String id;
    protected int minX, minY, maxX, maxY;
    // TODO: level?

    private SIndexTree(String id)
    {
        super(null);
        this.id = id;
        setAlignment(2);
    }

    private static SIndexTree fromNode(String id, RTree.Node node)
    {
        return node.isLeaf() ? new Leaf(id, node) : new Trunk(id, node);
    }

    public String id()
    {
        return id;
    }

    public abstract SIndexTree[] childBranches();

    protected abstract String type();

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

    public boolean isLeaf()
    {
        return firstChild != null;
    }

    protected void setBounds(Bounds b)
    {
        minX = b.minX();
        minY = b.minY();
        maxX = b.maxX();
        maxY = b.maxY();
    }

    @Override public String toString()
    {
        return String.format("%s %s with %d children", type(), id, countChildren());
    }


    public static class Leaf extends SIndexTree
    {
        private STagTable tags;
        private int tagsSize;   // = -1;  // TODO: not needed?

        private Leaf(String id, RTree.Node node)
        {
            super(id);
            setBounds(node);

            assert node.isLeaf();
            List<Bounds> childList = node.children();
            int childCount = childList.size();

            firstChild = ((BoundedItem<SFeature>) childList.get(0)).get();
            SFeature prevChild = firstChild;
            int size = firstChild.size();
            for (int i = 1; i < childCount; i++)
            {
                SFeature child = ((BoundedItem<SFeature>) childList.get(i)).get();
                size += child.size();
                prevChild.setNext(child);
                prevChild = child;
            }
            prevChild.markAsLastSpatialItem();
            setSize(size);
        }

        @Override public SIndexTree[] childBranches()
        {
            return null;
        }

        ;

        @Override protected String type()
        {
            return "LEAF";
        }

        ;

        public STagTable tags()
        {
            return tags;
        }

        public int tagsSize()
        {
            return tagsSize;
        }

        public void setTags(STagTable tags, int tagsSize)
        {
            this.tags = tags;
            this.tagsSize = tagsSize;
        }
    }

    private static class Trunk extends SIndexTree
    {
        private final SIndexTree[] childBranches;

        private Trunk(String id, RTree.Node node)
        {
            super(id);
            setBounds(node);

            assert !node.isLeaf();
            List<Bounds> childList = node.children();
            int childCount = childList.size();

            childBranches = new SIndexTree[childCount];
            for (int i = 0; i < childCount; i++)
            {
                String childId = id == null ? null : id + "-" + i;
                RTree.Node childNode = (RTree.Node) childList.get(i);
                childBranches[i] = fromNode(childId, childNode);
            }
            setSize(childCount * 20);
        }

        @Override public void writeTo(StructOutputStream out) throws IOException
        {
            for (int i = 0; i < childBranches.length; i++)
            {
                SIndexTree child = childBranches[i];
                int flags = (i == childBranches.length - 1) ? 1 : 0;
                if (child instanceof SIndexTree.Leaf) flags |= 2;
                out.writePointer(child, flags);
                out.writeInt(child.minX());
                out.writeInt(child.minY());
                out.writeInt(child.maxX());
                out.writeInt(child.maxY());
            }
        }

        @Override public SIndexTree[] childBranches()
        {
            return childBranches;
        }

        ;

        @Override public int countChildren()
        {
            return childBranches.length;
        }

        @Override protected String type()
        {
            return "TRUNK";
        }

        ;
    }

    private static class Root extends SIndexTree
    {
        private SIndexTree[] childBranches;
        private int[] keyBits;

        public Root(String id, SIndexTree[] childBranches, int[] keyBits)
        {
            super(id);
            this.childBranches = childBranches;
            this.keyBits = keyBits;
            setSize(childBranches.length * 8);
        }

        @Override public void writeTo(StructOutputStream out) throws IOException
        {
            for (int i = 0; i < childBranches.length; i++)
            {
                SIndexTree child = childBranches[i];
                int flags = (i == childBranches.length - 1) ? 1 : 0;
                if (child instanceof SIndexTree.Leaf) flags |= 2;
                out.writePointer(child, flags);
                out.writeInt(keyBits[i]);
            }
        }

        @Override public SIndexTree[] childBranches()
        {
            return childBranches;
        }

        ;

        @Override public int countChildren()
        {
            return childBranches.length;
        }

        @Override protected String type()
        {
            return "ROOT";
        }

        ;
    }

    private static class KeyGroup
    {
        int keyBits;
        String name;
        int featureCount;
        int groupNumber;
        KeyGroup assignedTo;
        List<SFeature> features;
    }

    // TODO: category starts with 1, but categories[] is 0-based
    private static String groupName(int keyBits, String[] categories)
    {
        return IndexBits.toString(keyBits, categories, "+", "uncat");
        /*
        if(keyBits == 0) return "uncat";
        StringBuilder buf = new StringBuilder();
        int cat = -2;
        while(keyBits != 0)
        {
            // topmost bit is unused (reserved)
            int zeroes = Integer.numberOfLeadingZeros(keyBits);
            cat += zeroes+1;
            if(buf.length() > 0) buf.append('+');
            buf.append(categories[cat]);
            keyBits <<= zeroes+1;
        }
        return buf.toString();
         */
    }

    /**
     * This method finds all possible key-bit combinations among a list of features (e.g. "highway", "highway+railway",
     * "building+amenity+tourism"). For each such combination, it creates a KeyGroup, and assigns the group number to
     * each feature. Group numbers start from 0.
     *
     * @param features a list of SFeature structs
     * @param schema   the KeyIndexSchema in use
     * @return a list of KeyGroups, ordered by group number
     */
    private static List<KeyGroup> groupFeatures(
        List<SFeature> features, KeyIndexSchema schema)
    {
        List<KeyGroup> groups = new ArrayList<>();
        MutableIntObjectMap<KeyGroup> keysToGroup = new IntObjectHashMap<>();

        for (SFeature f : features)
        {
            int keyBits = 0;
            STagTable tags = f.tags();
            for (Map.Entry<String, String> e : tags)
            {
                int category = schema.getCategory(e.getKey());
                if (category > 0) keyBits |= IndexBits.fromCategory(category);
            }
            KeyGroup group = keysToGroup.get(keyBits);
            if (group == null)
            {
                group = new KeyGroup();
                group.keyBits = keyBits;
                group.name = schema.getBucketName(keyBits, "+", "uncat");
                group.groupNumber = groups.size();
                keysToGroup.put(keyBits, group);
                groups.add(group);
            }
            group.featureCount++;
            f.setGroup(group.groupNumber);
        }
        return groups;
    }

    /**
     * Based on a list of KeyGroups, this method builds a list of index buckets. It will attempt to place each of the
     * most common key-bit groups into a separate bucket. If there are too many key-bit groups, or these groups aren't
     * used by a minimum number of features, it consolidates these less-frequent groups into a "mixed" bucket.
     *
     * @param groups  a list of KeyGroup objects
     * @param project the build settings (for "max-key-indexes" and "key-index-min-features")
     * @return a list of KeyGroup objects that represent the key buckets, sorted by key bits
     */
    private static List<KeyGroup> createKeyIndexBuckets(
        List<KeyGroup> groups, Project project)
    {
        List<KeyGroup> buckets = new ArrayList<>();
        if (groups.isEmpty()) return buckets;
        KeyGroup[] sortedGroups = groups.toArray(new KeyGroup[0]);
        Arrays.sort(sortedGroups, (a, b) -> Integer.compare(b.featureCount, a.featureCount));

        int maxBuckets = project.maxKeyIndexes();
        int minFeatures = project.keyIndexMinFeatures();

        int i = 0;
        do
        {
            KeyGroup group = sortedGroups[i];
            if (group.featureCount < minFeatures) break;
            buckets.add(group);
            group.assignedTo = group;
            group.features = new ArrayList<>(group.featureCount);
            i++;
        }
        while (i < sortedGroups.length && buckets.size() < maxBuckets - 1);

        KeyGroup mixed = null;
        if (i < sortedGroups.length)
        {
            mixed = new KeyGroup();
            mixed.name = "mixed";
            buckets.add(mixed);
        }

        while (i < sortedGroups.length)
        {
            KeyGroup group = sortedGroups[i];
            mixed.keyBits |= group.keyBits;
            mixed.featureCount += group.featureCount;
            group.assignedTo = mixed;
            i++;
        }

        if (mixed != null) mixed.features = new ArrayList<>(mixed.featureCount);
        Collections.sort(buckets, (a, b) -> Integer.compare(b.keyBits, a.keyBits));

        // Compiler.log.debug("{} buckets", buckets.size());

        return buckets;
    }

    private static SIndexTree buildSpatialIndex(
        String id, List<SFeature> features, Box tileBounds, int rtreeBucketSize)
    {
        if (features.isEmpty()) return null;
        List<Bounds> spatialItems = new ArrayList<>(features.size());
        for (SFeature f : features)
        {
            Bounds b = f.bounds();
            assert b != null : String.format("%s has null bounds", f);
            if (!tileBounds.contains(b))
            {
                b = tileBounds.intersection(b);
            }
            spatialItems.add(new BoundedItem<>(b, f));

            // TODO: could insert SFeature directly since it is a Bounds object
        }

        RTree tree = new OverlapMinimizingTree(spatialItems, rtreeBucketSize);

        /*
        long minX = tileBounds.minX();
        long maxX = tileBounds.maxX();
        RTree tree = new HilbertTileTree(spatialItems, Tile.zoomFromSize(maxX - minX + 1), rtreeBucketSize);
        */
        // TODO: ensure root SIB is not a leaf by creating a single-branch SIB
        return fromNode(id, tree.root());
    }

    public static SIndexTree build(String id, List<SFeature> features,
        Box tileBounds, Project project)
    {
        int rtreeBucketSize = project.rtreeBucketSize();
        KeyIndexSchema schema = project.keyIndexSchema();
        List<KeyGroup> groups = groupFeatures(features, schema);
        List<KeyGroup> buckets = createKeyIndexBuckets(groups, project);
        int bucketCount = buckets.size();
        if (bucketCount < 2)
        {
            return buildSpatialIndex(id, features, tileBounds, rtreeBucketSize);
        }

        for (SFeature f : features)
        {
            groups.get(f.group()).assignedTo.features.add(f);
        }

        SIndexTree[] childBranches = new SIndexTree[bucketCount];
        int[] keyBits = new int[bucketCount];
        for (int i = 0; i < bucketCount; i++)
        {
            KeyGroup bucket = buckets.get(i);
            int bits = bucket.keyBits;
            String childId = String.format("%s-%s", id, bucket.name);
            childBranches[i] = buildSpatialIndex(childId, bucket.features,
                tileBounds, rtreeBucketSize);
            keyBits[i] = bits;
        }
        return new Root(id, childBranches, keyBits);
    }

    public static SIndexTree buildFlat(String id, List<SFeature> features)
    {
        if (features.isEmpty()) return null;
        List<Bounds> spatialItems = new ArrayList<>(features.size());
        for (SFeature f : features)
        {
            Bounds b = f.bounds();
            spatialItems.add(new BoundedItem<>(b, f));
        }
        return new Leaf(id, new RTree.Node(spatialItems, true));
    }

    public static void writeIndexPointer(StructOutputStream out, SIndexTree index) throws IOException
    {
        int flags = 0;
        if (index instanceof Root)
        {
            flags = 1;
        }
        else if (index instanceof Leaf)
        {
            flags = 2;
        }
        out.writePointer(index, flags);
        // TODO: Is it better to create a single-item
        //  trunk SIB so queries won't need to check
        //  the trunk/leaf flag?
    }
}