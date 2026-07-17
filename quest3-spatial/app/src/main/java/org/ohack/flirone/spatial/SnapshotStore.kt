package org.ohack.flirone.spatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists thermal snapshots as {PNG, pose} pairs so the room heatmap
 * reloads across sessions. Poses are stored in the scene's LOCAL_FLOOR
 * reference space — stable in the same room/boundary; may shift if the
 * headset recreates its map (true cross-session spatial anchors are not
 * yet a public Spatial SDK API).
 */
class SnapshotStore(context: Context) {
    /** [pose] is world-space when [anchorUuid] is null, else local to that MRUK anchor.
     *  [inWorld]=false: capture lives only in the gallery (Undo/Clear unplace it
     *  from the room but never delete — deletion is the gallery's 🗑 only).
     *  [locked]: the placed quad live-updates from the camera when gazed at. */
    data class Snap(val id: Long, val file: File, val pose: Pose, val note: String,
                    val anchorUuid: String? = null, val scale: Float = 1f,
                    val name: String = "", val inWorld: Boolean = true,
                    val pal: String = "",   // palette at capture; id ≈ capture time (ms)
                    val locked: Boolean = false)

    private val dir = File(context.filesDir, "snaps").apply { mkdirs() }
    private val index = File(dir, "index.json")
    // in-memory index: callers used to re-read + re-parse the JSON file on
    // every mutation (UI-thread jank that grew with the gallery). All index
    // methods are synchronized — captures persist on an IO thread now.
    private var cache: List<Snap>? = null
    private var nextId = (loadIndex().maxOfOrNull { it.id } ?: 0L) + 1

    /** Reserve a capture id (timestamp-monotonic — it doubles as the capture
     *  time and filename) so the quad can spawn immediately while the slow
     *  PNG/JPEG persistence runs on the IO thread. */
    @Synchronized
    fun mintId(): Long {
        val id = maxOf(System.currentTimeMillis(), nextId)
        nextId = id + 1
        return id
    }

    @Synchronized
    fun save(id: Long, bitmap: Bitmap, pose: Pose, note: String,
             anchorUuid: String? = null, scale: Float = 1f, name: String = "",
             pal: String = ""): Snap {
        val f = File(dir, "$id.png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val snap = Snap(id, f, pose, note, anchorUuid, scale, name, true, pal)
        writeIndex(loadIndex().filterNot { it.id == id } + snap)
        return snap
    }

    @Synchronized
    fun renameSnap(id: Long, name: String) {
        writeIndex(loadIndex().map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Remove one capture from the room but keep it in the gallery.
     *  Leaving the room clears its live lock — locked implies placed. */
    @Synchronized
    fun setInWorld(id: Long, inWorld: Boolean) {
        writeIndex(loadIndex().map {
            if (it.id == id) it.copy(inWorld = inWorld, locked = it.locked && inWorld) else it
        })
    }

    @Synchronized
    fun setLocked(id: Long, locked: Boolean) {
        writeIndex(loadIndex().map { if (it.id == id) it.copy(locked = locked) else it })
    }

    /** Remove ALL captures from the room; the gallery keeps every file. */
    @Synchronized
    fun unplaceAll() {
        writeIndex(loadIndex().map { it.copy(inWorld = false, locked = false) })
    }

    /** Snapshot radiometric sidecar (written by saveRaw). Returns (w,h,raw) or null. */
    fun loadSnapRaw(id: Long): Triple<Int, Int, FloatArray>? {
        val f = File(dir, "$id.raw")
        if (!f.exists()) return null
        return try {
            val bb = java.nio.ByteBuffer.wrap(f.readBytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val w = bb.int; val h = bb.int
            // corrupt header → absurd dims → OutOfMemoryError (not an Exception)
            if (w !in 1..2048 || h !in 1..2048) return null
            Triple(w, h, FloatArray(w * h) { (bb.short.toInt() and 0xFFFF).toFloat() })
        } catch (e: Exception) { null }
    }

    /** Radiometric sidecar: 8-byte header (w,h as LE int32) + LE uint16 raw values,
     *  so snapshots can be re-paletted / re-measured later without re-shooting. */
    fun saveRaw(id: Long, w: Int, h: Int, data: FloatArray) {
        try {
            val bb = java.nio.ByteBuffer.allocate(8 + data.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(w); bb.putInt(h)
            for (v in data) bb.putShort(v.toInt().coerceIn(0, 65535).toShort())
            File(dir, "$id.raw").writeBytes(bb.array())
        } catch (_: Exception) {}
    }

    /** Batch pose write-back: one index write for N grab-moved snapshots. */
    @Synchronized
    fun updatePoses(poses: Map<Long, Pose>) {
        if (poses.isEmpty()) return
        writeIndex(loadIndex().map { s -> poses[s.id]?.let { s.copy(pose = it) } ?: s })
    }

    /** MSX edge sidecar (visible-cam Sobel magnitudes) so recalled captures can
     *  show the same edge detail as the live overlay. */
    fun saveEdge(id: Long, w: Int, h: Int, mag: ByteArray) {
        try {
            val bb = java.nio.ByteBuffer.allocate(8 + mag.size)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(w); bb.putInt(h); bb.put(mag)
            File(dir, "$id.edge").writeBytes(bb.array())
        } catch (_: Exception) {}
    }

    fun loadEdge(id: Long): Triple<Int, Int, ByteArray>? {
        val f = File(dir, "$id.edge")
        if (!f.exists()) return null
        return try {
            val bb = java.nio.ByteBuffer.wrap(f.readBytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val w = bb.int; val h = bb.int
            if (w !in 1..2048 || h !in 1..2048) return null
            val mag = ByteArray(w * h); bb.get(mag)
            Triple(w, h, mag)
        } catch (e: Exception) { null }
    }

    /** Real-world context photo from the FLIR's visible camera. */
    fun saveVisible(id: Long, jpeg: ByteArray) {
        try { File(dir, "$id.vis.jpg").writeBytes(jpeg) } catch (_: Exception) {}
    }

    fun loadVisible(id: Long): Bitmap? =
        File(dir, "$id.vis.jpg").takeIf { it.exists() }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /** Subsampled decode for list thumbnails (72 dp rows don't need 640 px). */
    fun loadThumb(f: File, sampleSize: Int): Bitmap? =
        f.takeIf { it.exists() }?.let {
            BitmapFactory.decodeFile(it.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize })
        }

    fun visibleFile(id: Long): File = File(dir, "$id.vis.jpg")

    /** Cheap "is this capture re-colorizable" check (raw sidecar present). */
    fun hasRaw(id: Long): Boolean = File(dir, "$id.raw").exists()

    // ---------------- free-space ink strokes (world-space polylines) ----------------
    private val inkIndex = File(dir, "ink.json")

    fun saveInk(strokes: List<List<Vector3>>) {
        try {
            val arr = JSONArray()
            for (s in strokes) {
                val flat = JSONArray()
                for (p in s) { flat.put(p.x.toDouble()); flat.put(p.y.toDouble()); flat.put(p.z.toDouble()) }
                arr.put(flat)
            }
            inkIndex.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    fun loadInk(): List<List<Vector3>> {
        if (!inkIndex.exists()) return emptyList()
        return try {
            val arr = JSONArray(inkIndex.readText())
            (0 until arr.length()).map { i ->
                val flat = arr.getJSONArray(i)
                (0 until flat.length() / 3).map { j ->
                    Vector3(flat.getDouble(j * 3).toFloat(),
                            flat.getDouble(j * 3 + 1).toFloat(),
                            flat.getDouble(j * 3 + 2).toFloat())
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized
    fun remove(id: Long) {
        val snaps = loadIndex().filterNot {
            if (it.id == id) {
                it.file.delete(); File(dir, "$id.raw").delete()
                File(dir, "$id.edge").delete(); File(dir, "$id.vis.jpg").delete()
                true
            } else false
        }
        writeIndex(snaps)
    }

    @Synchronized
    fun clear() {
        loadIndex().forEach {
            it.file.delete(); File(dir, "${it.id}.raw").delete()
            File(dir, "${it.id}.edge").delete(); File(dir, "${it.id}.vis.jpg").delete()
        }
        writeIndex(emptyList())
    }

    fun loadBitmap(snap: Snap): Bitmap? = BitmapFactory.decodeFile(snap.file.absolutePath)

    @Synchronized
    fun loadIndex(): List<Snap> {
        cache?.let { return it }
        if (!index.exists()) return emptyList<Snap>().also { cache = it }
        return try {
            parseIndex(JSONArray(index.readText())).also { cache = it }
        } catch (e: Exception) {
            // NEVER leave a corrupt index in place: the next save() would
            // rebuild from emptyList and orphan every prior capture
            try { index.renameTo(File(dir, "index.bad.json")) } catch (_: Exception) {}
            emptyList<Snap>().also { cache = it }
        }
    }

    /** Parse errors propagate to loadIndex, which quarantines the bad file. */
    private fun parseIndex(arr: JSONArray): List<Snap> =
        (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val f = File(dir, "${o.getLong("id")}.png")
                if (!f.exists()) return@mapNotNull null
                Snap(
                    o.getLong("id"), f,
                    Pose(
                        Vector3(o.getDouble("px").toFloat(), o.getDouble("py").toFloat(),
                                o.getDouble("pz").toFloat()),
                        Quaternion(o.getDouble("qw").toFloat(), o.getDouble("qx").toFloat(),
                                   o.getDouble("qy").toFloat(), o.getDouble("qz").toFloat()),
                    ),
                    o.optString("note", ""),
                    o.optString("anchor").ifEmpty { null },
                    o.optDouble("scale", 1.0).toFloat(),
                    o.optString("name", ""),
                    o.optBoolean("world", true),
                    o.optString("pal", ""),
                    o.optBoolean("locked", false),
                )
            }

    private fun writeIndex(snaps: List<Snap>) {
        cache = snaps
        val arr = JSONArray()
        for (s in snaps) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("px", s.pose.t.x); put("py", s.pose.t.y); put("pz", s.pose.t.z)
                put("qw", s.pose.q.w); put("qx", s.pose.q.x)
                put("qy", s.pose.q.y); put("qz", s.pose.q.z)
                put("note", s.note)
                s.anchorUuid?.let { put("anchor", it) }
                put("scale", s.scale)
                if (s.name.isNotEmpty()) put("name", s.name)
                put("world", s.inWorld)
                if (s.pal.isNotEmpty()) put("pal", s.pal)
                if (s.locked) put("locked", true)
            })
        }
        // atomic: a process kill mid-write must never leave a truncated index
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) index.writeText(arr.toString())
    }
}
