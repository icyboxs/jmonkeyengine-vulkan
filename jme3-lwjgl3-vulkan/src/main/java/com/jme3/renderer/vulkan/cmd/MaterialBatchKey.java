package com.jme3.renderer.vulkan.cmd;

import com.jme3.renderer.vulkan.pipeline.VkVariantKey;
import java.util.Arrays;
import java.util.Objects;

/**
 * 稳定的全动态材质批处理 Key（用于在 DrawQueue 进行 DrawCall 合并）。
 */
public final class MaterialBatchKey {

    public final long[] views;
    public final long[] samplers;
    public final VkVariantKey variant;

    public MaterialBatchKey(long[] views, long[] samplers, VkVariantKey variant) {
        this.views = views;
        this.samplers = samplers;
        this.variant = variant;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaterialBatchKey)) return false;
        MaterialBatchKey that = (MaterialBatchKey) o;
        return Arrays.equals(views, that.views)
                && Arrays.equals(samplers, that.samplers)
                && Objects.equals(variant, that.variant);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(variant);
        result = 31 * result + Arrays.hashCode(views);
        result = 31 * result + Arrays.hashCode(samplers);
        return result;
    }

    @Override
    public String toString() {
        return "MaterialBatchKey{"
                + "viewsCount=" + (views != null ? views.length : 0)
                + ", variant=" + variant
                + '}';
    }
}