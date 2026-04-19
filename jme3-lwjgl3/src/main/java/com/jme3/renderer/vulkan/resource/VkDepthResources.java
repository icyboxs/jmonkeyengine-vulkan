package com.jme3.renderer.vulkan.resource;

/**
 * VkDepthResources：深度缓冲相关资源集合。
 *
 * 功能：封装深度缓冲相关的Vulkan对象
 * 原理：将深度图像、内存和视图组合在一起
 *
 * 深度缓冲通常需要三个对象：
 * 1) VkImage：深度图像本体
 * 2) VkDeviceMemory：图像内存
 * 3) VkImageView：渲染管线/Framebuffer 绑定时使用的视图
 */
public final class VkDepthResources {
    public long image;   // VkImage
    public long memory;  // VkDeviceMemory
    public long view;    // VkImageView
}