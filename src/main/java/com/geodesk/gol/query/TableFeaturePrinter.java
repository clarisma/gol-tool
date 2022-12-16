/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.geodesk.gol.query;

import com.clarisma.common.text.Table;
import com.geodesk.feature.Feature;

import java.io.PrintStream;

public class TableFeaturePrinter extends AbstractFeaturePrinter
{
    private Table table;
    private Column currentColumn;
    private final StringBuilder buf = new StringBuilder();

    public TableFeaturePrinter(PrintStream out)
    {
        super(out);
        table = new Table();
    }

    @Override public boolean setOption(String name, String value)
    {
        switch (name)
        {
        case "max-width":
            checkValue(value);
            table.maxWidth((int) Math.round(Options.parseDouble(value)));
            return true;
        }
        return super.setOption(name, value);
    }


    @Override public void printHeader()
    {
        table.column();
        for (Column col : columns) table.column();
        table.add("ID");
        for (Column col : columns) table.add(col.key);
        table.divider("=");
    }

    @Override public void print(Feature feature)
    {
        setCoordinateProperties(feature);
        extractProperties(feature.tags());
        int currentRow = table.newRow();
        table.add(feature.toString());
        for (int col = 0; col < columns.size(); col++)
        {
            Column c = columns.get(col);
            if (c.properties != null)
            {
                int row = currentRow;
                for (Property tag : c.properties)
                {
                    String value = tag.key + "=" + tag.value;
                    table.cell(row++, col + 1, value);
                }
            }
            else
            {
                String value = c.value;
                if (value == null || value.isEmpty()) value = "-";
                table.cell(currentRow, col + 1, value);
            }
        }
        resetProperties();
    }

    @Override public void printFooter()
    {
        out.print(table);
    }
}