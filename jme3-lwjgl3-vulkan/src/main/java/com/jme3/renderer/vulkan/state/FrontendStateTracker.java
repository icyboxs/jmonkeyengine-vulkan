package com.jme3.renderer.vulkan.state;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.vulkan.cmd.RendererStateSnapshot;
import com.jme3.shader.Shader;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Texture;

import java.util.Arrays;

/**
 * 负责收集和缓存来自 jME3 前端 (Renderer 接口) 的状态变更。
 * @author icyboxs
 */
public final class FrontendStateTracker {

    public static final int MAX_TEXTURE_UNITS = 16;
    public static final int UNIT_TEX0 = 0;
    public static final int UNIT_LIGHT = 1;
    public static final int UNIT_EXTRA = 2;

    private final Texture[] currentTextures = new Texture[MAX_TEXTURE_UNITS];

    // 清屏与背景色
    private final ColorRGBA background = ColorRGBA.Black.clone();
    private boolean clearColor = true;
    private boolean clearDepth = true;
    private boolean clearStencil = false;

    // Viewport & Scissor (ClipRect)
    private int vpX = 0, vpY = 0, vpW = -1, vpH = -1;
    private boolean clipEnabled = false;
    private int clipX = 0, clipY = 0, clipW = 0, clipH = 0;

    // 核心渲染状态
    private Shader currentShader;
    private RenderState currentRenderState;
    private Material currentMaterial;
    private FrameBuffer currentFb;
    private final Matrix4f currentViewProj = new Matrix4f();

    public void reset() {
        vpX = 0; vpY = 0; vpW = -1; vpH = -1;
        clipEnabled = false;
        clipX = 0; clipY = 0; clipW = 0; clipH = 0;

        clearTextureUnits();
        currentShader = null;
        currentRenderState = null;
        currentMaterial = null;
        currentFb = null;
    }

    // --- State Setters ---

    public void setViewPort(int x, int y, int width, int height) {
        this.vpX = x; this.vpY = y; this.vpW = width; this.vpH = height;
    }

    public void setClipRect(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            this.clipEnabled = false;
            this.clipX = this.clipY = this.clipW = this.clipH = 0;
            return;
        }
        this.clipEnabled = true;
        this.clipX = x; this.clipY = y; this.clipW = width; this.clipH = height;
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
        if (color != null) background.set(color);
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

    // --- State Getters ---

    public ColorRGBA getBackground() { return background; }
    public int getVpX() { return vpX; }
    public int getVpY() { return vpY; }
    public int getVpW() { return vpW; }
    public int getVpH() { return vpH; }
    public boolean isClipEnabled() { return clipEnabled; }
    public int getClipX() { return clipX; }
    public int getClipY() { return clipY; }
    public int getClipW() { return clipW; }
    public int getClipH() { return clipH; }
    public FrameBuffer getCurrentFb() { return currentFb; }

    /**
     * 生成当前绘制命令所需的状态快照
     */
    public RendererStateSnapshot createSnapshot() {
        return RendererStateSnapshot.of(
                currentShader,
                currentRenderState,
                currentTextures[UNIT_TEX0],
                currentTextures[UNIT_LIGHT],
                currentTextures[UNIT_EXTRA],
                currentMaterial
        );
    }
}