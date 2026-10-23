package com.example.ipa_board

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test

/** Events target only the test-owned editor; no system clipboard/editor or external app is used. */
class PopupActionEditorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val initial = PopupConfiguration(
        listOf(PopupPoint(-80f, -80f), PopupPoint(80f, -80f), PopupPoint(80f, 80f), PopupPoint(-80f, 80f)),
        PopupPoint(0f, 40f)
    )

    @Test fun addSelectedItemDragAndHistoryLeaveAnchorAndBoundaryUnchanged() = withEditor(initial) { fixture ->
        fixture.ui { editor, _ -> button(editor, "Add Item").performClick() }
        fixture.ui { editor, viewport ->
            val center = editor.configuration.items.single().center
            drag(fixture, viewport, center, PopupPoint(center.x + 40f, center.y - 20f))
            assertEquals(PopupPoint(40f, 20f), editor.configuration.items.single().center)
            assertEquals(initial.anchor, editor.configuration.anchor)
            assertEquals(initial.vertices, editor.configuration.vertices)
            button(editor, "Undo").performClick()
            assertEquals(center, editor.configuration.items.single().center)
            button(editor, "Redo").performClick()
            assertEquals(PopupPoint(40f, 20f), editor.configuration.items.single().center)
        }
    }

    @Test fun itemSelectionTakesPriorityOverReferencePointAndDoubleTapDeletesTopmostOnly() {
        val overlap = initial.copy(items = listOf(
            PopupItem(initial.anchor, 20f, LongPressItem("lower")),
            PopupItem(initial.anchor, 24f, LongPressItem("upper"))
        ))
        withEditor(overlap) { fixture ->
            fixture.tap(initial.anchor)
            fixture.confirmTap()
            fixture.ui { editor, viewport ->
                drag(fixture, viewport, initial.anchor, PopupPoint(25f, 40f))
                assertEquals(initial.anchor, editor.configuration.anchor)
                assertEquals(initial.anchor, editor.configuration.items.first().center)
                assertEquals(PopupPoint(25f, 40f), editor.configuration.items.last().center)
            }
            fixture.tap(PopupPoint(25f, 40f))
            fixture.tap(PopupPoint(25f, 40f))
            fixture.ui { editor, _ ->
                assertEquals(listOf(overlap.items.first()), editor.configuration.items)
                button(editor, "Undo").performClick()
                assertEquals(2, editor.configuration.items.size)
            }
        }
    }

    @Test fun selectedItemBindingAndLayerDialogSaveUsesLinearTopmostOrder() {
        val lower = PopupItem(PopupPoint(-40f, 0f), 20f, LongPressItem("old"))
        val upper = PopupItem(PopupPoint(40f, 0f), 20f, LongPressItem("other"))
        withEditor(initial.copy(items = listOf(lower, upper))) { fixture ->
            fixture.tap(lower.center)
            fixture.confirmTap()
            fixture.tap(lower.center)
            fixture.confirmTap()
            onView(withContentDescription("Popup item text")).inRoot(isDialog()).perform(replaceText("bound"), closeSoftKeyboard())
            onView(withText("Bring to Front")).inRoot(isDialog()).perform(click())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            fixture.ui { editor, _ ->
                assertEquals(listOf(upper, lower.copy(binding = LongPressItem("bound"))), editor.configuration.items)
                button(editor, "Undo").performClick()
                assertEquals(listOf(lower, upper), editor.configuration.items)
            }
        }
    }

    @Test fun shapeConstraintDoesNotReshapeUntilDragAndDistortOnlyMovesChosenVertex() = withEditor(initial) { fixture ->
        fixture.tap(PopupPoint(0f, 0f))
        fixture.confirmTap()
        fixture.ui { editor, _ ->
            val shapes = descendants(editor).filterIsInstance<Spinner>().single()
            assertEquals(View.VISIBLE, shapes.visibility)
            shapes.setSelection(PopupShapeMode.DISTORT.ordinal)
        }
        instrumentation.waitForIdleSync()
        fixture.ui { editor, viewport ->
            assertEquals(initial.vertices, editor.configuration.vertices)
            drag(fixture, viewport, initial.vertices.first(), PopupPoint(-60f, -60f))
            assertOutline(listOf(PopupPoint(-60f, -60f)), listOf(editor.configuration.vertices.first()))
            assertEquals(initial.vertices.drop(1), editor.configuration.vertices.drop(1))
            assertEquals(initial.anchor, editor.configuration.anchor)
            button(editor, "Undo").performClick()
            assertEquals(initial.vertices, editor.configuration.vertices)
        }
    }

    @Test fun uniformDragScalesWarpedOutlineWithoutMovingItsItemsOrReferencePoint() {
        val warped = warped(PopupShapeMode.UNIFORM)
        withEditor(warped) { fixture ->
            fixture.tap(PopupPoint(0f, -20f)); fixture.confirmTap()
            fixture.ui { editor, viewport ->
                val fixed = warped.vertices.first()
                val corner = warped.vertices[2]
                drag(fixture, viewport, corner, PopupPoint(fixed.x + (corner.x - fixed.x) * .5f, fixed.y + (corner.y - fixed.y) * .5f))
                val expected = warped.vertices.map { PopupPoint(fixed.x + (it.x - fixed.x) * .5f, fixed.y + (it.y - fixed.y) * .5f) }
                assertOutline(expected, editor.configuration.vertices)
                assertEquals(warped.items, editor.configuration.items)
                assertEquals(warped.anchor, editor.configuration.anchor)
            }
        }
    }

    @Test fun freeformDragIndependentlyScalesWarpedOutlineWithoutRegularizingIt() {
        val warped = warped(PopupShapeMode.FREEFORM)
        withEditor(warped) { fixture ->
            fixture.tap(PopupPoint(0f, -20f)); fixture.confirmTap()
            fixture.ui { editor, viewport ->
                val fixed = warped.vertices.first()
                val corner = warped.vertices[2]
                drag(fixture, viewport, corner, PopupPoint(fixed.x + (corner.x - fixed.x) * .5f, fixed.y + (corner.y - fixed.y) * 1.2f))
                val expected = warped.vertices.map { PopupPoint(fixed.x + (it.x - fixed.x) * .5f, fixed.y + (it.y - fixed.y) * 1.2f) }
                assertOutline(expected, editor.configuration.vertices)
                assertNotEquals(editor.configuration.vertices[0].y, editor.configuration.vertices[1].y)
                assertNotEquals(editor.configuration.vertices[1].x, editor.configuration.vertices[2].x)
                assertEquals(warped.items, editor.configuration.items)
                assertEquals(warped.anchor, editor.configuration.anchor)
            }
        }
    }

    @Test fun rotationUsesFourVertexMeanAndDoesNotRotateItemsOrReferencePoint() {
        val warped = warped(PopupShapeMode.ROTATE)
        withEditor(warped) { fixture ->
            fixture.tap(PopupPoint(0f, -20f)); fixture.confirmTap()
            fixture.ui { editor, viewport ->
                // This outline's vertex mean differs from both its area centroid and reference point.
                val center = PopupPoint(5f, -5f)
                val corner = warped.vertices[2]
                val destination = PopupPoint(center.x - (corner.y - center.y), center.y + (corner.x - center.x))
                drag(fixture, viewport, corner, destination)
                val expected = warped.vertices.map { PopupPoint(center.x - (it.y - center.y), center.y + (it.x - center.x)) }
                assertOutline(expected, editor.configuration.vertices)
                assertEquals(warped.items, editor.configuration.items)
                assertEquals(warped.anchor, editor.configuration.anchor)
            }
        }
    }

    @Test fun selectedReferenceDoubleTapCentersWithoutFirstRunningTheDeselectAction() {
        val warped = warped(PopupShapeMode.DISTORT).copy(items = emptyList())
        withEditor(warped) { fixture ->
            fixture.tap(warped.anchor); fixture.confirmTap()
            fixture.tap(warped.anchor)
            fixture.tap(warped.anchor)
            val centered = PopupGeometry.centroid(warped)
            fixture.ui { editor, viewport ->
                assertEquals(centered, editor.configuration.anchor)
                // Immediately dragging the centered crosshair proves the conflicting single tap
                // did not deselect it before the double tap's center operation.
                drag(fixture, viewport, centered, PopupPoint(centered.x + 20f, centered.y))
                assertEquals(PopupPoint(centered.x + 20f, centered.y), editor.configuration.anchor)
                assertEquals(warped.vertices, editor.configuration.vertices)
            }
        }
    }

    private fun warped(mode: PopupShapeMode) = initial.copy(
        vertices = listOf(PopupPoint(-60f, -70f), PopupPoint(70f, -40f), PopupPoint(50f, 60f), PopupPoint(-40f, 30f)),
        items = listOf(PopupItem(PopupPoint(30f, 0f), 10f, LongPressItem("x"))), shapeMode = mode)

    private fun assertOutline(expected: List<PopupPoint>, actual: List<PopupPoint>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (before, after) ->
            assertEquals(before.x, after.x, .002f)
            assertEquals(before.y, after.y, .002f)
        }
    }

    @Test fun popupDoubleTapRegularizesOnlyBoundaryAndCanBeUndone() {
        val warped = initial.copy(vertices = listOf(PopupPoint(-80f, -70f), PopupPoint(50f, -50f), PopupPoint(60f, 60f), PopupPoint(-30f, 20f)),
            items = listOf(PopupItem(PopupPoint(25f, 15f), 10f, LongPressItem("x"))))
        withEditor(warped) { fixture ->
            fixture.tap(PopupPoint(-20f, -20f))
            fixture.tap(PopupPoint(-20f, -20f))
            fixture.ui { editor, _ ->
                val squared = editor.configuration
                assertNotEquals(warped.vertices, squared.vertices)
                assertEquals(squared.vertices[0].y, squared.vertices[1].y, .001f)
                assertEquals(squared.vertices[1].x, squared.vertices[2].x, .001f)
                assertEquals(squared.vertices[1].x - squared.vertices[0].x, squared.vertices[2].y - squared.vertices[1].y, .001f)
                assertEquals(warped.items, squared.items)
                assertEquals(warped.anchor, squared.anchor)
                button(editor, "Undo").performClick()
                assertEquals(warped.vertices, editor.configuration.vertices)
            }
        }
    }

    @Test fun emptyViewportPanAndDoubleTapRecenterDoNotChangeDraft() = withEditor(initial) { fixture ->
        fixture.ui { editor, viewport ->
            drag(fixture, viewport, PopupPoint(130f, 120f), PopupPoint(160f, 130f))
            assertEquals(initial, editor.configuration)
        }
        fixture.tap(PopupPoint(160f, 130f))
        fixture.tap(PopupPoint(160f, 130f))
        fixture.ui { editor, _ -> assertEquals(initial, editor.configuration) }
        // After double-tap reset, the reference point is under the original center again.
        fixture.tap(initial.anchor)
        fixture.confirmTap()
        fixture.ui { editor, viewport ->
            drag(fixture, viewport, initial.anchor, PopupPoint(20f, 40f))
            assertEquals(PopupPoint(20f, 40f), editor.configuration.anchor)
        }
    }

    @Test fun selectedItemPinchChangesCircleButNotCenterOrBindingAndResetHasSeparateUndo() {
        val item = PopupItem(PopupPoint(0f, 0f), 20f, LongPressItem("😀"))
        withEditor(initial.copy(items = listOf(item))) { fixture ->
            fixture.tap(item.center)
            fixture.confirmTap()
            var edited: PopupConfiguration? = null
            fixture.ui { editor, viewport ->
                val center = fixture.screen(item.center)
                val d = viewport.resources.displayMetrics.density
                val down = fixture.nextTime()
                event(viewport, down, down, MotionEvent.ACTION_DOWN, center.first, center.second)
                multi(viewport, down, fixture.nextTime(), MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    center.first - 10f * d, center.second, center.first + 10f * d, center.second)
                multi(viewport, down, fixture.nextTime(), MotionEvent.ACTION_MOVE,
                    center.first - 20f * d, center.second, center.first + 20f * d, center.second)
                event(viewport, down, fixture.nextTime(), MotionEvent.ACTION_UP, center.first, center.second)
                assertEquals(40f, editor.configuration.items.single().radius, .1f)
                assertEquals(item.center, editor.configuration.items.single().center)
                assertEquals(item.binding, editor.configuration.items.single().binding)
                edited = editor.configuration
                editor.resetConfiguration = initial
                button(editor, "Reset").performClick()
                assertEquals(initial, editor.configuration)
            }
            onView(withId(com.google.android.material.R.id.snackbar_action)).perform(awaitFullyDisplayed(), click())
            fixture.ui { editor, _ -> assertEquals(edited, editor.configuration) }
        }
    }

    /** Observe the actual entry animation; retain Espresso's normal click visibility requirement. */
    private fun awaitFullyDisplayed() = object : ViewAction {
        override fun getConstraints(): Matcher<View> = allOf(
            isAssignableFrom(Button::class.java), withEffectiveVisibility(Visibility.VISIBLE))
        override fun getDescription() = "wait for the owned control to finish entering the visible area"
        override fun perform(uiController: UiController, view: View) {
            val visible = isDisplayingAtLeast(90)
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (!visible.matches(view) && SystemClock.uptimeMillis() < deadline) {
                uiController.loopMainThreadForAtLeast(16)
            }
            check(visible.matches(view)) {
                val bounds = android.graphics.Rect()
                view.getGlobalVisibleRect(bounds)
                "Owned control did not become usable before the deadline: size=${view.width}x${view.height}, visible=$bounds"
            }
        }
    }

    private fun withEditor(configuration: PopupConfiguration, block: (Fixture) -> Unit) {
        PageGroupTestState(instrumentation.targetContext).use {
            ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                lateinit var editor: PopupActionEditorView
                scenario.onActivity { activity ->
                    editor = PopupActionEditorView(activity, configuration)
                    activity.setContentView(editor)
                }
                instrumentation.waitForIdleSync()
                val fixture = Fixture(scenario, editor, configuration.anchor)
                block(fixture)
            }
        }
    }

    private inner class Fixture(val scenario: ActivityScenario<KeyboardPageActivity>, val editor: PopupActionEditorView, val anchor: PopupPoint) {
        private var time = SystemClock.uptimeMillis()
        private val viewport get() = descendants(editor).first { it.contentDescription == "Popup editor viewport" }
        fun nextTime(): Long { time = maxOf(SystemClock.uptimeMillis(), time + 16); return time }
        fun screen(point: PopupPoint): Pair<Float, Float> =
            (viewport.width / 2f + (point.x - anchor.x) * viewport.resources.displayMetrics.density) to
                (viewport.height / 2f + (point.y - anchor.y) * viewport.resources.displayMetrics.density)
        fun ui(action: (PopupActionEditorView, View) -> Unit) = scenario.onActivity { action(editor, viewport) }
        fun tap(point: PopupPoint) = ui { _, view ->
            val p = screen(point); val down = nextTime()
            event(view, down, down, MotionEvent.ACTION_DOWN, p.first, p.second)
            event(view, down, nextTime(), MotionEvent.ACTION_UP, p.first, p.second)
        }
        fun confirmTap() { SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 40); instrumentation.waitForIdleSync() }
    }

    private fun drag(fixture: Fixture, view: View, from: PopupPoint, to: PopupPoint) {
        val start = fixture.screen(from); val end = fixture.screen(to); val down = fixture.nextTime()
        event(view, down, down, MotionEvent.ACTION_DOWN, start.first, start.second)
        event(view, down, fixture.nextTime(), MotionEvent.ACTION_MOVE, end.first, end.second)
        event(view, down, fixture.nextTime(), MotionEvent.ACTION_UP, end.first, end.second)
    }

    private fun event(view: View, down: Long, time: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(down, time, action, x, y, 0).let { try { view.dispatchTouchEvent(it) } finally { it.recycle() } }
    }

    private fun multi(view: View, down: Long, time: Long, action: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 1f },
            MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f })
        MotionEvent.obtain(down, time, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0).let {
            try { view.dispatchTouchEvent(it) } finally { it.recycle() }
        }
    }

    private fun button(root: View, label: String) = descendants(root).filterIsInstance<Button>().single { it.text == label }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
