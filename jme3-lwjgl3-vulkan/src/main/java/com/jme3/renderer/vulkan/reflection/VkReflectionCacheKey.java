package com.jme3.renderer.vulkan.reflection;

import java.util.Objects;

public final class VkReflectionCacheKey {
    public final int vertSpvHash;
    public final int fragSpvHash;
    public final int compSpvHash;

    public VkReflectionCacheKey(int vertSpvHash, int fragSpvHash, int compSpvHash) {
        this.vertSpvHash = vertSpvHash;
        this.fragSpvHash = fragSpvHash;
        this.compSpvHash = compSpvHash;
    }

    public VkReflectionCacheKey(int vertSpvHash, int fragSpvHash) {
        this(vertSpvHash, fragSpvHash, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VkReflectionCacheKey)) return false;
        VkReflectionCacheKey that = (VkReflectionCacheKey) o;
        return vertSpvHash == that.vertSpvHash && fragSpvHash == that.fragSpvHash && compSpvHash == that.compSpvHash;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vertSpvHash, fragSpvHash, compSpvHash);
    }

    @Override
    public String toString() {
        return "VkReflectionCacheKey{vert=" + vertSpvHash + ", frag=" + fragSpvHash + ", comp=" + compSpvHash + '}';
    }
}