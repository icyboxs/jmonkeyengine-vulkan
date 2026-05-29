package com.jme3.renderer.vulkan.cmd;

import com.jme3.shader.Shader;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.TextureImage;
import com.jme3.renderer.vulkan.pipeline.VkComputePipelineKey;

public final class ComputeCmd {
    private static final ComputeCmd[] POOL = new ComputeCmd[256];
    private static int poolPtr = -1;

    public int groupX, groupY, groupZ;
    public Shader shader;

    public final BufferObject[] ssbos = new BufferObject[16];
    public final TextureImage[] images = new TextureImage[16];

    public final float[] uboData = new float[256];
    public int uboDynamicOffset;

    public String compSrc, compDefines, finalCompSrc;
    public int compHash;
    public VkComputePipelineKey pipelineKey;

    public static ComputeCmd acquire() {
        synchronized (POOL) {
            if (poolPtr >= 0) return POOL[poolPtr--];
        }
        return new ComputeCmd();
    }

    public void recycle() {
        shader = null;
        compSrc = compDefines = finalCompSrc = null;
        pipelineKey = null;
        uboDynamicOffset = 0;
        for (int i = 0; i < 16; i++) {
            ssbos[i] = null;
            images[i] = null;
        }
        synchronized (POOL) {
            if (poolPtr < POOL.length - 1) POOL[++poolPtr] = this;
        }
    }

    public boolean isRenderable() {
        return pipelineKey != null && finalCompSrc != null;
    }
}