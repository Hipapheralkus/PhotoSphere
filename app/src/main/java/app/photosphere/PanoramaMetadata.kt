package app.photosphere

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Writes one metadata update to a newly rendered JPEG; callers must never pass an original. */
object PanoramaMetadata {
    private val exifTime = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)
    private val gpsDate = DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US).withZone(ZoneOffset.UTC)
    private val gpsTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC)
    private val gpsTags = arrayOf(
        "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude",
        "GPSAltitudeRef", "GPSAltitude", "GPSTimeStamp", "GPSSatellites", "GPSStatus",
        "GPSMeasureMode", "GPSDOP", "GPSSpeedRef", "GPSSpeed", "GPSTrackRef", "GPSTrack",
        "GPSImgDirectionRef", "GPSImgDirection", "GPSMapDatum", "GPSDestLatitudeRef",
        "GPSDestLatitude", "GPSDestLongitudeRef", "GPSDestLongitude", "GPSDestBearingRef",
        "GPSDestBearing", "GPSDestDistanceRef", "GPSDestDistance", "GPSProcessingMethod",
        "GPSAreaInformation", "GPSDateStamp", "GPSDifferential", "GPSHPositioningError",
    )

    /**
     * Coverage is estimated photographed solid angle, not the fraction of planned targets saved.
     * GPano dimensions describe the full canvas even when unphotographed areas remain inside it.
     * This performs file IO and one ExifInterface.saveAttributes(), so call on a worker thread.
     */
    fun write(
        file: File,
        width: Int,
        height: Int,
        sourceCount: Int,
        createdAtMs: Long,
        location: CaptureLocation?,
        coverageFraction: Double,
    ) {
        require(file.isFile && file.length() > 0) { "The rendered panorama JPEG is missing." }
        require(width > 0 && height > 0 && width.toLong() == height.toLong() * 2L) { "A full panorama canvas must be 2:1." }
        require(sourceCount > 0) { "A panorama needs at least one source photograph." }
        require(coverageFraction.isFinite() && coverageFraction in 0.0..1.0) { "Invalid photographed coverage." }
        val exif = ExifInterface(file)
        exif.setAttribute(ExifInterface.TAG_XMP, xmp(width, height, sourceCount, createdAtMs, coverageFraction))
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        exif.setAttribute(ExifInterface.TAG_DATETIME, exifTime.format(Instant.ofEpochMilli(createdAtMs)))
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, "+00:00")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,
            String.format(Locale.US, "Equirectangular panorama; estimated photographed coverage %.2f%%. Unphotographed areas may remain.", coverageFraction * 100.0))

        // Clear stale geographic tags first. No location argument means no geographic EXIF.
        gpsTags.forEach { exif.setAttribute(it, null) }
        if (location != null) {
            require(location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                location.longitude.isFinite() && location.longitude in -180.0..180.0) { "Invalid panorama location." }
            exif.setLatLong(location.latitude, location.longitude)
            location.altitudeMeters?.takeIf { it.isFinite() }?.let(exif::setAltitude)
            location.accuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.let {
                exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, it.toString())
            }
            val gpsInstant = Instant.ofEpochMilli(location.timeMs)
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, gpsDate.format(gpsInstant))
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, gpsTime.format(gpsInstant))
        }
        exif.saveAttributes()
    }

    /** Pure XML generation is exposed internally for schema tests without Android image decoding. */
    internal fun xmp(width: Int, height: Int, sourceCount: Int, createdAtMs: Long, coverageFraction: Double): String {
        val coverage = String.format(Locale.US, "%.8f", coverageFraction)
        return """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="PhotoSphere">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:PS="urn:app.photosphere:metadata:1.0"
GPano:UsePanoramaViewer="True" GPano:ProjectionType="equirectangular"
GPano:CaptureSoftware="PhotoSphere" GPano:StitchingSoftware="PhotoSphere"
GPano:CroppedAreaImageWidthPixels="$width" GPano:CroppedAreaImageHeightPixels="$height"
GPano:FullPanoWidthPixels="$width" GPano:FullPanoHeightPixels="$height"
GPano:CroppedAreaLeftPixels="0" GPano:CroppedAreaTopPixels="0"
GPano:SourcePhotosCount="$sourceCount" GPano:InitialHorizontalFOVDegrees="80"
xmp:CreatorTool="PhotoSphere" xmp:CreateDate="${Instant.ofEpochMilli(createdAtMs)}"
PS:EstimatedPhotographedCoverageFraction="$coverage" PS:CoverageMeasurement="solid-angle estimate" />
</rdf:RDF></x:xmpmeta>"""
    }
}
