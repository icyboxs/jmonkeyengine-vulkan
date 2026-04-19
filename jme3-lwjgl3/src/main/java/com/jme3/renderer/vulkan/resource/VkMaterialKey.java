package com.jme3.renderer.vulkan.resource;

import java.util.Objects;

/** 材质key：由 imageView + sampler 显式组成（不要再从 VkTexture.sampler 隐式取） */
public final class VkMaterialKey {
    public final long tex0View;
    public final long tex0Sampler;
    public final long lightView;
    public final long lightSampler;

    public VkMaterialKey(long tex0View, long tex0Sampler, long lightView, long lightSampler) {
        this.tex0View = tex0View;
        this.tex0Sampler = tex0Sampler;
        this.lightView = lightView;
        this.lightSampler = lightSampler;
    }

    public static VkMaterialKey of(VkTexture tex0, long sampler0, VkTexture light, long samplerLight) {
        long v0 = (tex0 != null) ? tex0.view : 0L;
        long v1 = (light != null) ? light.view : 0L;
        return new VkMaterialKey(v0, sampler0, v1, samplerLight);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VkMaterialKey)) return false;
        VkMaterialKey that = (VkMaterialKey) o;
        return tex0View == that.tex0View
                && tex0Sampler == that.tex0Sampler
                && lightView == that.lightView
                && lightSampler == that.lightSampler;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tex0View, tex0Sampler, lightView, lightSampler);
    }
}
