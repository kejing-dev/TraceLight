package io.embodit.tracelight.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonsTest {
    @Test
    fun sixSetsOfFiveFromEasyToHard() {
        assertEquals(6, Library.sets.size)
        for (s in Library.sets) {
            assertEquals(s.id, 5, s.pieces.size)
            val levels = s.pieces.map { it.level }
            assertEquals("${s.id} levels $levels", levels.sorted(), levels)
            for (p in s.pieces) {
                assertTrue(p.id, p.steps.isNotEmpty())
                for (st in p.steps) assertTrue("${p.id} ${st.zh}", st.strokes.isNotEmpty() && st.strokes.all { it.points.size >= 2 })
            }
        }
        assertEquals(30, Library.all.map { it.id }.toSet().size)
    }

    @Test
    fun everyStrokeStaysOnTheCanvas() {
        for (p in Library.all) for (st in p.steps) for (s in st.strokes) for (q in s.points) {
            assertTrue("${p.id} ${st.zh} x=${q.x}", q.x in -51f..51f)
            assertTrue("${p.id} ${st.zh} y=${q.y}", q.y in -71f..71f)
        }
    }

    @Test
    fun canvasFitsAnySheet() {
        val a4 = LessonFit(210f, 297f)
        assertEquals(1.8f, a4.scale, 1e-3f)
        val small = LessonFit(100f, 150f)
        assertTrue(small.mm(Mm(50f, 70f)).x <= 50f - LessonFit.MARGIN_MM + 0.01f)
        assertTrue(small.mm(Mm(50f, 70f)).y <= 75f - LessonFit.MARGIN_MM + 0.01f)
    }

    /** Writes every piece's strokes to build/lesson-previews/lessons.json (rendered by tools/preview_lessons.py). */
    @Test
    fun dumpStrokes() {
        val out = File("build/lesson-previews").apply { mkdirs() }
        val sb = StringBuilder("[")
        for ((pi, p) in Library.all.withIndex()) {
            if (pi > 0) sb.append(',')
            sb.append("{\"id\":\"${p.id}\",\"set\":\"${p.set}\",\"en\":\"${p.en}\",\"steps\":[")
            for ((si, st) in p.steps.withIndex()) {
                if (si > 0) sb.append(',')
                sb.append('[')
                for ((ki, k) in st.strokes.withIndex()) {
                    if (ki > 0) sb.append(',')
                    sb.append("{\"d\":${k.dashed},\"p\":[")
                    sb.append(k.points.joinToString(",") { "[${it.x},${it.y}]" })
                    sb.append("]}")
                }
                sb.append(']')
            }
            sb.append("]}")
        }
        sb.append(']')
        File(out, "lessons.json").writeText(sb.toString())
    }
}
