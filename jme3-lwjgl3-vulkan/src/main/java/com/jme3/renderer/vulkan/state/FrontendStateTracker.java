package com.jme3.renderer.vulkan.state;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.vulkan.cmd.RendererStateSnapshot;
import com.jme3.shader.Shader;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureImage;

import java.util.Arrays;

public final class FrontendStateTracker {

    public static final int MAX_TEXTURE_UNITS = 16;
    public static final int UNIT_TEX0 = 0;
    public static final int UNIT_LIGHT = 1;
    public static final int UNIT_EXTRA = 2;

    private final Texture[] currentTextures = new Texture[MAX_TEXTURE_UNITS];
    private final BufferObject[] currentSsbos = new BufferObject[16];
    private final TextureImage[] currentImages = new TextureImage[16];

    private final ColorRGBA background = ColorRGBA.Black.clone();
    private boolean clearColor = true;
    private boolean clearDepth = true;
    private boolean clearStencil = false;

    private int vpX = 0, vpY = 0, vpW = -1, vpH = -1;
    private boolean clipEnabled = false;
    private int clipX = 0, clipY = 0, clipW = 0, clipH = 0;

    private Shader currentShader;
    private RenderState currentRenderState;
    private Material currentMaterial;
    private FrameBuffer currentFb;
    private final Matrix4f currentViewProj = new Matrix4f();

    private boolean alphaToCoverage = false;
    private float depthRangeStart = 0f;
    private float depthRangeEnd = 1f;

    public void setDepthRange(float start, float end) {
        this.depthRangeStart = start;
        this.depthRangeEnd = end;
    }

    public float getDepthRangeStart() {
        return depthRangeStart;
    }

    public float getDepthRangeEnd() {
        return depthRangeEnd;
    }

    public void setAlphaToCoverage(boolean value) {
        this.alphaToCoverage = value;
    }

    public boolean getAlphaToCoverage() {
        return alphaToCoverage;
    }

    public void reset() {
        vpX = 0;
        vpY = 0;
        vpW = -1;
        vpH = -1;
        clipEnabled = false;
        clipX = 0;
        clipY = 0;
        clipW = 0;
        clipH = 0;

        clearTextureUnits();
        Arrays.fill(currentSsbos, null);
        Arrays.fill(currentImages, null);

        currentShader = null;
        currentRenderState = null;
        currentMaterial = null;
        currentFb = null;
        alphaToCoverage = false;
        depthRangeStart = 0f;
        depthRangeEnd = 1f;
    }

    public void setViewPort(int x, int y, int width, int height) {
        this.vpX = x;
        this.vpY = y;
        this.vpW = width;
        this.vpH = height;
    }

    public void setClipRect(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            this.clipEnabled = false;
            this.clipX = this.clipY = this.clipW = this.clipH = 0;
            return;
        }
        this.clipEnabled = true;
        this.clipX = x;
        this.clipY = y;
        this.clipW = width;
        this.clipH = height;
    }

    public void clearClipRect() {
        this.clipEnabled = false;
    }

    public void setClearBuffers(boolean color, boolean depth, boolean stencil) {
        this.clearColor = color;
        this.clearDepth = depth;
        this.clearStencil = stencil;
    }

    public void setBackgroundColor(ColorRGBA color) {
        if (color != null) {
            background.set(color);
        }
    }

    public void applyRenderState(RenderState state) {
        this.currentRenderState = state;
    }

    public void setShader(Shader shader) {
        this.currentShader = shader;
    }

    public void setTexture(int unit, Texture tex) {
        if (unit >= 0 && unit < MAX_TEXTURE_UNITS) {
            currentTextures[unit] = tex;
        }
    }

    public void setExtraTexture(Texture tex) {
        currentTextures[UNIT_EXTRA] = tex;
    }

    public void clearTextureUnits() {
        Arrays.fill(currentTextures, null);
    }

    public void setFrameBuffer(FrameBuffer fb) {
        this.currentFb = fb;
    }

    public void setCurrentMaterial(Material material) {
        this.currentMaterial = material;
    }

    public void setViewProjectionMatrices(Matrix4f viewMatrix, Matrix4f projMatrix) {
        if (viewMatrix != null && projMatrix != null) {
            projMatrix.mult(viewMatrix, currentViewProj);
        }
    }

    public void setShaderStorageBufferObject(int bindingPoint, BufferObject bo) {
        if (bindingPoint >= 0 && bindingPoint < 16) {
            currentSsbos[bindingPoint] = bo;
        }
    }

    public void setTextureImage(int unit, TextureImage tex) {
        if (unit >= 0 && unit < 16) {
            currentImages[unit] = tex;
        }
    }

    public ColorRGBA getBackground() {
        return background;
    }

    public Shader getCurrentShader() {
        return currentShader;
    }

    public int getVpX() {
        return vpX;
    }

    public int getVpY() {
        return vpY;
    }

    public int getVpW() {
        return vpW;
    }

    public int getVpH() {
        return vpH;
    }

    public boolean isClipEnabled() {
        return clipEnabled;
    }

    public int getClipX() {
        return clipX;
    }

    public int getClipY() {
        return clipY;
    }

    public int getClipW() {
        return clipW;
    }

    public int getClipH() {
        return clipH;
    }

    public FrameBuffer getCurrentFb() {
        return currentFb;
    }

    public RendererStateSnapshot createSnapshot() {
        RendererStateSnapshot s = new RendererStateSnapshot();
        s.shader = currentShader;
        s.renderState = currentRenderState;
        s.tex0 = currentTextures[UNIT_TEX0];
        s.light = currentTextures[UNIT_LIGHT];
        s.extra = currentTextures[UNIT_EXTRA];
        s.material = currentMaterial;
        s.alphaToCoverage = alphaToCoverage;
        s.vpX = vpX;
        s.vpY = vpY;
        s.vpW = vpW;
        s.vpH = vpH;
        s.clipEnabled = clipEnabled;
        s.clipX = clipX;
        s.clipY = clipY;
        s.clipW = clipW;
        s.clipH = clipH;
        s.depthRangeStart = depthRangeStart;
        s.depthRangeEnd = depthRangeEnd;

        System.arraycopy(currentSsbos, 0, s.ssbos, 0, 16);
        System.arraycopy(currentImages, 0, s.images, 0, 16);
        return s;
    }
}
