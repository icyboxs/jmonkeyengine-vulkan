package com.jme3.renderer.vulkan.resource;

import static org.lwjgl.vulkan.VK10.*;

public final class VkOffscreenTarget {
    public VkTexture color;   // 复用现有 VkTexture 结构 (image/memory/view/...)
    public VkDepthResources depth;

    public int width, height;

    public int colorLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    public int depthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
}
