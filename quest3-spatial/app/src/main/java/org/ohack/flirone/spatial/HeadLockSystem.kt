package org.ohack.flirone.spatial

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform

/**
 * Keeps the live-thermal overlay panel locked to the user's gaze at the
 * camera's FOV distance, with light smoothing. The smoothing (~3 frames)
 * roughly matches the thermal camera's own latency, so the image doesn't
 * jitter but still tracks head motion tightly enough to read as "AR overlay".
 */
class HeadLockSystem : SystemBase() {
    var target: Entity? = null
    @Volatile var enabled = false
    var distance = 1.0f

    private var current: Pose? = null

    private var bodySystem: PlayerBodyAttachmentSystem? = null   // cached (72-90 Hz)

    override fun execute() {
        if (!enabled) { current = null; return }
        val entity = target ?: return
        val sys = bodySystem
            ?: systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()
                ?.also { bodySystem = it } ?: return
        val head = sys.tryGetLocalPlayerAvatarBody()
            ?.head
            ?.tryGetComponent<Transform>()?.transform ?: return
        if (head == Pose()) return

        val desired = Pose(head.t + head.q * Vector3(0f, 0f, distance), head.q)
        val prev = current
        current = if (prev == null) desired else Pose(
            lerp(prev.t, desired.t, 0.35f),
            nlerp(prev.q, desired.q, 0.35f),
        )
        entity.setComponent(Transform(current!!))
    }

    private fun lerp(a: Vector3, b: Vector3, f: Float) =
        Vector3(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f)

    private fun nlerp(a: Quaternion, b: Quaternion, f: Float): Quaternion {
        val dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
        val s = if (dot < 0f) -1f else 1f
        val w = a.w + (b.w * s - a.w) * f
        val x = a.x + (b.x * s - a.x) * f
        val y = a.y + (b.y * s - a.y) * f
        val z = a.z + (b.z * s - a.z) * f
        val n = kotlin.math.sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-6f) b else Quaternion(w / n, x / n, y / n, z / n)
    }
}
