/*
 * Through-walls filled chest highlight with a cycling rainbow colour, modelled on SkyOcean's
 * ItemHighlighter/RenderUtils.renderBox (https://github.com/meowdding/SkyOcean).
 * Copyright (c) meowdding / SkyOcean contributors, MIT License — see THIRD_PARTY_NOTICES.md.
 */
package com.chestmaster.highlight

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import java.awt.Color

/**
 * Version-independent geometry for the in-world chest markers. The fill is meant for
 * `RenderTypes.textBackgroundSeeThrough()` (POSITION_COLOR_LIGHTMAP quads, no depth test),
 * so the marker stays visible through walls like SkyOcean's.
 */
object HighlightGeometry {
    private const val FULL_BRIGHT = 0x00F000F0
    private const val RAINBOW_PERIOD_MS = 4000L

    // Slightly larger than the block so the faces don't z-fight with the chest itself.
    const val INFLATE = 0.002f

    /**
     * Adds the other half of every double chest, so a double chest lights up as a whole
     * (items are stored per half, but highlighting just one half looks broken).
     */
    fun withDoubleChestHalves(positions: List<BlockPos>): List<BlockPos> {
        val level = Minecraft.getInstance().level ?: return positions
        val result = LinkedHashSet<BlockPos>()
        for (pos in positions) {
            result += pos
            val state = level.getBlockState(pos)
            if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                result += pos.relative(ChestBlock.getConnectedDirection(state)).immutable()
            }
        }
        return result.toList()
    }

    /** ARGB colour cycling through the hue wheel, shared by fill and outline. */
    fun rainbow(alpha: Int, offset: Float = 0f): Int {
        val hue = ((System.currentTimeMillis() % RAINBOW_PERIOD_MS) / RAINBOW_PERIOD_MS.toFloat() + offset) % 1f
        val rgb = Color.HSBtoRGB(hue, 0.85f, 1f) and 0x00FFFFFF
        return (alpha.coerceIn(0, 255) shl 24) or rgb
    }

    /** Six quads of an axis-aligned box. */
    fun fillBox(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        argb: Int
    ) {
        // Bottom / top
        quad(pose, buffer, argb, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2)
        quad(pose, buffer, argb, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1)
        // North / south
        quad(pose, buffer, argb, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1)
        quad(pose, buffer, argb, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2)
        // West / east
        quad(pose, buffer, argb, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1)
        quad(pose, buffer, argb, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2)
    }

    private fun quad(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        argb: Int,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float
    ) {
        buffer.addVertex(pose, ax, ay, az).setColor(argb).setLight(FULL_BRIGHT)
        buffer.addVertex(pose, bx, by, bz).setColor(argb).setLight(FULL_BRIGHT)
        buffer.addVertex(pose, cx, cy, cz).setColor(argb).setLight(FULL_BRIGHT)
        buffer.addVertex(pose, dx, dy, dz).setColor(argb).setLight(FULL_BRIGHT)
    }
}
