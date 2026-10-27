package io.embodit.tracelight.platform.camera

import android.content.Context
import android.os.SharedMemory
import com.pico.spatial.ml.readback.readbackContentSuspend
import com.pico.spatial.ml.securemr.GlobalTensor
import com.pico.spatial.ml.securemr.Pipeline
import com.pico.spatial.ml.securemr.SpatialMLInstance
import com.pico.spatial.ml.securemr.SpatialMLSession
import com.pico.spatial.ml.securemr.Tensor
import io.embodit.tracelight.platform.TraceLog
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The passthrough camera, read back into the app (SpatialML Readback Mode, `android.permission.CAMERA`;
 * SDK doc "Read data back to the app"). One pipeline per frame: `rectifiedVSTAccess` gives the
 * rectified stereo pair, its timestamp and intrinsics; the right image goes to an RGBA global tensor
 * (readback), and `uvTo3DInCameraSpace` turns the image points in [uv] into camera-space 3D points by
 * stereo. Everything runs on a background scope; [latest] is the last frame read back.
 */
class PaperCamera {
    data class Frame(
        val width: Int,
        val height: Int,
        /** RGBA, row-major, 4 bytes per pixel. */
        val rgba: ByteArray,
        /** Row-major 3x3 intrinsics. */
        val camMatrix: FloatArray,
        val timestamp: IntArray,
        /** The image points that were projected (pixels) and their camera-space 3D points (metres). */
        val uv: FloatArray,
        val points: FloatArray,
        val readAtMs: Long,
        /** Head pose when the frame was read back: position xyz + rotation xyzw (Stage), empty if unknown. */
        val head: FloatArray,
    )

    /** Head pose source (position xyz + rotation xyzw), sampled right after each submit. */
    @Volatile var headSource: (() -> FloatArray?)? = null

    private var scope: CoroutineScope? = null
    private var session: Deferred<SpatialMLSession>? = null
    private var rgba: GlobalTensor? = null
    private var cam: GlobalTensor? = null
    private var ts: GlobalTensor? = null
    private var uvTensor: GlobalTensor? = null
    private var pts: GlobalTensor? = null
    private var pipeline: Pipeline? = null

    @Volatile var latest: Frame? = null
        private set

    @Volatile var frames = 0L
        private set

    @Volatile var status: String = "off"
        private set

    /** Image points to project, pixels (x, y) pairs; set before [start] or between frames. */
    @Volatile var uvPixels: FloatArray = grid()

    fun start(context: Context, hz: Int = 2) {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        status = "starting"
        val app = context.applicationContext
        val sess =
            s.async {
                val instance = SpatialMLInstance.create(app)
                while (!instance.ready) delay(100)
                // the cameras only run while the headset is worn: "camera >>> configure => -1" until then, so retry
                var tries = 0
                var made: SpatialMLSession? = null
                while (made == null) {
                    tries++
                    made =
                        try {
                            instance.createSession(SpatialMLSession.InitInfo(imageWidth = W, imageHeight = H, containerWidth = 0, containerHeight = 0, containerDepth = 0))
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            status = "waiting for camera (try $tries): ${t.message}"
                            if (tries == 1 || tries % 10 == 0) TraceLog.w("CAM session try=$tries failed: $t")
                            null
                        }
                    if (made == null) delay(RETRY_MS)
                }
                TraceLog.i("CAM session made after tries=$tries")
                made
            }
        session = sess
        s.launch {
            try {
                val session = sess.await()
                // a plain RGB tensor, not a dynamic texture: the RGBA texture's readback came back with rows padded
                // to 768 px and cut at 512 x 512 x 4 bytes on Swan (2026-10-23), i.e. the top two thirds, interleaved
                rgba = session.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8_U, intArrayOf(H, W)))
                cam = session.newGlobalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.FLOAT32, intArrayOf(3, 3)))
                ts = session.newGlobalTensor(Tensor.TimeStampInitInfo())
                uvTensor = session.newGlobalTensor(Tensor.Point2ArrayInitInfo(Tensor.DataType.FLOAT32, N))
                pts = session.newGlobalTensor(Tensor.Point3ArrayInitInfo(Tensor.DataType.FLOAT32, N))
                writeUv(uvPixels)
                val p = session.newPipeline()
                p.apply {
                    val right = rgba!!
                    val left = newLocalTensor(Tensor.MultiDimensionalInitInfo(Tensor.DataType.Image.R8G8B8_U, intArrayOf(H, W)))
                    rectifiedVSTAccess(rightImageResult = right, leftImageResult = left, timestampResult = ts, cameraMatrixResult = cam)
                    // uvTo3DInCameraSpace crashed the system SecureMR backend on Swan (OS 6.2, 2026-10-23:
                    // SIGSEGV in Operator_UV2Cam::computeInternal), so 3D comes from rays onto the desk plane instead
                    if (STEREO) uvTo3DInCameraSpace(uvTensor!!, ts!!, cam!!, left, right, pts!!)
                }
                pipeline = p
                status = "running"
                TraceLog.i("CAM session ready ${W}x$H")
                val interval = 1000L / hz
                var failures = 0
                var task: Pipeline.RunTask? = null
                while (isActive) {
                    try {
                        task = p.submit(mapOf(), null, task)
                        val head = headSource?.invoke() ?: FloatArray(0)
                        delay(interval / 2)
                        readOnce(head)
                        if (failures > 0 || frames == 0L) TraceLog.i("CAM frame ok after failures=$failures")
                        frames++
                        status = "running frames=$frames"
                        failures = 0
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        failures++
                        status = "error ${t.javaClass.simpleName}: ${t.message}"
                        if (failures <= 3 || failures % 20 == 0) TraceLog.w("CAM frame failed ($failures): $t")
                        // back off while it keeps failing (headset off, permission missing)
                        if (failures > 3) delay(RETRY_MS)
                    }
                    task = null
                    delay(interval / 2)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                status = "failed ${t.javaClass.simpleName}: ${t.message}"
                TraceLog.e("CAM start failed: $t", t)
            }
        }
    }

    private suspend fun readOnce(head: FloatArray) {
        val img = rgba ?: return
        val raw = img.readbackContentSuspend().use { c -> c.buffer.rewind(); ByteArray(c.buffer.remaining()).also { c.buffer.get(it) } }
        val bytes = toRgba(raw)
        val m = floats(cam!!, 9)
        val t = ints(ts!!, 4)
        val p3 = if (STEREO) floats(pts!!, N * 3) else FloatArray(0)
        latest = Frame(W, H, bytes, m, t, uvPixels.copyOf(), p3, android.os.SystemClock.elapsedRealtime(), head)
    }

    private suspend fun floats(t: GlobalTensor, n: Int): FloatArray =
        t.readbackContentSuspend().use { c ->
            val b = c.buffer.order(ByteOrder.nativeOrder())
            b.rewind()
            val fb = b.asFloatBuffer()
            FloatArray(minOf(n, fb.remaining())).also { fb.get(it) }
        }

    private suspend fun ints(t: GlobalTensor, n: Int): IntArray =
        t.readbackContentSuspend().use { c ->
            val b = c.buffer.order(ByteOrder.nativeOrder())
            b.rewind()
            val ib = b.asIntBuffer()
            IntArray(minOf(n, ib.remaining())).also { ib.get(it) }
        }

    /** Packed (or row-padded) RGB from the readback -> RGBA, 4 bytes per pixel, as the detector reads it. */
    private fun toRgba(raw: ByteArray): ByteArray {
        val pitch = if (raw.size >= W * H * 3) raw.size / H else W * 3
        if (raw.size != W * H * 3 && !pitchLogged) {
            pitchLogged = true
            TraceLog.w("CAM rgb readback bytes=${raw.size} expected=${W * H * 3} pitch=$pitch")
        }
        val out = ByteArray(W * H * 4)
        for (y in 0 until H) {
            val row = y * pitch
            if (row + W * 3 > raw.size) break
            for (x in 0 until W) {
                val i = row + x * 3
                val o = (y * W + x) * 4
                out[o] = raw[i]
                out[o + 1] = raw[i + 1]
                out[o + 2] = raw[i + 2]
                out[o + 3] = -1
            }
        }
        return out
    }

    private var pitchLogged = false

    /** Writes [uv] (pixels, x/y pairs, N points) into the pipeline's input tensor. */
    fun writeUv(uv: FloatArray) {
        val t = uvTensor ?: return
        uvPixels = uv
        val mem = SharedMemory.create("uv", N * 2 * 4)
        val buf = mem.mapReadWrite().order(ByteOrder.nativeOrder())
        for (i in 0 until N * 2) buf.putFloat(uv.getOrElse(i) { 0f })
        SharedMemory.unmap(buf)
        t.tensorResource = mem
    }

    fun stop() {
        scope?.cancel()
        scope = null
        session = null
        pipeline = null
        latest = null
        status = "off"
    }

    companion object {
        const val W = 512
        const val H = 512
        const val N = 9
        const val RETRY_MS = 3000L
        const val STEREO = false

        /** A 3x3 grid over the middle of the image (pixels): the probe's stereo samples. */
        fun grid(): FloatArray {
            val out = FloatArray(N * 2)
            var k = 0
            for (j in 0 until 3) for (i in 0 until 3) {
                out[k++] = W * (0.3f + 0.2f * i)
                out[k++] = H * (0.3f + 0.2f * j)
            }
            return out
        }
    }
}
