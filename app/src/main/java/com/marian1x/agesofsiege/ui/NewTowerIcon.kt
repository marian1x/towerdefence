package com.marian1x.agesofsiege.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.marian1x.agesofsiege.core.Content

/** Compact tile illustrations for the three procedural tower models. */
@Composable
internal fun NewTowerIcon(id: String, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = Content.tower(id).name }) {
        val u = size.minDimension / 48f
        fun face(color: Long, vararg points: Pair<Float, Float>) {
            val path = Path()
            for ((index, point) in points.withIndex()) {
                val (x, y) = point
                if (index == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u)
            }
            path.close()
            drawPath(path, Color(color))
        }
        drawOval(Color(0x58000000), Offset(3f * u, 35f * u), Size(42f * u, 11f * u))
        face(0xFF7F8B9E, 5f to 34f, 22f to 26f, 43f to 33f, 26f to 42f)
        face(0xFF465368, 5f to 34f, 26f to 42f, 26f to 46f, 5f to 39f)
        face(0xFF5A6A7F, 26f to 42f, 43f to 33f, 43f to 38f, 26f to 46f)

        when (id) {
            "frost" -> {
                face(0xFF445F7C, 16f to 34f, 18f to 20f, 29f to 20f, 33f to 34f, 25f to 39f)
                face(0xFF3396C7, 14f to 24f, 18f to 30f, 25f to 33f, 32f to 28f, 35f to 18f, 23f to 4f)
                face(0xFF6CD8F8, 14f to 24f, 23f to 4f, 24f to 25f, 25f to 33f)
                face(0xFFC6F7FF, 23f to 4f, 35f to 18f, 24f to 25f)
                face(0xFF87E7FF, 9f to 23f, 15f to 26f, 17f to 35f, 11f to 31f)
                face(0xFF71D7ED, 38f to 17f, 39f to 29f, 32f to 34f, 34f to 25f)
            }
            "brazier" -> {
                face(0xFF71565B, 17f to 36f, 18f to 26f, 30f to 26f, 33f to 34f, 25f to 39f)
                face(0xFFAB6739, 8f to 21f, 40f to 21f, 35f to 32f, 25f to 36f, 14f to 32f)
                drawOval(Color(0xFFE5A44D), Offset(7f * u, 16f * u), Size(34f * u, 13f * u))
                drawOval(Color(0xFF642F30), Offset(10f * u, 18f * u), Size(28f * u, 8f * u))
                face(0xFFFF8140, 14f to 23f, 12f to 12f, 21f to 16f, 25f to 3f, 30f to 14f, 36f to 10f, 34f to 24f, 26f to 27f)
                face(0xFFFFDF84, 21f to 24f, 25f to 10f, 31f to 23f, 27f to 26f)
            }
            "repeater" -> {
                face(0xFF725138, 12f to 34f, 12f to 19f, 23f to 14f, 36f to 19f, 36f to 32f, 24f to 39f)
                face(0xFFB6804D, 12f to 19f, 23f to 14f, 36f to 19f, 25f to 25f)
                face(0xFF5D8B70, 14f to 24f, 24f to 29f, 24f to 12f, 33f to 9f, 37f to 17f, 31f to 25f)
                drawLine(Color(0xFFCCDFE4), Offset(7f * u, 12f * u), Offset(38f * u, 23f * u), 4f * u, cap = StrokeCap.Round)
                for (i in 0..2) drawLine(
                    Color(0xFFF1D58C),
                    Offset((16f + i * 3f) * u, 16f * u),
                    Offset((29f + i * 3f) * u, 6f * u),
                    1.6f * u,
                    cap = StrokeCap.Round,
                )
            }
            "banner" -> {
                // Pole, red war flag with a gold stripe.
                drawLine(Color(0xFF6E4A2A), Offset(19f * u, 38f * u), Offset(19f * u, 4f * u), 2.6f * u, cap = StrokeCap.Round)
                drawCircle(Color(0xFFF2C14E), 2.4f * u, Offset(19f * u, 4f * u))
                face(0xFFC4533A, 20f to 7f, 40f to 9f, 37f to 16f, 40f to 23f, 20f to 22f)
                face(0xFFF2C14E, 20f to 13f, 39f to 14f, 38f to 17f, 20f to 16f)
            }
        }
    }
}
