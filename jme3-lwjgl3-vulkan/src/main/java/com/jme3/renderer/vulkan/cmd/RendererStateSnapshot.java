package com.jme3.renderer.vulkan.cmd;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.shader.Shader;
import com.jme3.texture.Texture;

public final class RendererStateSnapshot {
    public Shader shader;
    public RenderState renderState;
    public Texture tex0;
    public Texture light;
    public Texture extra;
    public Matrix4f wvp;
    public ColorRGBA color;
    public Material material;

    public final com.jme3.shader.bufferobject.BufferObject[] ssbos = new com.jme3.shader.bufferobject.BufferObject[16];
    public final com.jme3.texture.TextureImage[] images = new com.jme3.texture.TextureImage[16];

    public boolean alphaToCoverage;
    public int vpX, vpY, vpW, vpH;
    public boolean clipEnabled;
    public int clipX, clipY, clipW, clipH;
    public float depthRangeStart;
    public float depthRangeEnd;
    
    public RendererStateSnapshot() {}
}