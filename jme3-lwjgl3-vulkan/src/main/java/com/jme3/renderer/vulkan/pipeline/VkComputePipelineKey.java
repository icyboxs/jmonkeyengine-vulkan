package com.jme3.renderer.vulkan.pipeline;

import java.util.Objects;

public final class VkComputePipelineKey {
    public final int compHash;

    public VkComputePipelineKey(int compHash) {
        this.compHash = compHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VkComputePipelineKey)) return false;
        return compHash == ((VkComputePipelineKey) o).compHash;
    }

    @Override
    public int hashCode() {
        return compHash;
    }
}