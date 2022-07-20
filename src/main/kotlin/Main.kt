package filaments

import mil.nga.tiff.FieldType
import mil.nga.tiff.FileDirectory
import mil.nga.tiff.TIFFImage
import mil.nga.tiff.TiffReader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.ximgproc.Ximgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt


fun TIFFImage.forEach(block: (FileDirectory) -> Unit) {
    this.fileDirectories.forEach {
        block(it)
    }
}

fun FileDirectory.toMat(): Mat {
    val rasters = this.readRasters()
    val type = rasters.fieldTypes[0]
    val data = rasters.sampleValues[0]
    when (type) {
        FieldType.BYTE -> {
            val image = Mat(this.imageWidth.toInt(), this.imageHeight.toInt(), CvType.CV_8U)
            val array = ByteArray(data.remaining())
            data.get(array)
            image.put(0, 0, array)
            return image
        }
        FieldType.SHORT -> {
            val image = Mat(this.imageWidth.toInt(), this.imageHeight.toInt(), CvType.CV_16U)
            val buffer = data.asShortBuffer()
            val array = ShortArray(buffer.remaining())
            buffer.get(array)
            image.put(0, 0, array)
            return image
        }
        else -> throw UnsupportedOperationException()
    }
}


fun main(string: Array<String>) {

    System.loadLibrary("opencv_java")

    val tiffImage: TIFFImage = TiffReader.readTiff(File(string.firstOrNull()?: "_1_MMStack_Pos0.ome.tif"))
    val full = Mat.zeros(512, 512, CvType.CV_32F)
    tiffImage.forEach {
        val image = it.toMat()
        image.convertTo(image, CvType.CV_8U, 1 / 255.0)
        Imgproc.medianBlur(image, image, 5)
        Imgproc.threshold(image, image, 22.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.accumulate(image, full)
    }

    full.convertTo(full, CvType.CV_8U, 1.0 / tiffImage.fileDirectories.size)
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    val static = Mat()
    Imgproc.dilate(full, static, kernel)
    Imgproc.threshold(static, static, 128.0, 255.0, Imgproc.THRESH_BINARY)
    //Imgproc.threshold(full, full, 128.0, 255.0, Imgproc.THRESH_BINARY)
    var n = 0;
    tiffImage.forEach {
        val base = it.toMat()
        base.convertTo(base, CvType.CV_8U, 1 / 255.0)
        val image = it.toMat()
        Imgproc.medianBlur(base, image, 5)
        val singleLayer = Mat()
        Imgproc.threshold(image, singleLayer, 22.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.medianBlur(singleLayer, singleLayer, 5)
        val doubleLayer = Mat()
        Imgproc.threshold(image, doubleLayer, 40.0, 255.0, Imgproc.THRESH_BINARY)
        var kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.dilate(doubleLayer, doubleLayer, kernel)
        val zero = Mat.zeros(512, 512, CvType.CV_8U)

        kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.erode(singleLayer, singleLayer, kernel)
        Imgproc.erode(singleLayer, singleLayer, kernel)
        val thinned = Mat()
        Ximgproc.thinning(singleLayer, thinned, Ximgproc.THINNING_GUOHALL)

        //Imgproc.dilate(thinned, thinned, kernel)
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(thinned, corners, 1000, 0.01, 10.0)


        val ls = Imgproc.createLineSegmentDetector()
        val linesMat = MatOfFloat4()
        val doubleLayerThinned = Mat()
        Ximgproc.thinning(doubleLayer, doubleLayerThinned)
        ls.detect(thinned, linesMat)

        val base1 = Mat()
        base.convertTo(base1, CvType.CV_8U, 3.0)
        val dst = arrayListOf(singleLayer, thinned, doubleLayer)
        val render = Mat()
        Core.merge(dst, render)
        if (linesMat.width() > 0) {
            var linesDirty =
                linesMat.toList().chunked(2).map { Point(it.map { it.toDouble() }.toDoubleArray()) }.chunked(2)
            for ((p1, p2) in linesDirty) {
                Imgproc.line(render, p1, p2, Scalar(255.0, 0.0, 0.0), 1)
            }
            var points = mutableListOf<Point>()
            linesDirty.flatten().asSequence().filter { p -> points.none { p.distanceTo(it) < 10.0 } }.forEach {
                points.add(it)
            }

            linesDirty.sortedBy { it[0].distanceTo(it[1]) }
            val lines = mutableListOf<List<Point>>()
            linesDirty
                .map { line -> line.map { p1 -> points.minByOrNull { p2 -> p1.distanceTo(p2) }!! } }
                .asSequence()
                .filter { line ->
                    lines.none { isSimilar(it, line) }
                }
                .filter { line -> lines.none { it.isBetter(line) } }
                .forEach { lines.add((it)) }
            require(lines.all { it.all { it in points } })
            val tryPoints = points.filter { p -> lines.count { p in it } == 1 }
            for ((p1, p2) in lines) {
                Imgproc.line(render, p1, p2, Scalar(255.0, 0.0, 255.0), 1)
            }
            for (p in points) {
                Imgproc.circle(render, p, 3, Scalar(0.0, 255.0, 255.0), 1)
            }
            for (p in tryPoints) {
                Imgproc.circle(render, p, 3, Scalar(0.0, 0.0, 255.0), 1)
            }
        }
        Imgcodecs.imwrite("Image${n++}.jpg", render)

    }
    System.exit(0)
}

fun List<Point>.isBetter(other: List<Point>): Boolean {
    val len = max(this[0].distanceTo(this[1]), other[0].distanceTo(other[1]))
    val c1 = Point((this[0].x + this[1].x) / 2, (this[0].y + this[1].y) / 2)
    val c2 = Point((other[0].x + other[1].x) / 2, (other[0].y + other[1].y) / 2)
    if (c1.distanceTo(c2) > len)
        return false
    val x1 = this[0].x - this[1].x
    val y1 = this[0].y - this[1].y
    val x2 = other[0].x - other[1].x
    val y2 = other[0].y - other[1].y
    val dot = x1 * x2 + y1 * y2
    val det = x1 * y2 + y1 * x2
    val angle = atan2(det, dot)
    //println(angle)
    return abs(angle) < 1
}


fun isSimilar(line1: List<Point>, line2: List<Point>, limit: Double = 10.0): Boolean {
    return ((line1[0].distanceTo(line2[0]) < limit) && (line1[1].distanceTo(line2[1]) < limit)) ||
            ((line1[1].distanceTo(line2[0]) < limit) && (line1[0].distanceTo(line2[1]) < limit))

}

fun Point.distanceTo(other: Point) =
    sqrt((this.x - other.x) * (this.x - other.x) + (this.y - other.y) * (this.y - other.y))


