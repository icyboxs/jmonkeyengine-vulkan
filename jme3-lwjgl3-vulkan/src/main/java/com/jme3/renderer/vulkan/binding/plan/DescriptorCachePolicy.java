package com.jme3.renderer.vulkan.binding.plan;

/**
 * Descriptor 缓存策略。
 * @author icyboxs
 */
public enum DescriptorCachePolicy {
    /** 跨帧持久缓存（典型：材质） */
    PERSISTENT,

    /** 按 frame slot 缓存（frame pool reset 后失效） */
    PER_FRAME,

    /** 不缓存，每次分配/写入 */
    NONE
}
