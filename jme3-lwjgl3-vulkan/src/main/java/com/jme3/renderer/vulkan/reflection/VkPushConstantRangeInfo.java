package com.jme3.renderer.vulkan.reflection;

import java.util.Objects;

/**
 * 反射得到的 push constant range 信息（独立 DTO）。
 */
public final class VkPushConstantRangeInfo {
    public final int offset;
    public final int size;
    public final int stageFlags;

    public VkPushConstantRangeInfo(int offset, int size, int stageFlags) {
        this.offset = offset;
        this.size = size;
        this.stageFlags = stageFlags;
    }

    @Override
    public String toString() {
        return "VkPushConstantRangeInfo{offset=" + offset
                + ", size=" + size
                + ", stageFlags=" + stageFlags + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VkPushConstantRangeInfo)) return false;
        VkPushConstantRangeInfo that = (VkPushConstantRangeInfo) o;
        return offset == that.offset
                && size == that.size
                && stageFlags == that.stageFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(offset, size, stageFlags);
    }
}
