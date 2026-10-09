package com.chestmaster.highlight

import com.chestmaster.ChestMasterMod
import com.chestmaster.compat.VersionHelper
import com.chestmaster.util.WorldUtils
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos

object ChestLocationHighlighter {
    @Volatile
    private var activePositions: List<BlockPos> = emptyList()

    @Volatile
    private var lastWorldContextKey: String? = null

    fun init() {
        LevelRenderEvents.BEFORE_GIZMOS.register(LevelRenderEvents.BeforeGizmos { context ->
            val positions = activePositions
            if (positions.isEmpty()) return@BeforeGizmos

            val cameraPos = context.gameRenderer().getMainCamera().position()
            val matrices = context.poseStack()
            val e = HighlightGeometry.INFLATE

            matrices.pushPose()
            matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z)

            // 1) Translucent rainbow fill, visible through walls (SkyOcean-style).
            val fill = context.bufferSource().getBuffer(RenderTypes.textBackgroundSeeThrough())
            val fillColor = HighlightGeometry.rainbow(alpha = 110)
            for (pos in positions) {
                HighlightGeometry.fillBox(
                    matrices.last(), fill,
                    pos.x - e, pos.y - e, pos.z - e,
                    pos.x + 1 + e, pos.y + 1 + e, pos.z + 1 + e,
                    fillColor
                )
            }

            // 2) Crisp outline on top, same hue.
            val outline = HighlightGeometry.rainbow(alpha = 255)
            val lines = VersionHelper.getLinesBuffer(context.bufferSource())
            for (pos in positions) {
                renderBox(
                    matrices, lines,
                    pos.x - e, pos.y - e, pos.z - e,
                    pos.x + 1 + e, pos.y + 1 + e, pos.z + 1 + e,
                    ((outline shr 16) and 0xFF) / 255f, ((outline shr 8) and 0xFF) / 255f, (outline and 0xFF) / 255f, 1f
                )
            }

            matrices.popPose()
        })
    }

    fun getActiveMarkerCount(): Int = activePositions.size

    fun clear() {
        activePositions = emptyList()
    }

    fun onClientTick(client: Minecraft) {
        val contextKey = getWorldContextKey(client)
        val previousContextKey = lastWorldContextKey
        if (previousContextKey == null) {
            lastWorldContextKey = contextKey
        } else if (previousContextKey != contextKey) {
            if (activePositions.isNotEmpty()) {
                if (ChestMasterMod.isVerboseLogging()) {
                    ChestMasterMod.LOGGER.debug(
                        "Clearing chest markers due to world/server change: '$previousContextKey' -> '$contextKey'"
                    )
                }
                clear()
            }
            lastWorldContextKey = contextKey
        }
    }

    fun highlight(itemName: String, positions: List<BlockPos>): Int {
        if (positions.isEmpty()) {
            clear()
            return 0
        }

        val unique = HighlightGeometry.withDoubleChestHalves(positions.distinct())
        activePositions = unique

        if (ChestMasterMod.isVerboseLogging()) {
            ChestMasterMod.LOGGER.debug("Highlighting ${unique.size} chest marker(s) for $itemName")
        }

        return unique.size
    }

    private fun renderBox(
        matrices: PoseStack,
        buffer: VertexConsumer,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        // Bottom face edges
        line(matrices, buffer, x1, y1, z1, x2, y1, z1, r, g, b, a)
        line(matrices, buffer, x2, y1, z1, x2, y1, z2, r, g, b, a)
        line(matrices, buffer, x2, y1, z2, x1, y1, z2, r, g, b, a)
        line(matrices, buffer, x1, y1, z2, x1, y1, z1, r, g, b, a)
        // Top face edges
        line(matrices, buffer, x1, y2, z1, x2, y2, z1, r, g, b, a)
        line(matrices, buffer, x2, y2, z1, x2, y2, z2, r, g, b, a)
        line(matrices, buffer, x2, y2, z2, x1, y2, z2, r, g, b, a)
        line(matrices, buffer, x1, y2, z2, x1, y2, z1, r, g, b, a)
        // Vertical edges
        line(matrices, buffer, x1, y1, z1, x1, y2, z1, r, g, b, a)
        line(matrices, buffer, x2, y1, z1, x2, y2, z1, r, g, b, a)
        line(matrices, buffer, x2, y1, z2, x2, y2, z2, r, g, b, a)
        line(matrices, buffer, x1, y1, z2, x1, y2, z2, r, g, b, a)
    }

    private fun line(
        matrices: PoseStack,
        buffer: VertexConsumer,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float,
        lineWidth: Float = 2.5f
    ) {
        val pose = matrices.last()
        val ri = (r * 255).toInt()
        val gi = (g * 255).toInt()
        val bi = (b * 255).toInt()
        val ai = (a * 255).toInt()
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        // MC 26.x LINES format: POSITION_COLOR_NORMAL_LINE_WIDTH — setLineWidth() is required.
        buffer.addVertex(pose, x1, y1, z1).setColor(ri, gi, bi, ai).setNormal(pose, dx, dy, dz).setLineWidth(lineWidth)
        buffer.addVertex(pose, x2, y2, z2).setColor(ri, gi, bi, ai).setNormal(pose, dx, dy, dz).setLineWidth(lineWidth)
    }

    private fun getWorldContextKey(client: Minecraft): String {
        val serverKey = WorldUtils.getCurrentServerKey()
        val level = client.level
        val levelKey = if (level == null) {
            "level:none"
        } else {
            val dimension = level.dimension().toString()
            "level:$dimension@${System.identityHashCode(level)}"
        }
        return "$serverKey|$levelKey"
    }
}
