<img src="https://docs.geodesk.com/img/logo2.png" width="30%">

# Geographic Object Librarian (GOL)

Use the GOL command-line utility to:

- build and maintain Geographic Object Libraries (GeoDesk's compact database format for OpenStreetMap features)
 
- perform [GOQL](http://docs.geodesk.com/goql) queries and export the results in a variety of formats.

## Setup

The GOL Utility is a cross-platform Java application. You will need to install a 64-bit JVM, Version 16 or above.

Apart from the 64-bit requirement, any reasonable hardware will do. The exception is the `gol build` command, which has higher recommended system configurations for reasonable performance.

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
gol load -n swiss http://data.geodesk.com/switzerland
```

This creates `swiss.gol` and populates it with the Switzerland dataset. GOLs are about 30% larger than the compressed tile sets, so be sure to have sufficient disk space.

You can also instruct `gol` to download tiles as-needed, by specifying the tile-set URL as a command-line option (`-u=http://data.geodesk.com/switzerland`).

## Querying GOLs

The GOL utility performs [GOQL](http://docs.geodesk.com/goql) queries, within an optional bounding-box (`-b`) or polygonal area (`-a`).

For example:

```
gol query swiss na[amenity=fire_station] -f=geojson
```

finds all fire stations in Switzerland and exports the features (nodes or areas) as GeoJSON.

See the [full documentation](http://docs.geodesk.com/gol/query) for details. Please note that as the current release is still Early Access, some less-common options are not yet supported.

