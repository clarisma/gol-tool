<img src="https://docs.geodesk.com/img/github-header.png">

# Geographic Object Librarian (GOL)

Use the GOL command-line utility to:

- build and maintain Geographic Object Libraries (GeoDesk's compact database format for OpenStreetMap features)
 
- perform [GOQL](http://docs.geodesk.com/goql) queries and export the results in a variety of formats.

## Setup

The GOL Utility is a cross-platform Java application. You will need to install a 64-bit JVM, Version 16 or above.

Apart from a 64-bit CPU, hardware requirements are minimal. The exception is the `gol build` command, which has higher recommended system configurations for reasonable performance.

### Building from source

To build the latest version from source, you will first need to build and install the [GeoDesk Java library](http://www.github.com/clarisma/geodesk):

```
git clone https://github.com/clarisma/geodesk.git
cd geodesk
mvn install
cd ..
```

Then the GOL tool itself:

```
git clone https://github.com/clarisma/gol-tool.git
cd gol-tool
mvn install
```

*If you get weird exceptions during `mvn install`, you should [upgrade Maven](https://maven.apache.org/download.cgi) to version **3.8.5** or above.*

A `gol.bat` (for Windows) and a `gol` shell script are supplied.

**Linux users**: You may have to make the launcher script executable using `chmod u+x gol`. To conveniently use the command from any folder, consider creating a symbolic link on your path, e.g. <code>ln -s <i>gol_app_dir</i>/gol ~/bin/gol</code>.

If you experience performance problems or out-of-memory errors, you may need to override the default memory-management settings in the call to `java`. Use option `-Xmx` to explicitly set the maximum heap size, e.g. `-Xmx4g` to allow Java to use 4 GB of heap space. 

## Converting OpenStreetMap data into a GOL

Use

```
gol build world planet-latest.osm.pbf  
```

to turn a planet file into `world.gol`.

Please note that converting an OpenStreetMap PBF file into a GOL is a resource-intensive process. A multi-core system with at least 24 GB is recommended for larger datasets. On a 10-core workstation with 32 GB RAM and an NVMe SSD, building a GOL from a 60 GB planet file takes about half an hour. A machine with less memory will manage, but may start paging furiously. Nonetheless, even a dual-core notebook with 8 GB RAM converts a country-size extract such as Germany (3.5 GB) in about 20 minutes (a quality SSD clearly helps on such a machine).

You should have plenty of disk space available, to accommodate the temporary files and the resulting GOL. 3 to 5 times the size of the PBF is a good rule of thumb, though the ratio could be as high as 10:1 for smaller files.

### Java heap-size settings

If you get an "out of memory" error during the "compile" phase, you should increase the maximum heap settings (`-Xmx`) in the launcher script. When you re-run the `build` command, it will continue compiling, you don't have to wait for re-processing of the previous phases.

Conversely, you may get better throughput on a low-end machine (8 GB RAM, or less) by adjusting `-Xmx`) downward, to leave more swap space to the OS. 

## Using existing tile sets

If you just want to experiment with the GOL utility, you can download our example tile set. This is an October 2022 snapshot of the complete OpenStreetMap data for Switzerland (about 400 MB).

Use:

```
gol load -n swiss https://data.geodesk.com/switzerland
```

This creates `swiss.gol` and populates it with the Switzerland dataset. GOLs are about 30% larger than the compressed tile sets, so be sure to have sufficient disk space.

You can also instruct `gol` to download tiles as-needed, by specifying the tile-set URL as a command-line option (`-u=https://data.geodesk.com/switzerland`).

## Querying GOLs

The GOL utility performs [GOQL](http://docs.geodesk.com/goql) queries, within an optional bounding-box (`-b`) or polygonal area (`-a`).

For example:

```
gol query swiss na[amenity=fire_station] -f=geojson
```

finds all fire stations in Switzerland and exports the features (nodes or areas) as GeoJSON.

The command-line utility only supports a subset of GeoDesk's query capabilities. For spatial joins, you will have to submit two commands. For example, to extract all pubs in Bavaria from `germany.gol`, use:

```
gol query germany a[boundary=administrative][admin_level=4][name:en=Bavaria] -f=poly > bavaria.poly
```

This creates a polygon file, which you can then use to restrict the second query (output as CSV with longitude, latitude and name as columns):

```
gol query germany na[amenity=pub] -a=bavaria.poly -f=csv -t=lon,lat,name > pubs.csv
```

To visualize query results on a [Leaflet](http://www.leafletjs.com)-powered slippy map, use the `map` formatting option and open the resulting file in your browser:

```
gol query germany na[amenity=pub] -a=bavaria.poly -f=map -t=lon,lat,name > pubs.html
```


See the [full documentation](http://docs.geodesk.com/gol/query) for details. Please note that as the current release is still Early Access, some less-common options are not yet supported.

