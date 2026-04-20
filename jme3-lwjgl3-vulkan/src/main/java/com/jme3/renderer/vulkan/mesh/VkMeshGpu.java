package com.jme3.renderer.vulkan.mesh;

import com.jme3.renderer.vulkan.resource.VkBuffer;
import com.jme3.scene.VertexBuffer;

import java.util.EnumMap;

/**
 * VkMeshGpu：一个 jME3 Mesh 在 GPU(Vulkan) 侧对应的资源集合。
 *
 * 作用：
 * - 把 jME3 的各个 VertexBuffer（Position/Color/Normal/UV...）映射成 Vulkan 的 VkBuffer。
 * - 可选地保存 index buffer（索引缓冲），用于 vkCmdDrawIndexed。
 *
 * 说明：
 * - 本类仅是“句柄/元数据容器”，不负责创建/销毁，生命周期通常由 VulkanRuntime/VkResourceFactory 管理。
 * - 当前最小实现一般只用 Position + Color（与你的 pipeline/shader 输入匹配），后续可扩展更多语义。
 * @author icyboxs
 */
public final class VkMeshGpu {

    /**
     * 顶点缓冲表：按 jME3 的 VertexBuffer.Type 索引到 Vulkan 的 VkBuffer。
     *
     * 例如：
     * - Type.Position -> 一个包含 vec3(float) 的 Vulkan vertex buffer
     * - Type.Color    -> 一个包含 vec3(float) 的 Vulkan vertex buffer
     *
     * 使用时：
     * - 你的 VulkanPipeline 的 vertex input 需要与这些 buffer 的 stride/format 对齐
     *   （比如 binding0=Position, binding1=Color）。
     */
    public final EnumMap<VertexBuffer.Type, VkBuffer> vbos =
            new EnumMap<>(VertexBuffer.Type.class);

    /**
     * 索引缓冲（可选）。
     *
     * - 如果为 null：表示 mesh 没有索引缓冲，使用 vkCmdDraw(vertexCount, ...)
     * - 如果非 null：表示 mesh 有索引缓冲，使用 vkCmdDrawIndexed(indexCount, ...)
     */
    public VkBuffer ibo;

    /**
     * index buffer 的索引数量（元素个数，不是字节数）。
     * 仅在 ibo != null 时有效，用作 vkCmdDrawIndexed 的 indexCount 参数。
     */
    public int indexCount;

    /**
     * 顶点数量（vertex count）。
     * - 对于非索引绘制：用作 vkCmdDraw 的 vertexCount 参数
     * - 对于索引绘制：仍可用于校验/调试，但绘制主要用 indexCount
     */
    public int vertexCount;

    /**
     * Vulkan 的索引类型（vkCmdBindIndexBuffer 的 indexType 参数）。
     *
     * 常见取值：
     * - VK_INDEX_TYPE_UINT16：对应 jME Format.UnsignedShort
     * - VK_INDEX_TYPE_UINT32：对应 jME Format.UnsignedInt
     * - VK_INDEX_TYPE_UINT8_EXT：对应 jME Format.UnsignedByte（需要 VK_EXT_index_type_uint8 扩展）
     *
     * 注意：
     * - 如果 mesh 使用 UnsignedByte 但未启用扩展，这里可能无法正确绑定/绘制。
     */
    public int vkIndexType; // VK_INDEX_TYPE_UINT16 / VK_INDEX_TYPE_UINT32 / VK_INDEX_TYPE_UINT8_EXT
}
