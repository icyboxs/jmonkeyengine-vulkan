package com.jme3.renderer.vulkan.cmd;

import com.jme3.renderer.vulkan.pipeline.VkVariantKey;
import com.jme3.renderer.vulkan.resource.VkTexture;

import java.util.Objects;

/**
 * A2-3: 稳定的材质批处理 key（用于排序/分组）。
 *
 * 设计原则：
 * - 不使用 jME Texture 对象 identity（可能每帧变化）
 * - 使用真正影响 descriptor 的 Vk 侧要素：imageView + sampler
 * - 可选加入 shader variant，避免错误把不同 define 的 pipeline 混到一起
 */
public final class MaterialBatchKey {

    public final long tex0View;
    public final long tex0Sampler;
    public final long lightView;
    public final long lightSampler;

    public final VkVariantKey variant;

    public MaterialBatchKey(long tex0View, long tex0Sampler,
                             long lightView, long lightSampler,
                             VkVariantKey variant) {
        this.tex0View = tex0View;
        this.tex0Sampler = tex0Sampler;
        this.lightView = lightView;
        this.lightSampler = lightSampler;
        this.variant = variant;
    }

    public static MaterialBatchKey of(VkTexture tex0, long samp0,
                                      VkTexture light, long sampL,
                                      VkVariantKey variant) {
        long v0 = (tex0 != null) ? tex0.view : 0L;
        long v1 = (light != null) ? light.view : 0L;
        return new MaterialBatchKey(v0, samp0, v1, sampL, variant);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaterialBatchKey)) return false;
        MaterialBatchKey that = (MaterialBatchKey) o;
        return tex0View == that.tex0View
                && tex0Sampler == that.tex0Sampler
                && lightView == that.lightView
                && lightSampler == that.lightSampler
                && Objects.equals(variant, that.variant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tex0View, tex0Sampler, lightView, lightSampler, variant);
    }

    @Override
    public String toString() {
        return "MaterialBatchKey{"
                + "tex0View=" + tex0View
                + ", tex0Sampler=" + tex0Sampler
                + ", lightView=" + lightView
                + ", lightSampler=" + lightSampler
                + ", variant=" + variant
                + '}';
    }
}
