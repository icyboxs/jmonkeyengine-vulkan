package com.jme3.renderer.vulkan.pipeline;

import java.util.Objects;

/**
 * PassKey：Dynamic Rendering 的 RenderPass 等价物（当前阶段先恒定）。
 * 以后 MRT/后处理会让它变成多样 key。
 */
public final class PassKey {

    public final int colorCount;
    public final int colorFormat; // VkFormat
    public final int depthFormat; // VkFormat or VK_FORMAT_UNDEFINED
    public final int samples;     // VkSampleCountFlagBits

    public PassKey(int colorCount, int colorFormat, int depthFormat, int samples) {
        this.colorCount = colorCount;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.samples = samples;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PassKey)) return false;
        PassKey passKey = (PassKey) o;
        return colorCount == passKey.colorCount
                && colorFormat == passKey.colorFormat
                && depthFormat == passKey.depthFormat
                && samples == passKey.samples;
    }

    @Override
    public int hashCode() {
        return Objects.hash(colorCount, colorFormat, depthFormat, samples);
    }

    @Override
    public String toString() {
        return "PassKey{colorCount=" + colorCount
                + ", colorFormat=" + colorFormat
                + ", depthFormat=" + depthFormat
                + ", samples=" + samples
                + '}';
    }
}
