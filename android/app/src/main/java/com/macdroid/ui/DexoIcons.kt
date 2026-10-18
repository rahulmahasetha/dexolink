package com.macdroid.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Clean, minimal, professional vector icon design system for DexoLink Android.
 * Designed on a 24x24 grid with standard 2dp stroke, rounded caps, and consistent visual weight.
 */
object DexoIcons {

    private fun strokedIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    // Core Navigation & Tabs
    val Home: ImageVector = strokedIcon("Home") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 10f)
            lineTo(12f, 3f)
            lineTo(21f, 10f)
            lineTo(21f, 20f)
            curveTo(21f, 20.6f, 20.6f, 21f, 20f, 21f)
            lineTo(4f, 21f)
            curveTo(3.4f, 21f, 3f, 20.6f, 3f, 20f)
            close()
            moveTo(9f, 21f)
            lineTo(9f, 14f)
            lineTo(15f, 14f)
            lineTo(15f, 21f)
        }
    }
    val Folder: ImageVector = strokedIcon("Folder") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 7f)
            curveTo(3f, 5.9f, 3.9f, 5f, 5f, 5f)
            lineTo(10f, 5f)
            lineTo(12f, 7f)
            lineTo(19f, 7f)
            curveTo(20.1f, 7f, 21f, 7.9f, 21f, 9f)
            lineTo(21f, 18f)
            curveTo(21f, 19.1f, 20.1f, 20f, 19f, 20f)
            lineTo(5f, 20f)
            curveTo(3.9f, 20f, 3f, 19.1f, 3f, 18f)
            close()
        }
    }
    val Transfer: ImageVector = strokedIcon("Transfer") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 4f)
            lineTo(7f, 18f)
            moveTo(3.5f, 14.5f)
            lineTo(7f, 18f)
            lineTo(10.5f, 14.5f)
            moveTo(17f, 20f)
            lineTo(17f, 6f)
            moveTo(13.5f, 9.5f)
            lineTo(17f, 6f)
            lineTo(20.5f, 9.5f)
        }
    }
    val Settings: ImageVector = run {
        val gearPath = PathParser().parsePathString(
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"
        ).toNodes()
        val hubPath = PathParser().parsePathString(
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"
        ).toNodes()

        ImageVector.Builder(
            name = "Settings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = gearPath,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ).addPath(
            pathData = hubPath,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ).build()
    }

    // Actions & Navigation
    val ArrowBack: ImageVector = strokedIcon("ArrowBack") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19f, 12f)
            lineTo(5f, 12f)
            moveTo(11f, 6f)
            lineTo(5f, 12f)
            lineTo(11f, 18f)
        }
    }
    val Close: ImageVector = strokedIcon("Close") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }
    val ChevronRight: ImageVector = strokedIcon("ChevronRight") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 6f)
            lineTo(15f, 12f)
            lineTo(9f, 18f)
        }
    }
    val Bell: ImageVector = strokedIcon("Bell") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18f, 16f)
            lineTo(18f, 11f)
            curveTo(18f, 7.7f, 15.3f, 5f, 12f, 5f)
            curveTo(8.7f, 5f, 6f, 7.7f, 6f, 11f)
            lineTo(6f, 16f)
            lineTo(4f, 18f)
            lineTo(20f, 18f)
            close()
            moveTo(10f, 19f)
            curveTo(10f, 20.1f, 10.9f, 21f, 12f, 21f)
            curveTo(13.1f, 21f, 14f, 20.1f, 14f, 19f)
        }
    }
    val Trash: ImageVector = strokedIcon("Trash") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(10f, 11f); lineTo(10f, 17f)
            moveTo(14f, 11f); lineTo(14f, 17f)
            moveTo(6f, 7f)
            lineTo(7f, 19f)
            curveTo(7f, 20.1f, 7.9f, 21f, 9f, 21f)
            lineTo(15f, 21f)
            curveTo(16.1f, 21f, 17f, 20.1f, 17f, 19f)
            lineTo(18f, 7f)
            moveTo(9f, 7f)
            lineTo(9f, 4f)
            curveTo(9f, 3.4f, 9.4f, 3f, 10f, 3f)
            lineTo(14f, 3f)
            curveTo(14.6f, 3f, 15f, 3.4f, 15f, 4f)
            lineTo(15f, 7f)
        }
    }
    val Search: ImageVector = strokedIcon("Search") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(11f, 18f)
            curveTo(14.9f, 18f, 18f, 14.9f, 18f, 11f)
            curveTo(18f, 7.1f, 14.9f, 4f, 11f, 4f)
            curveTo(7.1f, 4f, 4f, 7.1f, 4f, 11f)
            curveTo(4f, 14.9f, 7.1f, 18f, 11f, 18f)
            close()
            moveTo(16f, 16f)
            lineTo(21f, 21f)
        }
    }
    val Edit: ImageVector = strokedIcon("Edit") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18.5f, 2.5f)
            curveTo(19.3f, 1.7f, 20.7f, 1.7f, 21.5f, 2.5f)
            curveTo(22.3f, 3.3f, 22.3f, 4.7f, 21.5f, 5.5f)
            lineTo(8.5f, 18.5f)
            lineTo(3f, 21f)
            lineTo(5.5f, 15.5f)
            close()
            moveTo(15f, 6f)
            lineTo(18f, 9f)
        }
    }
    val Check: ImageVector = strokedIcon("Check") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 12f)
            lineTo(9.5f, 17f)
            lineTo(20f, 6.5f)
        }
    }
    val Send: ImageVector = strokedIcon("Send") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 2f)
            lineTo(11f, 13f)
            moveTo(22f, 2f)
            lineTo(15f, 22f)
            lineTo(11f, 13f)
            lineTo(2f, 9f)
            close()
        }
    }
    val Play: ImageVector = ImageVector.Builder(
        name = "Play",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7f, 5f)
            lineTo(19f, 12f)
            lineTo(7f, 19f)
            close()
        }
    }.build()
    val Pause: ImageVector = strokedIcon("Pause") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 3.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8f, 5f); lineTo(8f, 19f)
            moveTo(16f, 5f); lineTo(16f, 19f)
        }
    }
    val MoreVert: ImageVector = ImageVector.Builder(
        name = "MoreVert",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 6f)
            curveTo(13f, 6f, 13.8f, 5.2f, 13.8f, 4.2f)
            curveTo(13.8f, 3.2f, 13f, 2.4f, 12f, 2.4f)
            curveTo(11f, 2.4f, 10.2f, 3.2f, 10.2f, 4.2f)
            curveTo(10.2f, 5.2f, 11f, 6f, 12f, 6f)
            close()
            moveTo(12f, 13.8f)
            curveTo(13f, 13.8f, 13.8f, 13f, 13.8f, 12f)
            curveTo(13.8f, 11f, 13f, 10.2f, 12f, 10.2f)
            curveTo(11f, 10.2f, 10.2f, 11f, 10.2f, 12f)
            curveTo(10.2f, 13f, 11f, 13.8f, 12f, 13.8f)
            close()
            moveTo(12f, 21.6f)
            curveTo(13f, 21.6f, 13.8f, 20.8f, 13.8f, 19.8f)
            curveTo(13.8f, 18.8f, 13f, 18f, 12f, 18f)
            curveTo(11f, 18f, 10.2f, 18.8f, 10.2f, 19.8f)
            curveTo(10.2f, 20.8f, 11f, 21.6f, 12f, 21.6f)
            close()
        }
    }.build()
    val ArrowUp: ImageVector = strokedIcon("ArrowUp") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 19f)
            lineTo(12f, 5f)
            moveTo(5f, 12f)
            lineTo(12f, 5f)
            lineTo(19f, 12f)
        }
    }
    val ArrowDown: ImageVector = strokedIcon("ArrowDown") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(12f, 19f)
            lineTo(19f, 12f)
        }
    }
    val Retry: ImageVector = strokedIcon("Retry") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 12f)
            curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f)
            curveTo(15.5f, 4f, 18.5f, 6.2f, 19.5f, 9.5f)
            moveTo(20f, 4f); lineTo(19.5f, 9.5f); lineTo(14f, 9f)
            moveTo(20f, 12f)
            curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f)
            curveTo(8.5f, 20f, 5.5f, 17.8f, 4.5f, 14.5f)
            moveTo(4f, 20f); lineTo(4.5f, 14.5f); lineTo(10f, 15f)
        }
    }

    // Features & Device Status
    val Clipboard: ImageVector = strokedIcon("Clipboard") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(16f, 4f)
            lineTo(18f, 4f)
            curveTo(19.1f, 4f, 20f, 4.9f, 20f, 6f)
            lineTo(20f, 20f)
            curveTo(20f, 21.1f, 19.1f, 22f, 18f, 22f)
            lineTo(6f, 22f)
            curveTo(4.9f, 22f, 4f, 21.1f, 4f, 20f)
            lineTo(4f, 6f)
            curveTo(4f, 4.9f, 4.9f, 4f, 6f, 4f)
            lineTo(8f, 4f)
            moveTo(8f, 4f)
            curveTo(8f, 2.9f, 8.9f, 2f, 10f, 2f)
            lineTo(14f, 2f)
            curveTo(15.1f, 2f, 16f, 2.9f, 16f, 4f)
            close()
            moveTo(8f, 11f); lineTo(16f, 11f)
            moveTo(8f, 15f); lineTo(14f, 15f)
        }
    }
    val History: ImageVector = strokedIcon("History") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 21f)
            curveTo(17f, 21f, 21f, 17f, 21f, 12f)
            curveTo(21f, 7f, 17f, 3f, 12f, 3f)
            curveTo(7f, 3f, 3f, 7f, 3f, 12f)
            curveTo(3f, 17f, 7f, 21f, 12f, 21f)
            close()
            moveTo(12f, 7f)
            lineTo(12f, 12f)
            lineTo(16f, 14f)
        }
    }
    val Link: ImageVector = strokedIcon("Link") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 13f)
            curveTo(9.4f, 13.6f, 8.5f, 14f, 7.5f, 14f)
            curveTo(5.6f, 14f, 4f, 12.4f, 4f, 10.5f)
            curveTo(4f, 8.6f, 5.6f, 7f, 7.5f, 7f)
            lineTo(10f, 7f)
            moveTo(14f, 11f)
            curveTo(14.6f, 10.4f, 15.5f, 10f, 16.5f, 10f)
            curveTo(18.4f, 10f, 20f, 11.6f, 20f, 13.5f)
            curveTo(20f, 15.4f, 18.4f, 17f, 16.5f, 17f)
            lineTo(14f, 17f)
            moveTo(8f, 12f)
            lineTo(16f, 12f)
        }
    }
    val Sync: ImageVector = strokedIcon("Sync") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 12f)
            curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
            curveTo(8.13f, 3f, 4.88f, 5.43f, 3.65f, 8.9f)
            moveTo(3f, 3f); lineTo(3.65f, 8.9f); lineTo(9.5f, 8.5f)
            moveTo(3f, 12f)
            curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
            curveTo(15.87f, 21f, 19.12f, 18.57f, 20.35f, 15.1f)
            moveTo(21f, 21f); lineTo(20.35f, 15.1f); lineTo(14.5f, 15.5f)
        }
    }
    val Wifi: ImageVector = strokedIcon("Wifi") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(2f, 8.5f)
            curveTo(8f, 3.5f, 16f, 3.5f, 22f, 8.5f)
            moveTo(5.5f, 12.5f)
            curveTo(9.5f, 9f, 14.5f, 9f, 18.5f, 12.5f)
            moveTo(9f, 16.5f)
            curveTo(10.8f, 15f, 13.2f, 15f, 15f, 16.5f)
            moveTo(12f, 20f)
            lineTo(12.01f, 20f)
        }
    }
    val Flash: ImageVector = strokedIcon("Flash") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(13f, 2f)
            lineTo(4f, 13f)
            lineTo(11f, 13f)
            lineTo(10f, 22f)
            lineTo(20f, 10f)
            lineTo(13f, 10f)
            close()
        }
    }
    val Shield: ImageVector = strokedIcon("Shield") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2f)
            lineTo(20f, 5f)
            lineTo(20f, 11f)
            curveTo(20f, 16.5f, 16.6f, 20.7f, 12f, 22f)
            curveTo(7.4f, 20.7f, 4f, 16.5f, 4f, 11f)
            lineTo(4f, 5f)
            close()
        }
    }
    val Lock: ImageVector = strokedIcon("Lock") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5f, 11f)
            curveTo(5f, 9.9f, 5.9f, 9f, 7f, 9f)
            lineTo(17f, 9f)
            curveTo(18.1f, 9f, 19f, 9.9f, 19f, 11f)
            lineTo(19f, 19f)
            curveTo(19f, 20.1f, 18.1f, 21f, 17f, 21f)
            lineTo(7f, 21f)
            curveTo(5.9f, 21f, 5f, 20.1f, 5f, 19f)
            close()
            moveTo(8f, 9f)
            lineTo(8f, 6f)
            curveTo(8f, 3.8f, 9.8f, 2f, 12f, 2f)
            curveTo(14.2f, 2f, 16f, 3.8f, 16f, 6f)
            lineTo(16f, 9f)
            moveTo(12f, 13.5f)
            lineTo(12f, 16.5f)
        }
    }
    val Key: ImageVector = strokedIcon("Key") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 2f)
            lineTo(10.5f, 12.5f)
            moveTo(14.5f, 8.5f)
            lineTo(18.5f, 12.5f)
            moveTo(12.5f, 10.5f)
            lineTo(15.5f, 13.5f)
            moveTo(7.5f, 19.5f)
            curveTo(9.7f, 19.5f, 11.5f, 17.7f, 11.5f, 15.5f)
            curveTo(11.5f, 13.3f, 9.7f, 11.5f, 7.5f, 11.5f)
            curveTo(5.3f, 11.5f, 3.5f, 13.3f, 3.5f, 15.5f)
            curveTo(3.5f, 17.7f, 5.3f, 19.5f, 7.5f, 19.5f)
            close()
        }
    }
    val DeviceMac: ImageVector = strokedIcon("DeviceMac") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 5f)
            curveTo(4f, 4.4f, 4.4f, 4f, 5f, 4f)
            lineTo(19f, 4f)
            curveTo(19.6f, 4f, 20f, 4.4f, 20f, 5f)
            lineTo(20f, 15f)
            lineTo(4f, 15f)
            close()
            moveTo(2f, 18f)
            lineTo(22f, 18f)
            moveTo(9f, 18f)
            lineTo(15f, 18f)
        }
    }
    val DevicePhone: ImageVector = strokedIcon("DevicePhone") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 4f)
            curveTo(6f, 2.9f, 6.9f, 2f, 8f, 2f)
            lineTo(16f, 2f)
            curveTo(17.1f, 2f, 18f, 2.9f, 18f, 4f)
            lineTo(18f, 20f)
            curveTo(18f, 21.1f, 17.1f, 22f, 16f, 22f)
            lineTo(8f, 22f)
            curveTo(6.9f, 22f, 6f, 21.1f, 6f, 20f)
            close()
            moveTo(11f, 18.5f)
            lineTo(13f, 18.5f)
        }
    }
    val Disconnect: ImageVector = strokedIcon("Disconnect") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 3f); lineTo(9f, 7f)
            moveTo(15f, 3f); lineTo(15f, 7f)
            moveTo(6f, 7f); lineTo(18f, 7f)
            lineTo(17f, 13f)
            curveTo(17f, 15.2f, 15.2f, 17f, 13f, 17f)
            lineTo(11f, 17f)
            curveTo(8.8f, 17f, 7f, 15.2f, 7f, 13f)
            close()
            moveTo(12f, 17f); lineTo(12f, 21f)
        }
    }
    val Globe: ImageVector = strokedIcon("Globe") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 21f)
            curveTo(17f, 21f, 21f, 17f, 21f, 12f)
            curveTo(21f, 7f, 17f, 3f, 12f, 3f)
            curveTo(7f, 3f, 3f, 7f, 3f, 12f)
            curveTo(3f, 17f, 7f, 21f, 12f, 21f)
            close()
            moveTo(3f, 12f); lineTo(21f, 12f)
            moveTo(12f, 3f)
            curveTo(15f, 7f, 15f, 17f, 12f, 21f)
            curveTo(9f, 17f, 9f, 7f, 12f, 3f)
        }
    }
    val Message: ImageVector = strokedIcon("Message") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 11.5f)
            curveTo(21f, 6.8f, 17f, 3f, 12f, 3f)
            curveTo(7f, 3f, 3f, 6.8f, 3f, 11.5f)
            curveTo(3f, 14.1f, 4.3f, 16.4f, 6.4f, 17.9f)
            lineTo(5f, 21f)
            lineTo(9.5f, 19.5f)
            curveTo(10.3f, 19.8f, 11.1f, 20f, 12f, 20f)
            curveTo(17f, 20f, 21f, 16.2f, 21f, 11.5f)
            close()
        }
    }

    // File Category Minimalist Icons
    val CategoryImage: ImageVector = strokedIcon("CategoryImage") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 5f)
            curveTo(4f, 4.4f, 4.4f, 4f, 5f, 4f)
            lineTo(19f, 4f)
            curveTo(19.6f, 4f, 20f, 4.4f, 20f, 5f)
            lineTo(20f, 19f)
            curveTo(20f, 19.6f, 19.6f, 20f, 19f, 20f)
            lineTo(5f, 20f)
            curveTo(4.4f, 20f, 4f, 19.6f, 4f, 19f)
            close()
            moveTo(8.5f, 9.5f)
            curveTo(9f, 9.5f, 9.5f, 9f, 9.5f, 8.5f)
            curveTo(9.5f, 8f, 9f, 7.5f, 8.5f, 7.5f)
            curveTo(8f, 7.5f, 7.5f, 8f, 7.5f, 8.5f)
            curveTo(7.5f, 9f, 8f, 9.5f, 8.5f, 9.5f)
            close()
            moveTo(4f, 16f)
            lineTo(9f, 12f)
            lineTo(14f, 16f)
            lineTo(17f, 13.5f)
            lineTo(20f, 16f)
        }
    }
    val CategoryPdf: ImageVector = strokedIcon("CategoryPdf") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 2f)
            lineTo(5f, 2f)
            curveTo(4.4f, 2f, 4f, 2.4f, 4f, 3f)
            lineTo(4f, 21f)
            curveTo(4f, 21.6f, 4.4f, 22f, 5f, 22f)
            lineTo(19f, 22f)
            curveTo(19.6f, 22f, 20f, 21.6f, 20f, 21f)
            lineTo(20f, 8f)
            close()
            moveTo(14f, 2f)
            lineTo(20f, 8f)
            moveTo(8f, 13f); lineTo(16f, 13f)
            moveTo(8f, 17f); lineTo(13f, 17f)
        }
    }
    val CategoryDoc: ImageVector = strokedIcon("CategoryDoc") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 2f)
            lineTo(5f, 2f)
            curveTo(4.4f, 2f, 4f, 2.4f, 4f, 3f)
            lineTo(4f, 21f)
            curveTo(4f, 21.6f, 4.4f, 22f, 5f, 22f)
            lineTo(19f, 22f)
            curveTo(19.6f, 22f, 20f, 21.6f, 20f, 21f)
            lineTo(20f, 8f)
            close()
            moveTo(14f, 2f)
            lineTo(20f, 8f)
            moveTo(8f, 12f); lineTo(16f, 12f)
            moveTo(8f, 16f); lineTo(16f, 16f)
        }
    }
    val CategoryVideo: ImageVector = strokedIcon("CategoryVideo") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 6f)
            curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f)
            lineTo(15f, 4f)
            curveTo(16.1f, 4f, 17f, 4.9f, 17f, 6f)
            lineTo(17f, 18f)
            curveTo(17f, 19.1f, 16.1f, 20f, 15f, 20f)
            lineTo(5f, 20f)
            curveTo(3.9f, 20f, 3f, 19.1f, 3f, 18f)
            close()
            moveTo(17f, 9.5f)
            lineTo(22f, 6.5f)
            lineTo(22f, 17.5f)
            lineTo(17f, 14.5f)
        }
    }
    val CategoryAudio: ImageVector = strokedIcon("CategoryAudio") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 18f)
            curveTo(7.9f, 18f, 7f, 17.1f, 7f, 16f)
            curveTo(7f, 14.9f, 7.9f, 14f, 9f, 14f)
            curveTo(10.1f, 14f, 11f, 14.9f, 11f, 16f)
            lineTo(11f, 4f)
            lineTo(19f, 2f)
            lineTo(19f, 14f)
            moveTo(19f, 16f)
            curveTo(17.9f, 16f, 17f, 15.1f, 17f, 14f)
            curveTo(17f, 12.9f, 17.9f, 12f, 19f, 12f)
            curveTo(20.1f, 12f, 21f, 12.9f, 21f, 14f)
            close()
        }
    }
    val CategoryZip: ImageVector = strokedIcon("CategoryZip") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 4f)
            lineTo(21f, 4f)
            lineTo(21f, 8f)
            lineTo(3f, 8f)
            close()
            moveTo(5f, 8f)
            lineTo(5f, 19f)
            curveTo(5f, 20.1f, 5.9f, 21f, 7f, 21f)
            lineTo(17f, 21f)
            curveTo(18.1f, 21f, 19f, 20.1f, 19f, 19f)
            lineTo(19f, 8f)
            moveTo(10f, 12f)
            lineTo(14f, 12f)
        }
    }
    val CategoryFile: ImageVector = strokedIcon("CategoryFile") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 2f)
            lineTo(5f, 2f)
            curveTo(4.4f, 2f, 4f, 2.4f, 4f, 3f)
            lineTo(4f, 21f)
            curveTo(4f, 21.6f, 4.4f, 22f, 5f, 22f)
            lineTo(19f, 22f)
            curveTo(19.6f, 22f, 20f, 21.6f, 20f, 21f)
            lineTo(20f, 8f)
            close()
            moveTo(14f, 2f)
            lineTo(20f, 8f)
        }
    }
}

/**
 * Standard composable wrapper for DexoIcons.
 */
@Composable
fun DexoIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}
