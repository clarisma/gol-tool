package com.geodesk.gol;

import com.clarisma.common.validation.ValidationContext;
import com.clarisma.common.validation.Validator_old;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectIntHashMap;

public class IndexedKeysValidator implements Validator_old<ObjectIntHashMap<String>>
{
    private final static int MAX_KEY_CATEGORIES = 30;

    @Override public ObjectIntHashMap<String> validate(String s, ValidationContext ctx)
    {
        ObjectIntHashMap<String> map = new ObjectIntHashMap<>();
        String[] categories = s.split("\\s");
        for(int category=0; category<categories.length; category++)
        {
            String[] keys = categories[category].split("/");
            if(category >= MAX_KEY_CATEGORIES)
            {
                for(String key: keys)
                {
                    ctx.addWarning(String.format(
                        "Key %s won't be indexed (maximum %d key categories)",
                        key, MAX_KEY_CATEGORIES));
                }
            }

            for(String key: keys)
            {
                if(map.containsKey(key))
                {
                    ctx.addWarning(String.format("Key %s listed more than once", key));
                }
                else
                {
                    map.put(key, category + 1);
                }
            }
        }
        return map;
    }
}
