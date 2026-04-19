package com.jme3.renderer.vulkan.binding.api;

import com.jme3.renderer.vulkan.binding.api.DescriptorBindRequest;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindResult;

/**
 * 统一 descriptor 绑定器接口。
 * @author icyboxs
 */
public interface DescriptorSetBinder {

    /**
     * 根据请求生成本次 draw 应绑定的 descriptor sets。
     */
    DescriptorBindResult bindForDraw(DescriptorBindRequest req);

    /**
     * 每帧开始时可选调用（做按帧缓存清理等）。
     */
    default void beginFrame(int frameIndex) {
    }

    /**
     * 释放内部缓存。
     */
    default void cleanup() {
    }
}
