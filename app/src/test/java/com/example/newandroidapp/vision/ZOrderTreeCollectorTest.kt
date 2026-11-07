package com.example.newandroidapp.vision

import android.graphics.Rect
import com.example.newandroidapp.e2e.TestHelpers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ZOrderTreeCollector recursion depth limit (maxDepth = 45)
 * and node count limit (maxNodes = 400).
 */
class ZOrderTreeCollectorTest {

    private class SyntheticNode(
        override val identityCode: Int,
        override val isVisibleToUser: Boolean = true,
        val rectLeft: Int = 0,
        val rectTop: Int = 0,
        val rectRight: Int = 1080,
        val rectBottom: Int = 2400,
        override val className: CharSequence? = "android.widget.FrameLayout",
        override val packageName: CharSequence? = "com.example.test",
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val hintText: CharSequence? = null,
        override val viewIdResourceName: String? = null,
        override val isClickable: Boolean = false,
        override val isLongClickable: Boolean = false,
        override val isCheckable: Boolean = false,
        override val isChecked: Boolean = false,
        override val isSelected: Boolean = false,
        override val isEditable: Boolean = false,
        override val isScrollable: Boolean = false,
        override val isEnabled: Boolean = true,
        override val drawingOrder: Int = 0,
        val children: MutableList<SyntheticNode> = ArrayList(),
        override val canScrollForwardOrBackward: Boolean = false
    ) : ZOrderTreeCollector.NodeHierarchySource {
        override fun getBoundsInScreen(outBounds: Rect) {
            outBounds.left = rectLeft
            outBounds.top = rectTop
            outBounds.right = rectRight
            outBounds.bottom = rectBottom
        }
        override val childCount: Int get() = children.size
        override fun getChild(index: Int): ZOrderTreeCollector.NodeHierarchySource? {
            return if (index in children.indices) children[index] else null
        }
    }

    @Test
    fun testDefaultLimitsConstants() {
        assertEquals(45, ZOrderTreeCollector.DEFAULT_MAX_DEPTH)
        assertEquals(400, ZOrderTreeCollector.DEFAULT_MAX_NODES)
    }

    @Test
    fun testRecursionDepthLimitEnforcedAt45() {
        // Build a linear chain of 70 deeply nested nodes (depth 0 to 69)
        val root = SyntheticNode(identityCode = 1, text = "Root Level 0")
        var current = root
        for (depth in 1..70) {
            val child = SyntheticNode(identityCode = depth + 1, text = "Level $depth")
            current.children.add(child)
            current = child
        }

        // Collect with default maxDepth = 45
        val collected = ZOrderTreeCollector.collectFromNodeSource(
            root = root,
            screenWidth = 1080,
            screenHeight = 2400,
            maxDepth = 45,
            maxNodes = 400
        )

        // Traversal should explore depth 0 up to depth 45, but NOT depth 46+
        // Total collected nodes should be exactly 46 (depth 0 to depth 45 inclusive)
        assertTrue("Collected node count should not exceed 46, was: ${collected.size}", collected.size <= 46)
        assertTrue("Collected should reach at least depth 45", collected.size >= 45)

        // Verify that deeper levels (depth 50, 60, 70) were not collected
        val texts = collected.map { it.text }
        assertTrue(texts.contains("Root Level 0"))
        assertTrue(texts.contains("Level 1"))
        assertTrue(texts.contains("Level 44"))
        assertFalse("Level 46 should be blocked by maxDepth = 45", texts.contains("Level 46"))
        assertFalse("Level 70 should be blocked by maxDepth = 45", texts.contains("Level 70"))
    }

    @Test
    fun testCustomRecursionDepthLimit() {
        // Build a chain of 20 nodes
        val root = SyntheticNode(identityCode = 100, text = "Node 0")
        var current = root
        for (i in 1..20) {
            val child = SyntheticNode(identityCode = 100 + i, text = "Node $i")
            current.children.add(child)
            current = child
        }

        // Limit depth to 5
        val collected = ZOrderTreeCollector.collectFromNodeSource(
            root = root,
            screenWidth = 1080,
            screenHeight = 2400,
            maxDepth = 5,
            maxNodes = 400
        )

        assertEquals(6, collected.size) // depth 0, 1, 2, 3, 4, 5
        val texts = collected.map { it.text }
        assertTrue(texts.contains("Node 5"))
        assertFalse(texts.contains("Node 6"))
    }

    @Test
    fun testNodeCountLimitEnforcedAt400() {
        // Build a wide tree with 600 sibling nodes under root
        val root = SyntheticNode(identityCode = 1000, text = "Wide Root")
        for (i in 1..600) {
            root.children.add(
                SyntheticNode(
                    identityCode = 1000 + i,
                    text = "Item $i",
                    isClickable = true,
                    rectLeft = 0,
                    rectTop = i % 1000,
                    rectRight = 500,
                    rectBottom = (i % 1000) + 50
                )
            )
        }

        // Collect with default maxNodes = 400
        val collected = ZOrderTreeCollector.collectFromNodeSource(
            root = root,
            screenWidth = 1080,
            screenHeight = 2400,
            maxDepth = 45,
            maxNodes = 400
        )

        assertEquals("Total collected nodes must be capped at maxNodes = 400", 400, collected.size)
    }

    @Test
    fun testCustomNodeCountLimit() {
        val root = SyntheticNode(identityCode = 2000, text = "Tree Root")
        for (i in 1..100) {
            root.children.add(
                SyntheticNode(
                    identityCode = 2000 + i,
                    text = "Child $i",
                    isClickable = true,
                    rectLeft = 10,
                    rectTop = 10,
                    rectRight = 200,
                    rectBottom = 200
                )
            )
        }

        val collected = ZOrderTreeCollector.collectFromNodeSource(
            root = root,
            screenWidth = 1080,
            screenHeight = 2400,
            maxDepth = 45,
            maxNodes = 30
        )

        assertEquals(30, collected.size)
    }

    @Test
    fun testZeroEmojiCompliance() {
        val root = SyntheticNode(identityCode = 9999, text = "標準控制台按鈕", contentDescription = "確定")
        val collected = ZOrderTreeCollector.collectFromNodeSource(
            root = root,
            screenWidth = 1080,
            screenHeight = 2400
        )
        for (node in collected) {
            TestHelpers.assertZeroEmojis(node.text, "node.text")
            TestHelpers.assertZeroEmojis(node.contentDescription, "node.contentDescription")
        }
    }
}
