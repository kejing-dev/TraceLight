package io.embodit.tracelight.data

import android.content.Context
import io.embodit.tracelight.domain.Hand
import io.embodit.tracelight.domain.LanguagePref
import io.embodit.tracelight.domain.LineColor
import io.embodit.tracelight.domain.PaperPose
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.SavedFrame
import io.embodit.tracelight.domain.Settings
import io.embodit.tracelight.domain.Vec3
import org.json.JSONObject

/** Pose of a world anchor in Stage metres. */
data class AnchorPose(val position: Vec3, val rotation: Quat4)

/** A world anchor as the app sees it. [id] is the platform's UUID as a string. */
data class AnchorRecord(val id: String, val pose: AnchorPose)

sealed interface AnchorOutcome<out T> {
    data class Ok<T>(val value: T) : AnchorOutcome<T>

    data class Failed(val code: Int, val message: String) : AnchorOutcome<Nothing>
}

/** The platform's persistent anchors (`platform.anchor.WorldAnchorGateway`; a fake in tests). */
interface AnchorGateway {
    suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord>

    /** All anchors of this app in this space; an empty store is `Ok(emptyList())`. */
    suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>>

    suspend fun remove(anchorId: String): AnchorOutcome<Unit>
}

/** Settings and the last sheet on the device (no network, no account; design `dataModel`). */
interface TraceStore {
    fun loadSettings(): Settings

    fun saveSettings(settings: Settings)

    fun loadFrame(): SavedFrame?

    fun saveFrame(frame: SavedFrame?)
}

class PrefsTraceStore(context: Context) : TraceStore {
    private val prefs = context.getSharedPreferences("trace_light", Context.MODE_PRIVATE)

    override fun loadSettings(): Settings =
        Settings(
            language = LanguagePref.entries.firstOrNull { it.tag == prefs.getString("lang", null) } ?: LanguagePref.SYSTEM,
            hand = Hand.entries.firstOrNull { it.tag == prefs.getString("hand", null) } ?: Hand.RIGHT,
            lineColor = LineColor.entries.firstOrNull { it.tag == prefs.getString("line", null) } ?: LineColor.BLUE,
            opacity = prefs.getInt("opacity", 70).coerceIn(20, 100),
        )

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString("lang", settings.language.tag)
            .putString("hand", settings.hand.tag)
            .putString("line", settings.lineColor.tag)
            .putInt("opacity", settings.opacity)
            .apply()
    }

    override fun loadFrame(): SavedFrame? =
        runCatching {
            val o = JSONObject(prefs.getString(KEY_FRAME, null) ?: return null)
            SavedFrame(
                anchorId = o.getString("anchor"),
                pose = PaperPose(Vec3(o.getDouble("cx").toFloat(), o.getDouble("cy").toFloat(), o.getDouble("cz").toFloat()), o.getDouble("yaw").toFloat()),
                anchorPos = Vec3(o.getDouble("ax").toFloat(), o.getDouble("ay").toFloat(), o.getDouble("az").toFloat()),
                alignedAtMs = o.optLong("at"),
            )
        }.getOrNull()

    override fun saveFrame(frame: SavedFrame?) {
        if (frame == null) {
            prefs.edit().remove(KEY_FRAME).apply()
            return
        }
        val o =
            JSONObject()
                .put("anchor", frame.anchorId)
                .put("cx", frame.pose.center.x.toDouble()).put("cy", frame.pose.center.y.toDouble()).put("cz", frame.pose.center.z.toDouble())
                .put("yaw", frame.pose.yaw.toDouble())
                .put("ax", frame.anchorPos.x.toDouble()).put("ay", frame.anchorPos.y.toDouble()).put("az", frame.anchorPos.z.toDouble())
                .put("at", frame.alignedAtMs)
        prefs.edit().putString(KEY_FRAME, o.toString()).apply()
    }

    private companion object {
        const val KEY_FRAME = "frame"
    }
}
