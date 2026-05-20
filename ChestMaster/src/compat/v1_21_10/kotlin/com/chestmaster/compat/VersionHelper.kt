package com.chestmaster.compat

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation

object VersionHelper {
    fun getLinesBuffer(consumers: MultiBufferSource): VertexConsumer =
        consumers.getBuffer(RenderType.LINES)

    fun resourceLocation(namespace: String, path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(namespace, path)
}
