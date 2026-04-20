package com.jme3.renderer.vulkan.shader;

import java.nio.ByteBuffer;

/**
 * S2-T2: shader artifact
 * 持有：shader module + SPIR-V + 源hash + stage
 * @author icyboxs
 */
public final class ShaderArtifact {
    public final long module;
    public final ByteBuffer spirv; // read-only buffer
    public final int sourceHash;
    public final int stage;

    public ShaderArtifact(long module, ByteBuffer spirv, int sourceHash, int stage) {
        this.module = module;
        this.spirv = spirv;
        this.sourceHash = sourceHash;
        this.stage = stage;
    }

    @Override
    public String toString() {
        return "ShaderArtifact{module=" + module
                + ", sourceHash=" + sourceHash
                + ", stage=" + stage
                + ", spirvBytes=" + (spirv != null ? spirv.remaining() : 0)
                + '}';
    }
}
