Geographic Object Librarian (GOL) 0.1.6 EA
==========================================

Copyright (c) Clarisma / GeoDesk contributors
Licensed under AGPL 3.0 (see LICENSE.txt)

Setup
-----

Unzip the distribution files in a location of your choice.

The GOL Utility requires Java Runtime Environment (64-bit) Version 16 or above.
Please ensure that the "java" executable is included on the system path.
(Try running "java -version")

Usage
-----

bin/gol <command> [options]

See http://docs.geodesk.com/gol for full documentation.

Linux users: You may have to make the launcher script executable using
"chmod u+x gol". To conveniently use the command from any folder, consider
creating a symbolic link on your path, e.g. "ln -s bin/gol ~/bin/gol".

If you experience performance problems or out-of-memory errors, you may need
to override the default memory-management settings in the call to "java".
Use option "-Xmx" to explicitly set the maximum heap size, e.g. "-Xmx4g"
 to allow Java to use 4 GB of heap space.

OpenStreetMap Data
------------------

Unless otherwise indicated, all datasets downloaded from data.geodesk.com are
(c) OpenStreetMap, licensed under Open Database License (ODbL) 1.0

The full text of the ODbL can be found here:
https://opendatacommons.org/licenses/odbl/1-0/
