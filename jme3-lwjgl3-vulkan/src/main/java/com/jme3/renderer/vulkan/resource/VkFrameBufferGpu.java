package com.jme3.renderer.vulkan.resource;

import java.util.ArrayList;
import java.util.List;

/**
 * jME3 FrameBuffer 在 Vulkan 侧的 GPU 资源容器。
 *
 * 在 Dynamic Rendering 架构下，我们不需要创建 Vulkan 原生的 VkFramebuffer 和 VkRenderPass 句柄。
 * 但是我们需要为 FrameBuffer 中的“非贴图附件（纯 RenderBuffer，如深度缓冲或多重采样缓冲）”分配 VkImage 和 VkDeviceMemory。
 * 这个类用于持有这些专属资源，以便在 deleteFrameBuffer 时统一安全地释放。
 *
 * 注：贴图类型 (Texture) 附件的生命周期由 VulkanTextureManager 独立管理，不在此列表内。
 */
public final class VkFrameBufferGpu {
    // 专门存储那些没有对应 JME3 Texture 的纯底层颜色缓冲（例如 MSAA 缓冲）
    public final List<VkTexture> internalColorBuffers = new ArrayList<>();
    
    // 独占的深度/模板缓冲（没有绑定到 Texture）
    public VkDepthResources internalDepthBuffer;

    public int width;
    public int height;
}