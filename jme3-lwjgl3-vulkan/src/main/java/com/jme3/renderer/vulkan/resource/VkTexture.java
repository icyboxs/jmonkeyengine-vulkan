package com.jme3.renderer.vulkan.resource;

public final class VkTexture {
    public long image;
    public long memory;
    public long view;
    public long sampler;
    public int width;
    public int height;
    
    // 初始化时都置为 shader_read_only_optimal
    public int imageLayout = org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
}