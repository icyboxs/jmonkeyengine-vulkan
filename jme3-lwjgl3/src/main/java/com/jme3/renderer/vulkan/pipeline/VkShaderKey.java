package com.jme3.renderer.vulkan.pipeline;

import java.util.Objects;

public final class VkShaderKey {

    public final int vertHash;
    public final int fragHash;

    /** Vulkan 后端额外的变体开关（决定 adapter 注入的 defines） */
    public final VkVariantKey variant;

    public VkShaderKey(int vertHash, int fragHash, VkVariantKey variant) {
        this.vertHash = vertHash;
        this.fragHash = fragHash;
        this.variant = variant;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VkShaderKey)) return false;
        VkShaderKey that = (VkShaderKey) o;
        return vertHash == that.vertHash
                && fragHash == that.fragHash
                && Objects.equals(variant, that.variant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vertHash, fragHash, variant);
    }

    @Override
    public String toString() {
        return "VkShaderKey{vertHash=" + vertHash + ", fragHash=" + fragHash + ", variant=" + variant + '}';
    }
}
