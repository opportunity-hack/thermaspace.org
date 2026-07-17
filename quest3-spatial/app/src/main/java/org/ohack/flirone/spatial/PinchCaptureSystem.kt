package org.ohack.flirone.spatial

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.Transform

/**
 * PHYSICAL-CONTROLLER input only. Hands are deliberately excluded here:
 * for hand tracking the runtime maps the index pinches onto ButtonX (left)
 * and ButtonA (right), so acting on those bits would steal the pinch that
 * ISDK needs for panel/UI selection (that was v7's "I can't click any menu
 * buttons with my hands" bug). Hands get microgestures instead
 * (MicrogesturesSystem, wired in the activity).
 *
 *  - hold the RIGHT trigger = continuous [onInkHeld] with the controller pose
 *    (free-space drawing; the activity gates on Draw mode + throttles)
 *  - [onInkReleased] fires once when the right trigger lets go (ends a stroke)
 *  - face buttons on press edge: A [onButtonA], B [onButtonB], right
 *    thumbstick click [onStickR] (right), X [onButtonX], Y [onButtonY],
 *    MENU [onMenu] (left)
 */
class ControllerInputSystem(
    private val onInkHeld: (Pose?) -> Unit = {},
    private val onInkReleased: () -> Unit = {},
    private val onButtonA: () -> Unit = {},
    private val onButtonB: () -> Unit = {},
    private val onButtonX: () -> Unit = {},
    private val onButtonY: () -> Unit = {},
    private val onMenu: () -> Unit = {},
    private val onStickR: () -> Unit = {},
    // right stick deflection while held: (x: -1/0/+1 turn, y: -1/0/+1 push-pull)
    private val onStickMove: (Int, Int, Pose?) -> Unit = { _, _, _ -> },
    // right controller aim pose, every frame it's active (hover highlighting)
    private val onRightAim: (Pose?) -> Unit = {},
) : SystemBase() {

    private var rightWasDown = false
    private var prevRightButtons = -1   // -1: all bits set → no edges next frame
    private var prevLeftButtons = -1
    private var avatarEntity: Entity? = null   // cached: the Query costs at 72-90 Hz

    override fun execute() {
        var body = avatarEntity?.tryGetComponent<AvatarBody>()
            ?.takeIf { it.isPlayerControlled }
        if (body == null) {
            avatarEntity = Query.where { has(AvatarBody.id) }
                .eval()
                .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
            body = avatarEntity?.tryGetComponent<AvatarBody>() ?: return
        }

        val rightHand = body.rightHand
        val right = rightHand.tryGetComponent<Controller>()
        val rightIsCtrl = right != null && right.isActive &&
            right.type == ControllerType.CONTROLLER
        val rightDown = rightIsCtrl &&
            (right!!.buttonState and ButtonBits.ButtonTriggerR) != 0
        if (rightDown) {
            val pose = rightHand.tryGetComponent<Transform>()?.transform
            onInkHeld(pose)
        } else if (rightWasDown) {
            onInkReleased()
        }
        rightWasDown = rightDown

        if (rightIsCtrl) {
            val rb = right!!.buttonState
            if (edge(rb, prevRightButtons, ButtonBits.ButtonA)) onButtonA()
            if (edge(rb, prevRightButtons, ButtonBits.ButtonB)) onButtonB()
            // capture = stick CLICK only. The runtime also raises ThumbRClick
            // during plain deflection (field-observed: any stick direction
            // fired a capture), so the click edge only counts when the stick
            // is direction-neutral this frame AND the previous one.
            val dirMask = ButtonBits.ButtonThumbRU or ButtonBits.ButtonThumbRD or
                          ButtonBits.ButtonThumbRL or ButtonBits.ButtonThumbRR
            if ((rb and dirMask) == 0 && (prevRightButtons and dirMask) == 0 &&
                edge(rb, prevRightButtons, ButtonBits.ButtonThumbRClick)) onStickR()
            val pose = rightHand.tryGetComponent<Transform>()?.transform
            onRightAim(pose)
            // continuous while the stick is deflected (move/turn a drawing)
            val up = (rb and ButtonBits.ButtonThumbRU) != 0
            val down = (rb and ButtonBits.ButtonThumbRD) != 0
            val lft = (rb and ButtonBits.ButtonThumbRL) != 0
            val rgt = (rb and ButtonBits.ButtonThumbRR) != 0
            val dy = if (up != down) (if (up) 1 else -1) else 0
            val dx = if (lft != rgt) (if (rgt) 1 else -1) else 0
            if (dx != 0 || dy != 0) onStickMove(dx, dy, pose)
            prevRightButtons = rb
        } else {
            prevRightButtons = -1
            onRightAim(null)
        }

        val left = body.leftHand.tryGetComponent<Controller>()
        if (left == null || !left.isActive || left.type != ControllerType.CONTROLLER) {
            prevLeftButtons = -1
            return
        }
        val lb = left.buttonState
        if (edge(lb, prevLeftButtons, ButtonBits.ButtonX)) onButtonX()
        if (edge(lb, prevLeftButtons, ButtonBits.ButtonY)) onButtonY()
        if (edge(lb, prevLeftButtons, ButtonBits.ButtonMenu)) onMenu()
        prevLeftButtons = lb
    }

    private fun edge(cur: Int, prev: Int, bit: Int) =
        (cur and bit) != 0 && (prev and bit) == 0
}
