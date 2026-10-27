package io.embodit.tracelight.platform.anchor

import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.world.WorldAnchor
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import io.embodit.tracelight.data.AnchorGateway
import io.embodit.tracelight.data.AnchorOutcome
import io.embodit.tracelight.data.AnchorRecord
import io.embodit.tracelight.data.AnchorPose
import io.embodit.tracelight.domain.Quat4
import io.embodit.tracelight.domain.Vec3
import io.embodit.tracelight.platform.TraceLog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * World anchors (`WorldTrackingManager`, Full Space only), as in Spatial Ruler: creation takes
 * 0.3 - 1 s, anchors survive a relaunch, and loading an empty store is `Error(-7)` (an empty list here).
 */
class WorldAnchorGateway : AnchorGateway {

    /** Debug: the next creation fails (`unsaved` state). */
    @Volatile var failNext = false

    /** Debug: creation is held this long before it answers (`saving` state). */
    @Volatile var holdMs = 0L

    /** Debug: loading answers "none here" (`elsewhere` state). */
    @Volatile var hideStored = false

    private var subscription: Cancellable? = null

    /** Anchor pose reports (any thread). */
    @Volatile var onUpdate: ((id: String, position: Vec3) -> Unit)? = null

    /**
     * Subscribes to anchor updates: world tracking answers "provider is not ready" (-6) to a creation
     * until someone listens (seen on the emulator; Spatial Ruler subscribes at scene start too).
     */
    fun start() {
        if (subscription != null) return
        subscription =
            runCatching {
                WorldTrackingManager.subscribeAnchorUpdate { u ->
                    // the spike's drift evidence: every pose the platform reports for our anchors
                    runCatching {
                        val p = u.anchor.transform.position
                        val pos = Vec3(p.x, p.y, p.z)
                        TraceLog.i("ANCHOR update ev=${u.event} id=${u.anchor.anchorUUID.toString().take(8)} pos=${TraceLog.pos(pos)}")
                        onUpdate?.invoke(u.anchor.anchorUUID.toString(), pos)
                    }
                }
            }.onFailure { TraceLog.e("ANCHOR subscribe failed: $it", it) }.getOrNull()
    }

    fun stop() {
        runCatching { subscription?.cancel() }
        subscription = null
    }

    override suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord> {
        if (holdMs > 0L) kotlinx.coroutines.delay(holdMs)
        if (failNext) {
            failNext = false
            return AnchorOutcome.Failed(-100, "debug fail_next_anchor")
        }
        var r = createOnce(position, name)
        var tries = 1
        // -6: the provider is not ready yet (just after the Stage opened): try again for a few seconds
        while (r is AnchorOutcome.Failed && r.code == NOT_READY && tries < NOT_READY_TRIES) {
            kotlinx.coroutines.delay(NOT_READY_RETRY_MS)
            r = createOnce(position, name)
            tries++
        }
        if (tries > 1) TraceLog.i("ANCHOR create tries=$tries ok=${r is AnchorOutcome.Ok}")
        return r
    }

    private suspend fun createOnce(position: Vec3, name: String): AnchorOutcome<AnchorRecord> =
        withContext(Dispatchers.Main) {
            guarded {
                when (val r = WorldTrackingManager.createAnchor(Vector3(position.x, position.y, position.z), EulerAngles(0f, 0f, 0f), name)) {
                    is WorldTrackingResult.Success -> r.data?.let { AnchorOutcome.Ok(it.toRecord()) } ?: AnchorOutcome.Failed(-1, "anchor created without data")
                    is WorldTrackingResult.Error -> AnchorOutcome.Failed(r.errorCode, r.errorMessage)
                }
            }
        }

    override suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>> {
        if (hideStored) return AnchorOutcome.Ok(emptyList())
        return withContext(Dispatchers.Main) {
            guarded {
                when (val r = WorldTrackingManager.loadAnchor()) {
                    is WorldTrackingResult.Success -> AnchorOutcome.Ok((r.data ?: emptyArray()).map { it.toRecord() })
                    is WorldTrackingResult.Error -> if (r.errorCode == NOT_FOUND) AnchorOutcome.Ok(emptyList()) else AnchorOutcome.Failed(r.errorCode, r.errorMessage)
                }
            }
        }
    }

    override suspend fun remove(anchorId: String): AnchorOutcome<Unit> =
        withContext(Dispatchers.Main) {
            guarded {
                when (val r = WorldTrackingManager.removeAnchor(UUID.fromString(anchorId))) {
                    is WorldTrackingResult.Success -> AnchorOutcome.Ok(Unit)
                    is WorldTrackingResult.Error -> if (r.errorCode == NOT_FOUND) AnchorOutcome.Ok(Unit) else AnchorOutcome.Failed(r.errorCode, r.errorMessage)
                }
            }
        }

    private inline fun <T> guarded(block: () -> AnchorOutcome<T>): AnchorOutcome<T> =
        try {
            block()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            AnchorOutcome.Failed(-100, t.toString())
        }

    private fun WorldAnchor.toRecord(): AnchorRecord {
        val p = transform.position
        val q = transform.quaternion
        return AnchorRecord(anchorUUID.toString(), AnchorPose(Vec3(p.x, p.y, p.z), Quat4(q.x, q.y, q.z, q.w).normalized()))
    }

    companion object {
        const val NOT_FOUND = -7
        const val NOT_READY = -6
        const val NOT_READY_TRIES = 8
        const val NOT_READY_RETRY_MS = 750L
    }
}
