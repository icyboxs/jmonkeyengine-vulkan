package com.jme3.renderer.vulkan.reflection;

import java.util.Objects;

public final class VkReflectionCacheKey {
    public final int vertSpvHash;
    public final int fragSpvHash;

    public VkReflectionCacheKey(int vertSpvHash, int fragSpvHash) {
        this.vertSpvHash = vertSpvHash;
        this.fragSpvHash = fragSpvHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VkReflectionCacheKey)) return false;
        VkReflectionCacheKey that = (VkReflectionCacheKey) o;
        return vertSpvHash == that.vertSpvHash && fragSpvHash == that.fragSpvHash;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vertSpvHash, fragSpvHash);
    }

    @Override
    public String toString() {
        return "VkReflectionCacheKey{vert=" + vertSpvHash + ", frag=" + fragSpvHash + '}';
    }
}
