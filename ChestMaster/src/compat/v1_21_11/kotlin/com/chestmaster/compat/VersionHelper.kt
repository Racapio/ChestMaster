package com.chestmaster.compat

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.resources.Identifier

object VersionHelper {
    fun getLinesBuffer(consumers: MultiBufferSource): VertexConsumer =
        consumers.getBuffer(RenderTypes.LINES)

    fun resourceLocation(namespace: String, path: String): Identifier =
        Identifier.fromNamespaceAndPath(namespace, path)
}
