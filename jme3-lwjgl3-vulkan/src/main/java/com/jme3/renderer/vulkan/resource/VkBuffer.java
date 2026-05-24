package com.jme3.renderer.vulkan.resource;

/**
 * VkBuffer：一个简单的数据结构，保存 Vulkan buffer 句柄和其绑定的 VkDeviceMemory。
 *
 * 功能：封装Vulkan缓冲区和内存句柄
 * 原理：将相关的Vulkan对象组合在一起便于管理
 *
 * Vulkan 中的 Buffer 与 Memory 是两个对象：
 * - VkBuffer：描述"缓冲区对象"（用途、大小等）
 * - VkDeviceMemory：实际分配的 GPU/CPU 可见内存
 * - vkBindBufferMemory：把 memory 绑定到 buffer
 *
 * 这里把二者放一起便于管理生命周期。
 */
public final class VkBuffer {
    /** VkBuffer 句柄（vkCreateBuffer 返回） */
    public long handle;

    /** VkDeviceMemory 句柄（vkAllocateMemory 返回，并绑定到 handle） */
    public long memory;
    public long capacity;     // 【新增容量记录】
    public boolean isHostVisible; // 【新增可见性】
}