package app.photosphere

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Checks the reader-facing spherical metadata contract using a real namespace-aware XML parser. */
class PanoramaMetadataTest {
    @Test fun highResolutionCanvasIsDeclaredAsAnUncroppedEquirectangularSphere() {
        val description = description(PanoramaMetadata.xmp(20_480, 10_240, 48, 0L, 1.0))
        assertEquals("equirectangular", description.getAttributeNS(GPANO, "ProjectionType"))
        assertEquals("True", description.getAttributeNS(GPANO, "UsePanoramaViewer"))
        assertEquals("20480", description.getAttributeNS(GPANO, "FullPanoWidthPixels"))
        assertEquals("10240", description.getAttributeNS(GPANO, "FullPanoHeightPixels"))
        assertEquals("20480", description.getAttributeNS(GPANO, "CroppedAreaImageWidthPixels"))
        assertEquals("10240", description.getAttributeNS(GPANO, "CroppedAreaImageHeightPixels"))
        assertEquals("0", description.getAttributeNS(GPANO, "CroppedAreaLeftPixels"))
        assertEquals("0", description.getAttributeNS(GPANO, "CroppedAreaTopPixels"))
        assertEquals("48", description.getAttributeNS(GPANO, "SourcePhotosCount"))
        assertTrue(description.getAttributeNS(GPANO, "InitialHorizontalFOVDegrees").toDouble() in 1.0..179.0)
    }

    @Test fun incompletePhotographedCoverageDoesNotChangeTheFullCanvasGeometry() {
        val description = description(PanoramaMetadata.xmp(4096, 2048, 17, 0L, .73123456))
        assertEquals("4096", description.getAttributeNS(GPANO, "FullPanoWidthPixels"))
        assertEquals("2048", description.getAttributeNS(GPANO, "FullPanoHeightPixels"))
        assertEquals(.73123456, description.getAttributeNS(PHOTO_SPHERE, "EstimatedPhotographedCoverageFraction").toDouble(), .000000001)
        assertEquals("solid-angle estimate", description.getAttributeNS(PHOTO_SPHERE, "CoverageMeasurement"))
        assertFalse(description.hasAttributeNS(PHOTO_SPHERE, "Complete"))
        assertEquals("17", description.getAttributeNS(GPANO, "SourcePhotosCount"))
    }

    @Test fun unknownAbsoluteHeadingAndGeographicCoordinatesAreNotFabricated() {
        val description = description(PanoramaMetadata.xmp(4096, 2048, 1, 0L, .15))
        for (index in 0 until description.attributes.length) {
            val attribute = description.attributes.item(index)
            assertFalse(attribute.localName.orEmpty().contains("Heading", ignoreCase = true), "Unexpected heading: ${attribute.nodeName}")
            assertFalse(attribute.localName.orEmpty().contains("GPS", ignoreCase = true), "Unexpected GPS: ${attribute.nodeName}")
            assertFalse(attribute.localName.orEmpty() in listOf("Latitude", "Longitude", "Altitude"), "Unexpected coordinate: ${attribute.nodeName}")
        }
    }

    @Test fun decimalCoverageAndUtcTimestampAreIndependentOfDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val description = description(PanoramaMetadata.xmp(8192, 4096, 42, 0L, .975))
            assertEquals("0.97500000", description.getAttributeNS(PHOTO_SPHERE, "EstimatedPhotographedCoverageFraction"))
            assertEquals("1970-01-01T00:00:00Z", description.getAttributeNS(XMP, "CreateDate"))
            assertEquals("PhotoSphere", description.getAttributeNS(XMP, "CreatorTool"))
        } finally { Locale.setDefault(previous) }
    }

    private fun description(xml: String): Element {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val descriptions = document.getElementsByTagNameNS(RDF, "Description")
        assertEquals(1, descriptions.length)
        return descriptions.item(0) as Element
    }

    companion object {
        private const val GPANO = "http://ns.google.com/photos/1.0/panorama/"
        private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        private const val XMP = "http://ns.adobe.com/xap/1.0/"
        private const val PHOTO_SPHERE = "urn:app.photosphere:metadata:1.0"
    }
}
