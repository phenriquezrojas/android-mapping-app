package com.example.lazyreps.nanoleaf

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

enum class LayoutType(val id: Int) {
    GRID(0),
    DIAMOND(1),
    WAVE(2),
    ORBITAL(3)
}

data class NodePosition(val x: Float, val y: Float)

class SpatialGraphModel {

    /**
     * Generates a list of positions for the Nanoleaf panels.
     * Enforces the auto-scaling and identity preservation contract (u_panelCount < 16).
     */
    fun generateLayout(type: LayoutType, panelCount: Int): List<NodePosition> {
        // Enforce max 16 panels, auto-scale handling if < 16
        val count = panelCount.coerceIn(1, 16)
        
        return when (type) {
            LayoutType.GRID -> generateGrid(count)
            LayoutType.DIAMOND -> generateDiamond(count)
            LayoutType.WAVE -> generateWave(count)
            LayoutType.ORBITAL -> generateOrbital(count)
        }
    }

    private fun generateGrid(count: Int): List<NodePosition> {
        val positions = mutableListOf<NodePosition>()
        // Auto-scale to form the most compact square grid
        val cols = Math.ceil(Math.sqrt(count.toDouble())).toInt()
        val spacing = 0.3f
        
        for (i in 0 until count) {
            val row = i / cols
            val col = i % cols
            // Center the grid around (0,0)
            val cx = (col - (cols - 1) / 2f) * spacing
            val cy = (row - (Math.ceil(count.toDouble() / cols) - 1) / 2f) * spacing
            positions.add(NodePosition(cx, cy.toFloat()))
        }
        return positions
    }

    private fun generateDiamond(count: Int): List<NodePosition> {
        val positions = mutableListOf<NodePosition>()
        val spacing = 0.25f
        
        if (count > 0) positions.add(NodePosition(0f, 0f))
        
        var ring = 1
        var added = 1
        
        while (added < count) {
            for (i in 0 until (4 * ring)) {
                if (added >= count) break
                
                val edge = i / ring
                val step = i % ring
                
                var x = 0f
                var y = 0f
                
                when (edge) {
                    0 -> { x = ring - step.toFloat(); y = step.toFloat() } // right to top
                    1 -> { x = -step.toFloat(); y = ring - step.toFloat() } // top to left
                    2 -> { x = -(ring - step.toFloat()); y = -step.toFloat() } // left to bottom
                    3 -> { x = step.toFloat(); y = -(ring - step.toFloat()) } // bottom to right
                }
                
                positions.add(NodePosition(x * spacing, y * spacing))
                added++
            }
            ring++
        }
        
        return positions
    }

    private fun generateWave(count: Int): List<NodePosition> {
        val positions = mutableListOf<NodePosition>()
        val spacing = 0.25f
        val amplitude = 0.4f
        
        for (i in 0 until count) {
            // Distribute along x axis centered
            val x = (i - (count - 1) / 2f) * spacing
            val frequency = 2.0 * PI / (count.toFloat().coerceAtLeast(1f)) * 2.0 // 2 waves
            val y = sin(i.toFloat() * frequency) * amplitude
            
            positions.add(NodePosition(x, y.toFloat()))
        }
        return positions
    }

    private fun generateOrbital(count: Int): List<NodePosition> {
        val positions = mutableListOf<NodePosition>()
        if (count == 0) return positions
        
        if (count == 1) {
            positions.add(NodePosition(0f, 0f))
            return positions
        }
        
        positions.add(NodePosition(0f, 0f)) // Core node
        
        val circleCount = count - 1
        val radius = 0.5f
        
        for (i in 0 until circleCount) {
            val angle = (i.toFloat() / circleCount) * 2.0 * PI
            val x = cos(angle) * radius
            val y = sin(angle) * radius
            positions.add(NodePosition(x.toFloat(), y.toFloat()))
        }
        
        return positions
    }
}
