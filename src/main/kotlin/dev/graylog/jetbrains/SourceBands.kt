package dev.graylog.jetbrains

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.function.IntFunction

/** Adjacent visible rows join by exact source identity, independently of their assigned color. */
object SourceBands {
    data class Segment(val previous: Boolean, val next: Boolean)
    @JvmStatic fun at(row: Int, rows: Int, sourceAt: IntFunction<String>): Segment? {
        if (row !in 0 until rows) return null
        val source = sourceAt.apply(row)
        if (source.isBlank()) return null
        return Segment(row > 0 && sourceAt.apply(row - 1) == source, row + 1 < rows && sourceAt.apply(row + 1) == source)
    }
    @JvmStatic fun paint(graphics: Graphics, color: Color, x: Int, width: Int, height: Int, gap: Int, radius: Int, segment: Segment) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = color
            val top = if (segment.previous) -radius else gap
            val bottom = if (segment.next) height + radius else height - gap
            g.fillRoundRect(x, top, width, (bottom - top).coerceAtLeast(0), radius, radius)
        } finally { g.dispose() }
    }
}
