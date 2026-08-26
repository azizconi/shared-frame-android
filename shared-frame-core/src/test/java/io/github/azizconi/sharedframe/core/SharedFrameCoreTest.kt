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

    @Test fun cropToFitInterpolationKeepsExactEndpointsAndFiniteMiddle() {
        val crop = checkNotNull(SharedFrameMath.centerCropTransform(1600f, 900f, 320f, 480f))
        val fit = checkNotNull(SharedFrameMath.centerFitTransform(1600f, 900f, 1080f, 720f))
        assertEquals(crop, SharedFrameMath.lerp(crop, fit, 0f))
        assertEquals(fit, SharedFrameMath.lerp(crop, fit, 1f))
        assertTrue(SharedFrameMath.lerp(crop, fit, .5f).isUsable())
    }

    @Test fun dragAndDismissUseConfiguredDirection() {
        assertEquals(1f, SharedFrameMath.dragTransform(-300f, 20f, 1000f).scale, .001f)
        assertEquals(.6f, SharedFrameMath.dragTransform(1200f, 20f, 1000f).scale, .001f)
        assertFalse(SharedFrameMath.shouldFinishDismiss(100f, 200f, 1000f, 1100f))
        assertTrue(SharedFrameMath.shouldFinishDismiss(260f, 200f, 1000f, 1100f))
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
}
