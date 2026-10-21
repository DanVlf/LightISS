package com.dan.lightiss

/** A parsed simplified world coastline for the monochrome map. */
object WorldMapData {

    class LandPolygon(val points: FloatArray)

    fun parse(bytes: ByteArray): List<LandPolygon> {
        val text = String(bytes, Charsets.UTF_8)
        val polygons = mutableListOf<LandPolygon>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val tokens = line.split(' ')
            val points = FloatArray(tokens.size * 2)
            var count = 0
            for (token in tokens) {
                val comma = token.indexOf(',')
                if (comma <= 0 || comma >= token.length - 1) continue
                val lat = token.substring(0, comma).toDoubleOrNull() ?: continue
                val lng = token.substring(comma + 1).toDoubleOrNull() ?: continue
                points[count * 2] = lat.toFloat()
                points[count * 2 + 1] = lng.toFloat()
                count++
            }
            if (count >= 4) {
                polygons.add(LandPolygon(points.copyOf(count * 2)))
            }
        }
        return polygons
    }
}
