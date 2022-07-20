# Filament
## Prepare:
OpenCV version 4.0.1 (maybe newer ones will do, I took this one because it had extra libraries prebuilted for Windows)

https://docs.opencv.org/4.0.1/

## Build:
Gradle 6.8
gradle task shadowJar

Output:

build/libs/Filament-1.0-SNAPSHOT-all.jar

## Run:
Launch java -jar build/libs/Filament-1.0-SNAPSHOT-all.jar ПУТЬ_К_TIFF файлу (for check I add _1_MMStack_Pos0.ome.tif to git)

Maybe you need add path to OpenCV libs to launch line -DJava.library.path=...  I just place all libs in to project directory

Output:

40 jpg files in launch dir
