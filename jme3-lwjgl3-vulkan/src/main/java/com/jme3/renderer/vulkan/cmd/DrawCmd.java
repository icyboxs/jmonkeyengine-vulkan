package com.jme3.renderer.vulkan.cmd;

import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.renderer.vulkan.pipeline.VkVariantKey;
import com.jme3.renderer.vulkan.reflection.ParamBindingPlan;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;

public final class DrawCmd {

    private static final DrawCmd[] POOL = new DrawCmd[65536];
    private static int poolPtr = -1;

    public int vpX, vpY, vpW, vpH;
    public boolean clipEnabled;
    public int clipX, clipY, clipW, clipH;
    public float depthRangeStart, depthRangeEnd;
    
    public static DrawCmd acquire() {
        synchronized (POOL) { if (poolPtr >= 0) return POOL[poolPtr--]; }
        return new DrawCmd();
    }

    public void recycle() {
        mesh = null; instanceData = null; renderState = null;
        vertSrc = null; fragSrc = null; finalVertSrc = null; finalFragSrc = null;
        jmeTex0Snapshot = null; jmeLightSnapshot = null; jmeExtraSnapshot = null;
        materialKeySnapshot = null; materialResolvePlan = null;
        variant = null; pipelineKey = null; materialBatchKey = null;
        for (int i = 0; i < customImageCount; i++) { customImageSlots[i] = null; customImageTextures[i] = null; }
        customImageCount = 0;
        
        for (int i = 0; i < 16; i++) { ssbos[i] = null; images[i] = null; }

        vpX = 0; vpY = 0; vpW = 0; vpH = 0; clipEnabled = false; clipX = 0; clipY = 0; clipW = 0; clipH = 0;
        depthRangeStart = 0f; depthRangeEnd = 0f;
        
        synchronized (POOL) { if (poolPtr < POOL.length - 1) POOL[++poolPtr] = this; }
    }

    public Mesh mesh;
    public int lod;
    public int count;
    public VertexBuffer[] instanceData;
    public RenderState renderState;

    public String vertSrc, fragSrc, vertDefines, fragDefines, finalVertSrc, finalFragSrc;
    public int vertHash, fragHash;

    public Texture jmeTex0Snapshot, jmeLightSnapshot, jmeExtraSnapshot;
    public MaterialSnapshotKey materialKeySnapshot;
    public MaterialResolvePlan materialResolvePlan;
    public boolean useWhiteTex0, useWhiteLight, useWhiteExtra;

    public final com.jme3.shader.bufferobject.BufferObject[] ssbos = new com.jme3.shader.bufferobject.BufferObject[16];
    public final com.jme3.texture.TextureImage[] images = new com.jme3.texture.TextureImage[16];

    public final Matrix4f wvpSnapshot = new Matrix4f();
    public final ColorRGBA colorSnapshot = new ColorRGBA();
    public final float[] uboData = new float[256];

    public VkVariantKey variant;
    public VkPipelineKey pipelineKey;

    public int uboDynamicOffset;
    public MaterialBatchKey materialBatchKey;
    public int objectId;
    public int submissionIndex;
    public long sortKey;

    public long tex0SamplerSnapshot, lightSamplerSnapshot, extraSamplerSnapshot;
    public final ParamBindingPlan.BindingSlot[] customImageSlots = new ParamBindingPlan.BindingSlot[16];
    public final Texture[] customImageTextures = new Texture[16];
    public int customImageCount = 0;

    public boolean isRenderable() {
        return pipelineKey != null && mesh != null && finalVertSrc != null && finalFragSrc != null;
    }
}