package io.github.azizconi.sharedframe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFrameCoreTest {
    @Test fun collapsedMaskMapsToNonSquareSource() {
        val parent = Frame(0f, 0f, 1080f, 2400f)
        val hero = Frame(0f, 180f, 1080f, 1260f)
        val source = Frame(12f, 240f, 332f, 600f)
        val geometry = checkNotNull(SharedFrameMath.buildGeometry(source, parent, hero))
        val mapped = mapFrame(geometry.collapsedMask, parent, geometry.collapsedTransform)
        assertFrame(source, mapped)
    }

    @Test fun imageMatrixRoundTripIsExact() {
        val parent = Frame(0f, 0f, 1080f, 2400f)
        val hero = Frame(0f, 180f, 1080f, 1125f)
        val transform = UniformTransform(.37f, -320f, -610f)
        val requested = ImageTransform(.31f, 8f, 248f)
        val local = checkNotNull(SharedFrameMath.localImageTransformForScreen(requested, parent, hero, transform))
        val mapped = checkNotNull(SharedFrameMath.imageTransformInScreen(local, parent, hero, transform))
        assertEquals(requested.scale, mapped.scale, .001f)
        assertEquals(requested.translationX, mapped.translationX, .001f)
        assertEquals(requested.translationY, mapped.translationY, .001f)
    }

    @Test fun draggedClosingKeepsStableDetailBaselineAndExactSourceEndpoint() {
        val parent = Frame(0f, 0f, 1080f, 2400f)
        val hero = Frame(0f, 180f, 1080f, 1125f)
        val source = Frame(42f, 1570f, 382f, 1910f)
        val geometry = checkNotNull(SharedFrameMath.buildGeometry(source, parent, hero))
        val detailLocal = checkNotNull(SharedFrameMath.centerCropTransform(1600f, 900f, hero.width, hero.height))
        val sourceLocal = checkNotNull(SharedFrameMath.centerCropTransform(1600f, 900f, source.width, source.height))
        val sourceScreen = ImageTransform(
            sourceLocal.scale,
            source.left + sourceLocal.translationX,
            source.top + sourceLocal.translationY,
        )
        val dragTransforms = listOf(
            UniformTransform(.82f, -240f, 35f),
            UniformTransform(.82f, 240f, 35f),
            UniformTransform(.82f, 35f, 280f),
        )

        dragTransforms.forEach { dragged ->
            val visibleAtRelease = checkNotNull(
                SharedFrameMath.imageTransformInScreen(detailLocal, parent, hero, dragged)
            )
            val closingStart = checkNotNull(
                SharedFrameMath.localImageTransformForScreen(visibleAtRelease, parent, hero, dragged)
            )
            val remappedStart = checkNotNull(
                SharedFrameMath.imageTransformInScreen(closingStart, parent, hero, dragged)
            )
            val closingEnd = checkNotNull(
                SharedFrameMath.localImageTransformForScreen(
                    sourceScreen,
                    parent,
                    hero,
                    geometry.collapsedTransform,
                )
            )
            val remappedEnd = checkNotNull(
                SharedFrameMath.imageTransformInScreen(
                    closingEnd,
                    parent,
                    hero,
                    geometry.collapsedTransform,
                )
            )

            assertImageTransform(visibleAtRelease, remappedStart)
            assertImageTransform(sourceScreen, remappedEnd)
        }
    }

    @Test fun cropToFitInterpolationKeepsExactEndpointsAndFiniteMiddle() {
        val crop = checkNotNull(SharedFrameMath.centerCropTransform(1600f, 900f, 320f, 480f))
        val fit = checkNotNull(SharedFrameMath.centerFitTransform(1600f, 900f, 1080f, 720f))
        assertEquals(crop, SharedFrameMath.lerp(crop, fit, 0f))
        assertEquals(fit, SharedFrameMath.lerp(crop, fit, 1f))
        assertTrue(SharedFrameMath.lerp(crop, fit, .5f).isUsable())
    }

    @Test fun dragAndDismissUseConfiguredDirection() {
        // Compatibility overloads retain their original right-only behavior.
        assertEquals(1f, SharedFrameMath.dragTransform(-300f, 20f, 1000f).scale, .001f)
        assertEquals(.6f, SharedFrameMath.dragTransform(1200f, 20f, 1000f).scale, .001f)
        assertFalse(SharedFrameMath.shouldFinishDismiss(100f, 200f, 1000f, 1100f))
        assertTrue(SharedFrameMath.shouldFinishDismiss(260f, 200f, 1000f, 1100f))
    }

    @Test fun directionAwareDragSupportsLeftRightAndDown() {
        val allowed = setOf(
            SharedFrameDismissDirection.Left,
            SharedFrameDismissDirection.Right,
            SharedFrameDismissDirection.Down,
        )
        assertEquals(SharedFrameDismissDirection.Left, SharedFrameMath.resolveDismissDirection(-40f, 2f, 8f, allowed))
        assertEquals(SharedFrameDismissDirection.Right, SharedFrameMath.resolveDismissDirection(40f, 2f, 8f, allowed))
        assertEquals(SharedFrameDismissDirection.Down, SharedFrameMath.resolveDismissDirection(2f, 40f, 8f, allowed))
        assertNull(SharedFrameMath.resolveDismissDirection(2f, -40f, 8f, allowed))

        val left = SharedFrameMath.dragTransform(-300f, 15f, 1000f, 2000f, SharedFrameDismissDirection.Left)
        val right = SharedFrameMath.dragTransform(300f, 15f, 1000f, 2000f, SharedFrameDismissDirection.Right)
        val down = SharedFrameMath.dragTransform(15f, 300f, 1000f, 2000f, SharedFrameDismissDirection.Down)
        assertEquals(left.scale, right.scale, .001f)
        assertEquals(right.scale, down.scale, .001f)
        assertTrue(left.scale < 1f)
        assertTrue(SharedFrameMath.dragTransform(-600f, 0f, 1000f, 2000f, SharedFrameDismissDirection.Left).scale < left.scale)
    }

    @Test fun directionAwareDismissUsesShortEdgeAndOutwardVelocity() {
        val width = 1000f
        val height = 2000f
        fun finish(x: Float, y: Float, vx: Float, vy: Float, direction: SharedFrameDismissDirection) =
            SharedFrameMath.shouldFinishDismiss(x, y, vx, vy, width, height, direction, 700f, .15f)

        assertFalse(finish(-149f, 0f, -699f, 0f, SharedFrameDismissDirection.Left))
        assertTrue(finish(-150f, 0f, 0f, 0f, SharedFrameDismissDirection.Left))
        assertTrue(finish(-20f, 0f, -700f, 0f, SharedFrameDismissDirection.Left))
        assertFalse(finish(-20f, 0f, 900f, 0f, SharedFrameDismissDirection.Left))
        assertTrue(finish(150f, 0f, 0f, 0f, SharedFrameDismissDirection.Right))
        assertTrue(finish(0f, 150f, 0f, 0f, SharedFrameDismissDirection.Down))
        assertFalse(finish(0f, 20f, 0f, -900f, SharedFrameDismissDirection.Down))
    }

    @Test fun dragSlopIsRemovedWithoutAFirstFrameJump() {
        assertEquals(DragOffset(0f, 0f), SharedFrameMath.dragOffsetAfterSlop(8f, 0f, 8f))
        val offset = SharedFrameMath.dragOffsetAfterSlop(18f, 0f, 8f)
        assertEquals(10f, offset.x, .001f)
        assertEquals(0f, offset.y, .001f)
    }

    @Test fun invalidFramesAreRejected() {
        assertNull(SharedFrameMath.buildGeometry(Frame(0f, 0f, 0f, 10f), Frame(0f, 0f, 100f, 100f), Frame(0f, 0f, 20f, 20f)))
        assertNull(SharedFrameMath.centerCropTransform(0f, 10f, 20f, 20f))
    }

    @Test fun stateMachineRejectsOverlappingPhases() {
        val state = SharedFrameStateMachine()
        assertTrue(state.beginOpen())
        assertFalse(state.beginOpen())
        assertTrue(state.finishOpen())
        assertTrue(state.beginDrag())
        assertTrue(state.cancelDrag())
        assertTrue(state.finishCancel())
        assertTrue(state.beginClose())
        assertTrue(state.finishClose())
        assertEquals(SharedFramePhase.Hidden, state.phase)
    }

    private fun mapFrame(frame: Frame, parent: Frame, transform: UniformTransform): Frame {
        fun x(value: Float) = parent.centerX + (value - parent.centerX) * transform.scale + transform.translationX
        fun y(value: Float) = parent.centerY + (value - parent.centerY) * transform.scale + transform.translationY
        return Frame(x(frame.left), y(frame.top), x(frame.right), y(frame.bottom))
    }

    private fun assertFrame(expected: Frame, actual: Frame) {
        assertEquals(expected.left, actual.left, .001f)
        assertEquals(expected.top, actual.top, .001f)
        assertEquals(expected.right, actual.right, .001f)
        assertEquals(expected.bottom, actual.bottom, .001f)
    }

    private fun assertImageTransform(expected: ImageTransform, actual: ImageTransform) {
        assertEquals(expected.scale, actual.scale, .001f)
        assertEquals(expected.translationX, actual.translationX, .001f)
        assertEquals(expected.translationY, actual.translationY, .001f)
    }
}
