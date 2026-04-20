package com.jme3.renderer.vulkan.pipeline;

import java.util.Objects;

/**
 * Vulkan 后端自己的 shader 变体开关（不改变 jME API）。
 * 先做最小：HAS_COLORMAP / HAS_COLOR / HAS_LIGHTMAP
 */
public final class VkVariantKey {
    public final boolean hasColorMap;
    public final boolean hasColor;
    public final boolean hasLightMap;

    public VkVariantKey(boolean hasColorMap, boolean hasColor, boolean hasLightMap) {
        this.hasColorMap = hasColorMap;
        this.hasColor = hasColor;
        this.hasLightMap = hasLightMap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VkVariantKey)) return false;
        VkVariantKey that = (VkVariantKey) o;
        return hasColorMap == that.hasColorMap
                && hasColor == that.hasColor
                && hasLightMap == that.hasLightMap;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hasColorMap, hasColor, hasLightMap);
    }

    @Override
    public String toString() {
        return "VkVariantKey{hasColorMap=" + hasColorMap + ", hasColor=" + hasColor + ", hasLightMap=" + hasLightMap + '}';
    }
}
