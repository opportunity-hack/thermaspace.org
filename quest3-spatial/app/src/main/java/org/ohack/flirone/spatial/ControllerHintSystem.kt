package org.ohack.flirone.spatial

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import kotlin.math.sqrt

/**
 * Game-style button callouts: look at a controller OR a tracked hand and a
 * small card fades in above it explaining every input (buttons/trigger for
 * controllers; pinch + thumb microgestures for hands).
 *
 * Show/hide uses gaze-cone hysteresis (show inside ~23°, hide outside ~32°)
 * plus a distance gate, so the card doesn't flicker at the edge. Position is
 * nlerp-smoothed like the head-locked overlay.
 */
class ControllerHintSystem : SystemBase() {
    var leftCard: Entity? = null        // controller cards
    var rightCard: Entity? = null
    var leftHandCard: Entity? = null    // hand-tracking cards (pinch/microgestures)
    var rightHandCard: Entity? = null

    private companion object {
        const val SHOW_DOT = 0.92f     // ~23° cone to start showing
        const val HIDE_DOT = 0.85f     // ~32° to hide (hysteresis)
        const val MAX_DIST = 1.1f      // arm's reach only
        const val LIFT = 0.13f         // meters above the controller/hand
        const val FOLLOW = 0.35f
    }

    private class Side {
        var visible = false
        var current: Pose? = null
        var active: Entity? = null     // card currently owned by this side
    }
    private val left = Side()
    private val right = Side()
    // cached lookups: system find + avatar Query cost at 72-90 Hz
    private var bodySystem: PlayerBodyAttachmentSystem? = null
    private var avatarEntity: Entity? = null

    override fun execute() {
        val sys = bodySystem
            ?: systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()
                ?.also { bodySystem = it } ?: return
        val head = sys.tryGetLocalPlayerAvatarBody()
            ?.head?.tryGetComponent<Transform>()?.transform ?: return
        if (head == Pose()) return

        var body = avatarEntity?.tryGetComponent<AvatarBody>()
            ?.takeIf { it.isPlayerControlled }
        if (body == null) {
            avatarEntity = Query.where { has(AvatarBody.id) }
                .eval()
                .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
            body = avatarEntity?.tryGetComponent<AvatarBody>() ?: return
        }

        update(left, leftCard, leftHandCard, body.leftHand, head)
        update(right, rightCard, rightHandCard, body.rightHand, head)
    }

    private fun update(side: Side, ctrlCard: Entity?, handCard: Entity?,
                       hand: Entity, head: Pose) {
        val ctrl = hand.tryGetComponent<Controller>()
        val pose = hand.tryGetComponent<Transform>()?.transform
        if (ctrl == null || !ctrl.isActive || pose == null || pose == Pose()) {
            hide(side); return
        }
        // card for the active input modality; switching modality swaps cards
        val card = (if (ctrl.type == ControllerType.HAND) handCard else ctrlCard)
            ?: run { hide(side); return }
        if (side.active !== card) hide(side)
        side.active = card

        val to = pose.t - head.t
        val dist = sqrt(to.x * to.x + to.y * to.y + to.z * to.z)
        if (dist < 0.05f || dist > MAX_DIST) { hide(side); return }
        val gaze = head.q * Vector3(0f, 0f, 1f)
        val dot = (gaze.x * to.x + gaze.y * to.y + gaze.z * to.z) / dist

        val want = if (side.visible) dot > HIDE_DOT else dot > SHOW_DOT
        if (!want) { hide(side); return }

        // float above the controller/hand, facing the user
        val t = pose.t + Vector3(0f, LIFT, 0f)
        val look = t - head.t
        if (look.x * look.x + look.y * look.y + look.z * look.z < 1e-6f) {
            hide(side); return
        }
        val q = Quaternion.lookRotation(look.normalize())
        val desired = Pose(t, q)
        val prev = side.current
        side.current = if (prev == null) desired else Pose(
            lerp(prev.t, desired.t, FOLLOW), nlerp(prev.q, desired.q, FOLLOW))
        card.setComponent(Transform(side.current!!))
        if (!side.visible) { side.visible = true; card.setComponent(Visible(true)) }
    }

    private fun hide(side: Side) {
        if (side.visible) { side.visible = false; side.active?.setComponent(Visible(false)) }
        side.current = null
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
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-6f) b else Quaternion(w / n, x / n, y / n, z / n)
    }
}
