package io.embodit.tracelight.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The tracing library (v0.2.0, user 2026-10-26: "画的内容丰富一点"): six sets, five drawings each from
 * easy to hard. Every drawing is drawn in code on a 100 x 140 canvas (x right -50..50, y away from the
 * user -70..70, units) and fitted onto whatever sheet was aligned ([LessonFit]). A drawing is a few
 * steps; a step is a group of strokes that appear together, the pen tip drawing them in order.
 */
data class Stroke(val points: List<Mm>, val dashed: Boolean = false)

data class Step(val zh: String, val en: String, val strokes: List<Stroke>)

data class Piece(val id: String, val set: String, val zh: String, val en: String, val level: Int, val steps: List<Step>)

data class LessonSet(val id: String, val zh: String, val en: String, val pieces: List<Piece>)

/** Canvas units -> millimetres on a sheet of [wMm] x [hMm]: the canvas fitted inside 15 mm margins, centred. */
class LessonFit(wMm: Float, hMm: Float) {
    val scale: Float = min((wMm - 2 * MARGIN_MM) / CANVAS_W, (hMm - 2 * MARGIN_MM) / CANVAS_H).coerceAtLeast(0.2f)

    fun mm(p: Mm): Mm = Mm(p.x * scale, p.y * scale)

    companion object {
        const val CANVAS_W = 100f
        const val CANVAS_H = 140f
        const val MARGIN_MM = 15f
    }
}

/** A tiny drawing DSL: every call adds one stroke to the step being written. */
class StepBuilder {
    val strokes = ArrayList<Stroke>()

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, dashed: Boolean = false) {
        strokes += Stroke(listOf(Mm(x1, y1), Mm(x2, y2)), dashed)
    }

    /** Straight segments through x, y pairs; [closed] joins the last point to the first. */
    fun poly(vararg xy: Float, closed: Boolean = false, dashed: Boolean = false) {
        val p = ArrayList<Mm>()
        var i = 0
        while (i + 1 < xy.size) {
            p += Mm(xy[i], xy[i + 1])
            i += 2
        }
        if (closed && p.isNotEmpty()) p += p[0]
        strokes += Stroke(p, dashed)
    }

    fun rect(x1: Float, y1: Float, x2: Float, y2: Float, dashed: Boolean = false) = poly(x1, y1, x2, y1, x2, y2, x1, y2, closed = true, dashed = dashed)

    /** An ellipse arc from [a0] to [a1] degrees (0 = right, counter-clockwise), rotated by [rot] degrees. */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, a0: Float = 0f, a1: Float = 360f, rot: Float = 0f, dashed: Boolean = false) {
        val n = (kotlin.math.abs(a1 - a0) / 6f).toInt().coerceAtLeast(6)
        val r = rot * PI.toFloat() / 180f
        val p = (0..n).map { i ->
            val a = (a0 + (a1 - a0) * i / n) * PI.toFloat() / 180f
            val x = rx * cos(a)
            val y = ry * sin(a)
            Mm(cx + x * cos(r) - y * sin(r), cy + x * sin(r) + y * cos(r))
        }
        strokes += Stroke(p, dashed)
    }

    fun circle(cx: Float, cy: Float, r: Float, dashed: Boolean = false) = ellipse(cx, cy, r, r, dashed = dashed)

    fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float) = ellipse(cx, cy, r, r, a0, a1)

    /** A smooth curve through x, y pairs (Catmull-Rom); [closed] makes a loop. */
    fun curve(vararg xy: Float, closed: Boolean = false, dashed: Boolean = false) {
        val pts = ArrayList<Mm>()
        var i = 0
        while (i + 1 < xy.size) {
            pts += Mm(xy[i], xy[i + 1])
            i += 2
        }
        strokes += Stroke(catmull(pts, closed), dashed)
    }

    /** Cubic Bezier from (x0, y0) to (x3, y3). */
    fun bez(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val p = (0..20).map { k ->
            val t = k / 20f
            val u = 1 - t
            Mm(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3, u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3)
        }
        strokes += Stroke(p)
    }

    companion object {
        fun catmull(p: List<Mm>, closed: Boolean): List<Mm> {
            if (p.size < 3) return p
            val pts = if (closed) listOf(p.last()) + p + listOf(p[0], p[1]) else listOf(p[0]) + p + listOf(p.last())
            val out = ArrayList<Mm>()
            for (i in 1 until pts.size - 2) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[i + 2]
                for (k in 0 until 8) {
                    val t = k / 8f
                    val t2 = t * t
                    val t3 = t2 * t
                    fun c(a: Float, b: Float, cc: Float, d: Float) = 0.5f * (2 * b + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t2 + (-a + 3 * b - 3 * cc + d) * t3)
                    out += Mm(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y))
                }
            }
            out += if (closed) p[0] else p.last()
            return out
        }
    }
}

private class PieceBuilder(val id: String, val set: String, val zh: String, val en: String, val level: Int) {
    val steps = ArrayList<Step>()

    fun step(zh: String, en: String, block: StepBuilder.() -> Unit) {
        steps += Step(zh, en, StepBuilder().apply(block).strokes)
    }

    fun build() = Piece(id, set, zh, en, level, steps)
}

private fun piece(id: String, set: String, zh: String, en: String, level: Int, block: PieceBuilder.() -> Unit): Piece = PieceBuilder(id, set, zh, en, level).apply(block).build()

object Library {
    val sets: List<LessonSet> by lazy { listOf(lines(), animals(), plants(), faces(), buildings(), characters()) }

    val all: List<Piece> by lazy { sets.flatMap { it.pieces } }

    fun piece(id: String): Piece? = all.firstOrNull { it.id == id }

    fun set(id: String): LessonSet? = sets.firstOrNull { it.id == id }

    /** The piece after [id] in its set (null after the last). */
    fun next(id: String): Piece? {
        val p = piece(id) ?: return null
        val list = set(p.set)?.pieces ?: return null
        val i = list.indexOfFirst { it.id == id }
        return list.getOrNull(i + 1)
    }

    // ------------------------------------------------------------------------------------------
    // 线条练习: hand warm-up, from straight lines to a cube
    // ------------------------------------------------------------------------------------------
    private fun lines() =
        LessonSet(
            "lines", "线条练习", "Line warm-up",
            listOf(
                piece("lines-1", "lines", "横线和竖线", "Straight lines", 1) {
                    step("五条横线，从左往右，一口气画完", "Five lines, left to right, in one go") {
                        for (y in listOf(55f, 42f, 29f, 16f, 3f)) line(-40f, y, 40f, y)
                    }
                    step("五条竖线，从上往下", "Five lines, top to bottom") {
                        for (x in listOf(-40f, -20f, 0f, 20f, 40f)) line(x, -10f, x, -62f)
                    }
                },
                piece("lines-2", "lines", "斜线和排线", "Hatching", 2) {
                    step("斜线排满左边的方框", "Fill the left box with slanted lines") {
                        rect(-44f, 20f, -4f, 60f)
                        for (k in 0 until 8) line(-44f + k * 5f, 20f, -4f, 60f - k * 5f)
                    }
                    step("右边再交叉排一遍", "Cross-hatch the right box") {
                        rect(4f, 20f, 44f, 60f)
                        for (k in 0 until 8) line(4f + k * 5f, 20f, 44f, 60f - k * 5f)
                        for (k in 0 until 8) line(44f - k * 5f, 20f, 4f, 60f - k * 5f)
                    }
                    step("下面三格：疏、中、密", "Three boxes: light, medium, dense") {
                        rect(-44f, -50f, -18f, -10f)
                        rect(-13f, -50f, 13f, -10f)
                        rect(18f, -50f, 44f, -10f)
                        for (k in 1..4) line(-44f + k * 5.2f, -50f, -44f + k * 5.2f, -10f)
                        for (k in 1..8) line(-13f + k * 2.9f, -50f, -13f + k * 2.9f, -10f)
                        for (k in 1..12) line(18f + k * 2f, -50f, 18f + k * 2f, -10f)
                    }
                },
                piece("lines-3", "lines", "圆和椭圆", "Circles and ellipses", 2) {
                    step("先画一个方框", "A square first") { rect(-30f, 5f, 30f, 65f) }
                    step("四边中点连成圆", "A circle touching the four sides") { circle(0f, 35f, 30f) }
                    step("压扁的方框里是椭圆", "A flattened box holds an ellipse") {
                        rect(-40f, -55f, 40f, -20f, dashed = true)
                        ellipse(0f, -37.5f, 40f, 17.5f)
                    }
                    step("三个小圆，越来越小", "Three circles, smaller and smaller") {
                        circle(-30f, -5f, 8f)
                        circle(-8f, -5f, 6f)
                        circle(10f, -5f, 4f)
                    }
                },
                piece("lines-4", "lines", "波浪和螺旋", "Waves and spirals", 3) {
                    step("三条波浪线，手腕放松", "Three waves, loose wrist") {
                        for (row in 0 until 3) {
                            val y0 = 55f - row * 14f
                            val pts = (0..32).flatMap { i -> val x = -42f + i * 84f / 32f; listOf(x, y0 + 5f * sin(i / 32f * 4f * PI.toFloat())) }
                            poly(*pts.toFloatArray())
                        }
                    }
                    step("从中心往外画螺旋", "A spiral from the centre outwards") {
                        val pts = (0..220).flatMap { i -> val a = i / 220f * 6f * PI.toFloat(); val r = 1.5f + a * 1.7f; listOf(r * cos(a), -25f + r * sin(a)) }
                        poly(*pts.toFloatArray())
                    }
                },
                piece("lines-5", "lines", "立方体", "A cube", 4) {
                    step("前面的正方形", "The front square") { rect(-35f, -45f, 10f, 0f) }
                    step("后面的正方形，往右上挪一点", "The back square, up and to the right") {
                        poly(-15f, 20f, 30f, 20f, 30f, -25f)
                        poly(-15f, 20f, -15f, -25f, 30f, -25f, dashed = true)
                    }
                    step("连起四条棱", "Join the four edges") {
                        line(-35f, 0f, -15f, 20f)
                        line(10f, 0f, 30f, 20f)
                        line(10f, -45f, 30f, -25f)
                        line(-35f, -45f, -15f, -25f, dashed = true)
                    }
                },
            ),
        )

    // ------------------------------------------------------------------------------------------
    // 简笔动物
    // ------------------------------------------------------------------------------------------
    private fun animals() =
        LessonSet(
            "animals", "简笔动物", "Simple animals",
            listOf(
                piece("animals-1", "animals", "小鱼", "Fish", 1) {
                    step("身体像一片叶子", "A body like a leaf") { curve(-35f, 5f, -15f, 25f, 15f, 22f, 30f, 5f, 15f, -12f, -15f, -15f, closed = true) }
                    step("三角形的尾巴", "A triangle tail") { poly(30f, 5f, 48f, 22f, 44f, 5f, 48f, -12f, closed = true) }
                    step("眼睛和鳃", "Eye and gill") {
                        circle(-20f, 8f, 3f)
                        arc(-10f, 5f, 10f, -40f, 40f)
                    }
                    step("鳍和泡泡", "Fins and bubbles") {
                        poly(0f, 22f, 8f, 32f, 14f, 21f)
                        poly(2f, -14f, 8f, -24f, 12f, -13f)
                        circle(-38f, 30f, 3f)
                        circle(-42f, 42f, 2f)
                        circle(-36f, 50f, 1.5f)
                    }
                },
                piece("animals-2", "animals", "小鸟", "Bird", 2) {
                    step("圆圆的身体", "A round body") { ellipse(0f, -5f, 26f, 20f) }
                    step("小小的头", "A small head") { circle(-24f, 18f, 12f) }
                    step("嘴巴和眼睛", "Beak and eye") {
                        poly(-35f, 21f, -46f, 17f, -35f, 13f)
                        circle(-26f, 21f, 2f)
                    }
                    step("翅膀和尾巴", "Wing and tail") {
                        curve(-6f, 4f, 10f, 10f, 22f, 0f, 8f, -8f)
                        poly(24f, 2f, 42f, 12f, 40f, -2f, 24f, -10f)
                    }
                    step("两条腿站在树枝上", "Two legs on a branch") {
                        poly(-6f, -24f, -8f, -38f)
                        poly(6f, -24f, 6f, -38f)
                        line(-35f, -38f, 35f, -40f)
                    }
                },
                piece("animals-3", "animals", "小猫", "Cat", 3) {
                    step("头是一个圆", "The head is a circle") { circle(0f, 30f, 20f) }
                    step("两只尖耳朵", "Two pointed ears") {
                        poly(-17f, 41f, -15f, 60f, -4f, 49f)
                        poly(17f, 41f, 15f, 60f, 4f, 49f)
                    }
                    step("眼睛、鼻子和胡须", "Eyes, nose and whiskers") {
                        ellipse(-7f, 33f, 3f, 4f)
                        ellipse(7f, 33f, 3f, 4f)
                        poly(-2f, 26f, 2f, 26f, 0f, 23f, closed = true)
                        curve(-5f, 20f, -2f, 18f, 0f, 21f, 2f, 18f, 5f, 20f)
                        line(-10f, 25f, -28f, 28f)
                        line(-10f, 23f, -28f, 21f)
                        line(10f, 25f, 28f, 28f)
                        line(10f, 23f, 28f, 21f)
                    }
                    step("身体是一个大椭圆", "The body is a big oval") { curve(-14f, 12f, -22f, -15f, -18f, -45f, 0f, -50f, 18f, -45f, 22f, -15f, 14f, 12f) }
                    step("尾巴绕到前面", "The tail curls round") { curve(18f, -40f, 35f, -35f, 40f, -15f, 32f, 0f) }
                },
                piece("animals-4", "animals", "兔子", "Rabbit", 3) {
                    step("椭圆的头", "An oval head") { ellipse(0f, 15f, 17f, 15f) }
                    step("两只长耳朵", "Two long ears") {
                        curve(-8f, 28f, -16f, 50f, -12f, 66f, -4f, 52f, -2f, 29f)
                        curve(4f, 29f, 8f, 52f, 16f, 66f, 18f, 50f, 9f, 28f)
                    }
                    step("眼睛、鼻子、嘴", "Eyes, nose, mouth") {
                        circle(-6f, 18f, 2f)
                        circle(6f, 18f, 2f)
                        poly(-2f, 11f, 2f, 11f, 0f, 9f, closed = true)
                        curve(-4f, 6f, 0f, 9f, 4f, 6f)
                    }
                    step("身体和尾巴", "Body and tail") {
                        curve(-14f, 3f, -24f, -20f, -18f, -45f, 0f, -50f, 18f, -45f, 24f, -20f, 14f, 3f)
                        circle(24f, -40f, 6f)
                    }
                    step("前爪和后脚", "Paws and feet") {
                        ellipse(-8f, -46f, 7f, 4f)
                        ellipse(8f, -46f, 7f, 4f)
                        curve(-6f, -15f, -5f, -28f, -2f, -32f)
                        curve(6f, -15f, 5f, -28f, 2f, -32f)
                    }
                },
                piece("animals-5", "animals", "大象", "Elephant", 4) {
                    step("大大的身体", "A big body") { ellipse(12f, -8f, 30f, 20f) }
                    step("头和长鼻子", "Head and long trunk") {
                        circle(-20f, 8f, 15f)
                        curve(-33f, 2f, -38f, -12f, -40f, -28f, -46f, -38f, -40f, -42f)
                        curve(-28f, -3f, -30f, -16f, -32f, -30f, -36f, -38f)
                    }
                    step("扇子一样的耳朵和眼睛", "Fan ears and an eye") {
                        curve(-14f, 20f, 2f, 22f, 6f, 8f, 0f, -6f, -10f, -4f)
                        circle(-26f, 12f, 2f)
                    }
                    step("四条柱子一样的腿", "Four legs like pillars") {
                        poly(-12f, -18f, -12f, -48f, -2f, -48f, -2f, -24f)
                        poly(4f, -26f, 4f, -50f, 14f, -50f, 14f, -27f)
                        poly(24f, -26f, 24f, -50f, 34f, -50f, 34f, -24f)
                        poly(36f, -22f, 36f, -46f, 44f, -46f, 44f, -14f)
                    }
                    step("小尾巴", "A small tail") { curve(42f, -2f, 48f, -10f, 47f, -20f) }
                },
            ),
        )

    // ------------------------------------------------------------------------------------------
    // 花草
    // ------------------------------------------------------------------------------------------
    private fun plants() =
        LessonSet(
            "plants", "花草", "Plants and flowers",
            listOf(
                piece("plants-1", "plants", "叶子", "Leaf", 1) {
                    step("中间的叶脉", "The middle vein") { curve(0f, -55f, 2f, -10f, 0f, 30f, -6f, 60f) }
                    step("两边的叶形", "The two edges") {
                        curve(0f, -40f, -25f, -10f, -28f, 25f, -6f, 60f)
                        curve(0f, -40f, 24f, -12f, 22f, 25f, -6f, 60f)
                    }
                    step("斜着的侧脉", "Side veins") {
                        for (k in 0 until 4) {
                            val y = -28f + k * 18f
                            line(1f, y, -18f, y + 12f)
                            line(1f, y, 17f, y + 10f)
                        }
                    }
                },
                piece("plants-2", "plants", "郁金香", "Tulip", 2) {
                    step("一根直直的茎", "A straight stem") { curve(0f, -62f, 1f, -20f, 0f, 15f) }
                    step("杯子一样的花", "A cup-shaped flower") {
                        curve(-15f, 40f, -14f, 25f, 0f, 15f, 14f, 25f, 15f, 40f)
                        poly(-15f, 40f, -8f, 30f, 0f, 45f, 8f, 30f, 15f, 40f)
                    }
                    step("两片长叶子", "Two long leaves") {
                        curve(0f, -55f, -20f, -30f, -22f, 0f, -10f, -40f, 0f, -50f)
                        curve(0f, -45f, 18f, -25f, 20f, 5f, 8f, -30f, 1f, -40f)
                    }
                },
                piece("plants-3", "plants", "向日葵", "Sunflower", 3) {
                    step("中间的大圆", "The big centre") { circle(0f, 30f, 14f) }
                    step("一圈花瓣", "A ring of petals") {
                        for (k in 0 until 12) {
                            val a = k * 30f
                            val r = 26f
                            ellipse(r * cos(a * PI.toFloat() / 180f), 30f + r * sin(a * PI.toFloat() / 180f), 11f, 5f, rot = a)
                        }
                    }
                    step("花心的格子", "The seed grid") {
                        for (k in -2..2) {
                            line(-12f + 0f, 30f + k * 5f, 12f, 30f + k * 5f)
                            line(k * 5f, 18f, k * 5f, 42f)
                        }
                    }
                    step("茎和叶子", "Stem and leaves") {
                        curve(0f, 4f, 2f, -30f, 0f, -65f)
                        curve(1f, -25f, -18f, -18f, -30f, -30f, -10f, -36f, 1f, -32f)
                        curve(1f, -40f, 18f, -36f, 28f, -48f, 10f, -52f, 1f, -46f)
                    }
                },
                piece("plants-4", "plants", "仙人掌盆栽", "Potted cactus", 3) {
                    step("梯形的花盆", "A trapezoid pot") {
                        poly(-22f, -30f, 22f, -30f, 16f, -62f, -16f, -62f, closed = true)
                        rect(-25f, -30f, 25f, -22f)
                    }
                    step("圆柱一样的主干", "The main stem") { curve(-10f, -22f, -11f, 20f, -6f, 42f, 0f, 46f, 6f, 42f, 11f, 20f, 10f, -22f) }
                    step("两条胳膊", "Two arms") {
                        curve(-10f, 0f, -24f, 0f, -26f, 12f, -24f, 26f, -18f, 28f, -16f, 14f, -10f, 12f)
                        curve(10f, 14f, 22f, 14f, 25f, 22f, 24f, 34f, 18f, 35f, 17f, 25f, 10f, 24f)
                    }
                    step("小刺", "Little spines") {
                        for ((x, y) in listOf(-11f to 10f, 11f to 0f, -11f to 30f, 9f to 34f, -25f to 18f, 25f to 28f, 0f to 46f)) {
                            line(x - 2.5f, y + 2.5f, x + 2.5f, y - 2.5f)
                            line(x - 2.5f, y - 2.5f, x + 2.5f, y + 2.5f)
                        }
                    }
                },
                piece("plants-5", "plants", "玫瑰", "Rose", 5) {
                    step("中心的小螺旋", "A small spiral in the middle") {
                        val pts = (0..60).flatMap { i -> val a = i / 60f * 3.5f * PI.toFloat(); val r = 1f + a * 1.1f; listOf(r * cos(a), 35f + r * sin(a) * 0.8f) }
                        poly(*pts.toFloatArray())
                    }
                    step("包着它的两片花瓣", "Two petals wrapping it") {
                        curve(-14f, 38f, -16f, 26f, -4f, 20f, 8f, 22f)
                        curve(14f, 40f, 17f, 28f, 8f, 20f)
                    }
                    step("外面一圈大花瓣", "The outer petals") {
                        curve(-14f, 38f, -24f, 44f, -26f, 28f, -16f, 16f, 0f, 12f)
                        curve(0f, 12f, 16f, 15f, 26f, 26f, 22f, 42f, 14f, 40f)
                        curve(-16f, 46f, -8f, 54f, 6f, 54f, 18f, 46f)
                    }
                    step("茎和叶子", "Stem and leaves") {
                        curve(0f, 12f, -2f, -20f, 2f, -62f)
                        curve(-1f, -20f, -20f, -14f, -30f, -26f, -14f, -30f, -1f, -26f)
                        curve(1f, -38f, 18f, -32f, 26f, -44f, 12f, -48f, 1f, -44f)
                    }
                },
            ),
        )

    // ------------------------------------------------------------------------------------------
    // 人物头像
    // ------------------------------------------------------------------------------------------
    private fun faces() =
        LessonSet(
            "faces", "人物头像", "Faces",
            listOf(
                piece("faces-1", "faces", "笑脸", "Smiley", 1) {
                    step("一个大圆", "A big circle") { circle(0f, 10f, 38f) }
                    step("两只眼睛", "Two eyes") {
                        ellipse(-13f, 22f, 4f, 7f)
                        ellipse(13f, 22f, 4f, 7f)
                    }
                    step("弯弯的嘴和脸蛋", "A smile and cheeks") {
                        arc(0f, 8f, 22f, 200f, 340f)
                        circle(-26f, 0f, 5f)
                        circle(26f, 0f, 5f)
                    }
                },
                piece("faces-2", "faces", "头部比例", "Head proportions", 2) {
                    step("蛋形的头", "An egg-shaped head") { curve(0f, 60f, 26f, 48f, 30f, 15f, 22f, -25f, 0f, -45f, -22f, -25f, -30f, 15f, -26f, 48f, closed = true) }
                    step("竖中线", "The centre line") { line(0f, 60f, 0f, -45f, dashed = true) }
                    step("眼睛在一半的高度", "Eyes sit halfway down") { line(-30f, 8f, 30f, 8f, dashed = true) }
                    step("鼻子和嘴的位置", "Where nose and mouth go") {
                        line(-20f, -14f, 20f, -14f, dashed = true)
                        line(-14f, -28f, 14f, -28f, dashed = true)
                    }
                },
                piece("faces-3", "faces", "眼睛", "An eye", 3) {
                    step("杏仁形的眼眶", "An almond outline") {
                        curve(-38f, 10f, -15f, 26f, 15f, 26f, 38f, 10f)
                        curve(-38f, 10f, -15f, -4f, 15f, -4f, 38f, 10f)
                    }
                    step("虹膜和瞳孔", "Iris and pupil") {
                        circle(0f, 10f, 13f)
                        circle(0f, 10f, 5f)
                    }
                    step("高光和双眼皮", "Highlight and crease") {
                        circle(4f, 14f, 2f)
                        curve(-34f, 18f, -12f, 34f, 14f, 34f, 34f, 18f)
                    }
                    step("眉毛", "The eyebrow") { curve(-38f, 34f, -15f, 48f, 15f, 48f, 40f, 40f) }
                },
                piece("faces-4", "faces", "正面五官", "Face from the front", 4) {
                    step("脸的轮廓", "The face outline") { curve(0f, 58f, 26f, 46f, 30f, 15f, 22f, -25f, 0f, -45f, -22f, -25f, -30f, 15f, -26f, 46f, closed = true) }
                    step("眼睛和眉毛", "Eyes and brows") {
                        ellipse(-12f, 8f, 7f, 3.5f)
                        ellipse(12f, 8f, 7f, 3.5f)
                        circle(-12f, 8f, 2f)
                        circle(12f, 8f, 2f)
                        curve(-20f, 16f, -12f, 19f, -4f, 16f)
                        curve(4f, 16f, 12f, 19f, 20f, 16f)
                    }
                    step("鼻子", "The nose") { curve(-2f, 4f, -3f, -8f, -6f, -14f, 0f, -16f, 6f, -14f) }
                    step("嘴巴", "The mouth") {
                        curve(-11f, -27f, -4f, -25f, 0f, -26f, 4f, -25f, 11f, -27f)
                        curve(-8f, -28f, 0f, -32f, 8f, -28f)
                    }
                    step("耳朵和头发", "Ears and hair") {
                        curve(-29f, 8f, -36f, 4f, -34f, -10f, -26f, -14f)
                        curve(29f, 8f, 36f, 4f, 34f, -10f, 26f, -14f)
                        curve(-30f, 20f, -32f, 50f, -10f, 66f, 15f, 64f, 32f, 48f, 30f, 20f)
                        curve(-26f, 40f, -10f, 50f, 10f, 46f, 28f, 36f)
                    }
                },
                piece("faces-5", "faces", "侧脸", "Profile", 5) {
                    step("头骨是一个大圆", "The skull as a big circle") { circle(8f, 26f, 26f) }
                    step("脸的侧线：额头、鼻子、嘴、下巴", "The profile: brow, nose, lips, chin") {
                        curve(-14f, 46f, -18f, 32f, -17f, 24f, -22f, 16f, -29f, 8f, -21f, 5f, -21f, 0f, -18f, -3f, -20f, -7f, -17f, -14f, -10f, -20f, 0f, -20f, 8f, -12f)
                    }
                    step("眼睛和耳朵", "Eye and ear") {
                        poly(-14f, 20f, -9f, 22f, -10f, 17f)
                        curve(10f, 14f, 14f, 18f, 18f, 12f, 16f, 2f, 11f, 2f)
                    }
                    step("脖子和头发", "Neck and hair") {
                        curve(-8f, -19f, -9f, -40f, -10f, -58f)
                        curve(14f, -6f, 16f, -30f, 20f, -58f)
                        curve(-14f, 46f, 0f, 56f, 20f, 54f, 34f, 40f, 36f, 18f, 28f, 4f)
                    }
                },
            ),
        )

    // ------------------------------------------------------------------------------------------
    // 建筑速写
    // ------------------------------------------------------------------------------------------
    private fun buildings() =
        LessonSet(
            "buildings", "建筑速写", "Buildings",
            listOf(
                piece("buildings-1", "buildings", "小房子", "A little house", 1) {
                    step("方方的墙", "The walls") { rect(-30f, -50f, 30f, 0f) }
                    step("三角形屋顶和烟囱", "Roof and chimney") {
                        poly(-38f, 0f, 0f, 32f, 38f, 0f)
                        poly(16f, 18f, 16f, 30f, 24f, 30f, 24f, 11f)
                    }
                    step("门和窗", "Door and windows") {
                        rect(-8f, -50f, 8f, -24f)
                        rect(-25f, -20f, -13f, -8f)
                        line(-19f, -20f, -19f, -8f)
                        rect(13f, -20f, 25f, -8f)
                        line(19f, -20f, 19f, -8f)
                    }
                },
                piece("buildings-2", "buildings", "灯塔", "Lighthouse", 2) {
                    step("上窄下宽的塔身", "A tapering tower") { poly(-14f, 25f, -20f, -55f, 20f, -55f, 14f, 25f, closed = true) }
                    step("灯室和尖顶", "Lamp room and roof") {
                        rect(-12f, 25f, 12f, 40f)
                        poly(-15f, 40f, 0f, 52f, 15f, 40f, closed = true)
                    }
                    step("条纹和门", "Stripes and door") {
                        line(-15f, 10f, 15f, 10f)
                        line(-17f, -12f, 17f, -12f)
                        line(-18f, -34f, 18f, -34f)
                        poly(-5f, -55f, -5f, -44f, 5f, -44f, 5f, -55f)
                    }
                    step("光线", "Light beams") {
                        line(14f, 34f, 42f, 44f)
                        line(14f, 30f, 44f, 28f)
                        line(-14f, 34f, -42f, 44f)
                        line(-14f, 30f, -44f, 28f)
                    }
                },
                piece("buildings-3", "buildings", "石拱桥", "Arch bridge", 3) {
                    step("桥面", "The deck") {
                        curve(-48f, 0f, -25f, 12f, 0f, 16f, 25f, 12f, 48f, 0f)
                        curve(-48f, -6f, -25f, 6f, 0f, 10f, 25f, 6f, 48f, -6f)
                    }
                    step("半圆的桥洞", "The round arch") { arc(0f, -30f, 28f, 0f, 180f) }
                    step("栏杆", "Railings") {
                        for (k in -4..4) {
                            val x = k * 10f
                            val y = 16f - (x / 48f) * (x / 48f) * 16f
                            line(x, y, x, y + 7f)
                        }
                        curve(-48f, 7f, -25f, 19f, 0f, 23f, 25f, 19f, 48f, 7f)
                    }
                    step("水面和倒影", "Water and reflection") {
                        line(-50f, -30f, 50f, -30f)
                        arc(0f, -30f, 28f, 200f, 340f)
                        for (y in listOf(-40f, -48f, -56f)) line(-44f, y, -20f, y)
                    }
                },
                piece("buildings-4", "buildings", "一点透视街道", "One-point street", 4) {
                    step("地平线和灭点", "Horizon and vanishing point") {
                        line(-50f, 10f, 50f, 10f)
                        circle(0f, 10f, 1.5f)
                    }
                    step("马路的两边连向灭点", "Road edges run to the point") {
                        line(-50f, -70f, 0f, 10f, dashed = true)
                        line(50f, -70f, 0f, 10f, dashed = true)
                        line(-20f, -70f, -4f, 10f - 4f * 0f)
                        line(20f, -70f, 4f, 10f)
                    }
                    step("左边的楼", "Buildings on the left") {
                        poly(-20f, -38f, -20f, 50f, -4f, 18f, -4f, 3f)
                        line(-50f, 50f, -20f, 50f)
                        line(-50f, -38f, -20f, -38f)
                        line(-20f, 50f, -20f, -38f)
                    }
                    step("右边的楼和窗", "Buildings and windows on the right") {
                        poly(20f, -38f, 20f, 40f, 4f, 16f, 4f, 3f)
                        line(20f, 40f, 50f, 40f)
                        for (k in 0 until 3) line(20f, 20f - k * 18f, 4f, 13f - k * 3f)
                    }
                },
                piece("buildings-5", "buildings", "两点透视小屋", "Two-point house", 5) {
                    step("地平线和两边的灭点", "Horizon and two vanishing points") {
                        line(-48f, 20f, 48f, 20f)
                        circle(-48f, 20f, 1.5f)
                        circle(48f, 20f, 1.5f)
                    }
                    step("最前面的竖棱", "The nearest corner edge") { line(0f, -10f, 0f, -50f) }
                    step("上下两端连向两个灭点", "Run the ends to both points") {
                        line(0f, -10f, -48f, 20f, dashed = true)
                        line(0f, -50f, -48f, 20f, dashed = true)
                        line(0f, -10f, 48f, 20f, dashed = true)
                        line(0f, -50f, 48f, 20f, dashed = true)
                    }
                    step("两侧的墙", "The two walls") {
                        line(-28f, 6.8f, -28f, -33.2f)
                        line(30f, 8f, 30f, -32f)
                        line(0f, -10f, -28f, 6.8f)
                        line(0f, -50f, -28f, -33.2f)
                        line(0f, -10f, 30f, 8f)
                        line(0f, -50f, 30f, -32f)
                    }
                    step("屋顶和门", "Roof and door") {
                        poly(-28f, 6.8f, -14f, 26f, 0f, -10f)
                        poly(-14f, 26f, 16f, 40f, 30f, 8f)
                        rect(10f, -44f, 18f, -28f)
                    }
                },
            ),
        )

    // ------------------------------------------------------------------------------------------
    // 汉字笔画 (stroke centre lines, in writing order and direction)
    // ------------------------------------------------------------------------------------------
    private fun characters() =
        LessonSet(
            "characters", "汉字笔画", "Chinese strokes",
            listOf(
                piece("characters-1", "characters", "基本笔画", "Basic strokes", 1) {
                    step("横：从左到右，稍微往上", "Héng: left to right, rising a little") { curve(-40f, 50f, -10f, 53f, 20f, 54f, 38f, 52f) }
                    step("竖：从上到下", "Shù: top to bottom") { line(0f, 40f, 0f, 0f) }
                    step("撇：往左下甩出去", "Piě: sweep down to the left") { curve(-5f, -5f, -10f, -20f, -22f, -32f, -38f, -40f) }
                    step("捺：往右下，最后压一下", "Nà: down to the right, press at the end") { curve(5f, -5f, 14f, -20f, 26f, -34f, 40f, -40f) }
                    step("点：短短一下", "Diǎn: a short dot") { curve(-2f, -50f, 1f, -54f, 3f, -60f) }
                },
                piece("characters-2", "characters", "人", "人 (person)", 2) {
                    step("撇", "Piě") { curve(4f, 55f, 0f, 20f, -14f, -15f, -38f, -45f) }
                    step("捺", "Nà") { curve(-2f, 15f, 10f, -10f, 24f, -32f, 42f, -45f) }
                },
                piece("characters-3", "characters", "大", "大 (big)", 2) {
                    step("横", "Héng") { curve(-38f, 25f, -10f, 28f, 20f, 29f, 38f, 27f) }
                    step("撇", "Piě") { curve(0f, 55f, -1f, 20f, -12f, -15f, -38f, -48f) }
                    step("捺", "Nà") { curve(-2f, 10f, 10f, -15f, 24f, -35f, 42f, -48f) }
                },
                piece("characters-4", "characters", "木", "木 (tree)", 3) {
                    step("横", "Héng") { curve(-38f, 25f, -10f, 28f, 20f, 29f, 38f, 27f) }
                    step("竖", "Shù") { line(0f, 58f, 0f, -55f) }
                    step("撇", "Piě") { curve(-2f, 22f, -10f, 0f, -24f, -20f, -40f, -32f) }
                    step("点（右边）", "Diǎn (right)") { curve(2f, 22f, 12f, 6f, 26f, -10f, 38f, -22f) }
                },
                piece("characters-5", "characters", "永", "永 (forever)", 4) {
                    step("点", "Diǎn") { curve(-4f, 62f, 0f, 58f, 3f, 52f) }
                    step("横折钩", "Héng-zhé-gōu") {
                        poly(-14f, 40f, 4f, 42f, 2f, -50f)
                        curve(2f, -50f, -4f, -48f, -9f, -44f)
                    }
                    step("横撇", "Héng-piě") {
                        poly(-34f, 20f, -16f, 22f)
                        curve(-16f, 22f, -22f, 8f, -32f, -4f, -42f, -10f)
                    }
                    step("撇", "Piě") { curve(28f, 28f, 22f, 16f, 12f, 8f, 6f, 4f) }
                    step("捺", "Nà") { curve(6f, 4f, 16f, -12f, 30f, -30f, 44f, -40f) }
                },
            ),
        )
}
