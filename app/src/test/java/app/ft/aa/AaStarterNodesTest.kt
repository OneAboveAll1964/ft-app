package app.ft.aa

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AaStarterNodesTest {
    private fun node(text: String? = null, pkg: String = AaInstaller.GEARHEAD, window: Int = 7): AccessibilityNodeInfo =
        mock(AccessibilityNodeInfo::class.java).also {
            `when`(it.packageName).thenReturn(pkg)
            `when`(it.windowId).thenReturn(window)
            `when`(it.isVisibleToUser).thenReturn(true)
            `when`(it.isEnabled).thenReturn(true)
            `when`(it.text).thenReturn(text)
        }

    private fun children(root: AccessibilityNodeInfo, vararg children: AccessibilityNodeInfo) {
        `when`(root.childCount).thenReturn(children.size)
        children.forEachIndexed { index, child -> `when`(root.getChild(index)).thenReturn(child) }
    }

    @Test fun findsEnglishAndRussianExactLabels() {
        for ((start, stop) in listOf("Start head unit server" to "Stop head unit server",
            "Запустить сервер головного устройства" to "Остановить сервер головного устройства")) {
            val root = node()
            val first = node(start)
            val second = node(stop)
            children(root, first, second, node("$start?"))
            val menu = AaStarterNodes.menu(root, emptySet())
            assertSame(first, menu.start)
            assertSame(second, menu.stop)
        }
    }

    @Test fun foreignRootIsNotTraversed() {
        val root = node(pkg = "com.android.settings")
        children(root, node("Start head unit server"))
        assertNull(AaStarterNodes.menu(root, emptySet()).start)
        verify(root, never()).getChild(anyInt())
    }

    @Test fun foreignWindowHiddenAndDisabledItemsAreIgnored() {
        val root = node()
        val hidden = node("Start head unit server")
        `when`(hidden.isVisibleToUser).thenReturn(false)
        val disabled = node("Start head unit server")
        `when`(disabled.isEnabled).thenReturn(false)
        children(root, node("Start head unit server", pkg = "another.app"),
            node("Start head unit server", window = 8), hidden, disabled)
        assertNull(AaStarterNodes.menu(root, emptySet()).start)
    }

    @Test fun visibleButtonsInsideHiddenOrDisabledContainersRemainDiscoverable() {
        for (hidden in listOf(true, false)) {
            val root = node()
            val container = node()
            `when`(container.isVisibleToUser).thenReturn(!hidden)
            `when`(container.isEnabled).thenReturn(hidden)
            val button = node()
            `when`(button.contentDescription).thenReturn("Другие параметры")
            children(root, container)
            children(container, button)
            assertSame(button, AaStarterNodes.menu(root, AaStarterNodes.DEFAULT_OVERFLOW_LABELS).overflow)
        }
    }

    @Test fun everyKnownOverflowLabelIsRecognizedWithoutAaResourceNames() {
        for (label in AaStarterNodes.DEFAULT_OVERFLOW_LABELS) {
            val root = node()
            val button = node()
            `when`(button.contentDescription).thenReturn(label)
            children(root, button)
            assertSame(label, button, AaStarterNodes.menu(root, AaStarterNodes.DEFAULT_OVERFLOW_LABELS).overflow)
        }
    }

    @Test fun traversalNeverCrossesForeignPackageOrWindowContainers() {
        for (container in listOf(node(pkg = "another.app"), node(window = 8))) {
            val root = node()
            children(root, container)
            children(container, node("Start head unit server"))
            assertNull(AaStarterNodes.menu(root, emptySet()).start)
            verify(container, never()).getChild(anyInt())
        }
    }

    @Test fun clicksOnlyClickableParentInSamePackageAndWindow() {
        val item = node("Start head unit server")
        val parent = node()
        `when`(item.parent).thenReturn(parent)
        `when`(parent.isClickable).thenReturn(true)
        `when`(parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        assertTrue(AaStarterNodes.click(item, 7))
        verify(item, never()).performAction(anyInt())
        verify(parent).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @Test fun parentOutsideAaOrWindowCannotBeClicked() {
        for (parent in listOf(node(pkg = "another.app"), node(window = 8))) {
            val item = node()
            `when`(item.parent).thenReturn(parent)
            `when`(parent.isClickable).thenReturn(true)
            assertFalse(AaStarterNodes.click(item, 7))
            verify(parent, never()).performAction(anyInt())
        }
    }

    @Test fun rejectedActionDoesNotClickHigherAncestor() {
        val item = node()
        val parent = node()
        `when`(item.isClickable).thenReturn(true)
        `when`(item.parent).thenReturn(parent)
        assertFalse(AaStarterNodes.click(item, 7))
        verify(item).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        verify(parent, never()).performAction(anyInt())
    }

    @Test fun cyclicParentChainIsBounded() {
        val item = node()
        `when`(item.parent).thenReturn(item)
        assertFalse(AaStarterNodes.click(item, 7))
        verify(item, times(8)).getParent()
    }

    @Test fun overflowUsesKnownLabelOrSpecificClass() {
        val root = node()
        val button = node()
        children(root, button)
        `when`(button.contentDescription).thenReturn("Ещё")
        assertSame(button, AaStarterNodes.menu(root, setOf("Ещё")).overflow)
        `when`(button.contentDescription).thenReturn(null)
        `when`(button.className).thenReturn("androidx.appcompat.widget.ActionMenuPresenter.OverflowMenuButton")
        assertSame(button, AaStarterNodes.menu(root, emptySet()).overflow)
    }
}
