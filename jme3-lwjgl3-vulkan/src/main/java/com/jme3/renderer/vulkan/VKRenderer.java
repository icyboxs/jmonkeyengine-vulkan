package com.jme3.renderer.vulkan;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.*;
import com.jme3.renderer.vulkan.binding.impl.DefaultDescriptorSetBinder;
import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.cmd.DrawCmdBuilder;
import com.jme3.renderer.vulkan.cmd.RendererStateSnapshot;
import com.jme3.renderer.vulkan.frame.DefaultFrameRecorder;
import com.jme3.renderer.vulkan.frame.DrawExecutor;
import com.jme3.renderer.vulkan.frame.VulkanFrameInfo;
import com.jme3.renderer.vulkan.queue.DrawQueue;
import com.jme3.renderer.vulkan.state.FrontendStateTracker;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;

import com.jme3.shader.Shader;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureImage;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author icyboxs
 */
public final class VKRenderer implements Renderer, VkCommandRecorder {

    private static final Logger LOGGER = Logger.getLogger(VKRenderer.class.getName());

    private final VulkanRuntime runtime;

    private final Statistics stats = new Statistics();
    private final EnumSet<Caps> caps = EnumSet.noneOf(Caps.class);
    private final EnumMap<Limits, Integer> limits = new EnumMap<>(Limits.class);

    private boolean initialized = false;

    // --- 子系统 ---
    private final FrontendStateTracker stateTracker = new FrontendStateTracker();
    private final DrawQueue drawQueue = new DrawQueue();
    
    private DrawCmdBuilder drawCmdBuilder;
    private DrawExecutor drawExecutor;
    private DefaultFrameRecorder frameRecorder;

    public VKRenderer(VulkanRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void initialize() {
        if (initialized) {
            return;
        }

        runtime.ensureInitialized();
        
        drawCmdBuilder = new DrawCmdBuilder(runtime);
        
        DefaultDescriptorSetBinder binder = new DefaultDescriptorSetBinder(
                runtime,
                new com.jme3.renderer.vulkan.binding.plan.FrequencyLayerRegistry(),
                new com.jme3.renderer.vulkan.binding.cache.FrameSetCache(),
                new com.jme3.renderer.vulkan.binding.provider.ObjectHighBindingProvider(runtime)
        );
        
        drawExecutor = new DrawExecutor(binder);
        frameRecorder = new DefaultFrameRecorder(stateTracker, drawQueue, drawExecutor);

        initCapsAndLimits();
        initialized = true;

        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("LwjglVKRenderer initialized. Caps=" + caps + ", limits=" + limits);
        }
    }

    private void initCapsAndLimits() {
        caps.clear();
        limits.clear();
        safeAddCap(Caps.GLSL310);
        safeAddCap(Caps.GLSL330);
        safeAddCap(Caps.GLSL400);
        safeAddCap(Caps.GLSL410);
        safeAddCap(Caps.GLSL420);
        safeAddCap(Caps.GLSL430);
        safeAddCap(Caps.GLSL440);
        safeAddCap(Caps.GLSL450);
        safeAddCap("VertexBufferArray");
        safeAddCap("FrameBuffer");
        safeAddCap("FrameBufferMRT");
        safeAddCap("TextureNonPowerOfTwo");
        safeAddCap("TextureCompressionS3TC");
        safeAddCap("TextureAnisotropicFilter");
        safeAddCap("DepthTexture");
        safeAddCap("PackedDepthStencilBuffer");
        safeAddCap("SeparateShaderObjects");

        safePutLimit("TextureImageUnits", 16);
        safePutLimit("VertexTextureUnits", 16);
        safePutLimit("FragmentTextureUnits", 16);
        safePutLimit("CombinedTextureUnits", 16);
        safePutLimit("MaxTextureSize", 16384);
        safePutLimit("MaxCubeMapSize", 16384);
        safePutLimit("MaxVertexUniformVectors", 4096);
        safePutLimit("MaxFragmentUniformVectors", 4096);
        safePutLimit("MaxVertexAttribs", 16);
        safePutLimit("MaxSamples", 4);
    }

    private void safeAddCap(Caps c) { if (c != null) caps.add(c); }
    private void safeAddCap(String capName) {
        try { caps.add(Caps.valueOf(capName)); } catch (IllegalArgumentException ignored) {}
    }
    private void safePutLimit(String limitName, int value) {
        try { limits.put(Limits.valueOf(limitName), value); } catch (IllegalArgumentException ignored) {}
    }

    @Override public EnumSet<Caps> getCaps() { return caps; }
    @Override public EnumMap<Limits, Integer> getLimits() { return limits; }
    @Override public Statistics getStatistics() { return stats; }

    @Override
    public void renderMesh(Mesh mesh, int lod, int count, VertexBuffer[] instanceData) {
        if (drawQueue.isFull() || mesh == null || drawCmdBuilder == null) return;

        RendererStateSnapshot snap = stateTracker.createSnapshot();
        DrawCmd dc = drawCmdBuilder.build(mesh, lod, count, instanceData, snap);
        
        if (dc != null) {
            drawQueue.enqueue(dc);
        }
    }

    @Override
    public void recordFrame(VkCommandBuffer cmd, int swapchainIndex, int frameIndex, VulkanFrameInfo frame) {
        if (frameRecorder != null) {
            frameRecorder.recordFrame(cmd, swapchainIndex, frameIndex, frame);
        }
    }

    @Override
    public void modifyTexture(Texture tex, Image pixels, int x, int y) {
        if (tex == null) return;
        if (pixels != null) tex.setImage(pixels);
        try { runtime.invalidateVkTexture(tex); } catch (Throwable ignored) {}
    }

    @Override
    public void deleteImage(Image image) {
        if (image == null) return;
        try { runtime.invalidateVkTextureByImage(image); } catch (Throwable ignored) {}
    }

    @Override
    public void updateBufferData(VertexBuffer vb) {
        if (vb == null) return;
        try { runtime.invalidateMeshGpuByVertexBuffer(vb); } catch (Throwable ignored) { runtime.invalidateAllMeshGpu(); }
    }

    @Override
    public void deleteBuffer(VertexBuffer vb) {
        if (vb == null) return;
        try { runtime.invalidateMeshGpuByVertexBuffer(vb); } catch (Throwable ignored) { runtime.invalidateAllMeshGpu(); }
    }

    @Override
    public void cleanup() {
        stateTracker.reset();
        drawQueue.clear();

        if (drawExecutor != null) {
            drawExecutor.cleanup();
        }
        
        drawCmdBuilder = null;
        drawExecutor = null;
        frameRecorder = null;
        initialized = false;
    }


    public void discardPendingDraws() {
        drawQueue.clear();
    }

    // --- State Tracker 代理委派 ---
    @Override public void clearBuffers(boolean color, boolean depth, boolean stencil) { stateTracker.setClearBuffers(color, depth, stencil); }
    @Override public void setBackgroundColor(ColorRGBA color) { stateTracker.setBackgroundColor(color); }
    @Override public void applyRenderState(RenderState state) { stateTracker.applyRenderState(state); }
    @Override public void setViewPort(int x, int y, int width, int height) { stateTracker.setViewPort(x, y, width, height); }
    @Override public void setClipRect(int x, int y, int width, int height) { stateTracker.setClipRect(x, y, width, height); }
    @Override public void clearClipRect() { stateTracker.clearClipRect(); }
    @Override public void setShader(Shader shader) { stateTracker.setShader(shader); }
    @Override public void setFrameBuffer(FrameBuffer fb) { stateTracker.setFrameBuffer(fb); }
    @Override public FrameBuffer getCurrentFrameBuffer() { return stateTracker.getCurrentFb(); }
    @Override public void setTexture(int unit, Texture tex) throws TextureUnitException { stateTracker.setTexture(unit, tex); }

    // --- 空实现或未支持的方法 ---
    @Override public void invalidateState() {}
    @Override public void setDepthRange(float start, float end) {}
    @Override public void postFrame() {}
    @Override public void deleteShader(Shader shader) {}
    @Override public void deleteShaderSource(Shader.ShaderSource source) {}
    @Override public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyDepth) {}
    @Override public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyColor, boolean copyDepth) {}
    @Override public void setMainFrameBufferOverride(FrameBuffer fb) {}
    @Override public void readFrameBuffer(FrameBuffer fb, ByteBuffer byteBuf) {}
    @Override public void readFrameBufferWithFormat(FrameBuffer fb, ByteBuffer byteBuf, Image.Format format) {}
    @Override public void deleteFrameBuffer(FrameBuffer fb) {}
    public void updateBufferData(BufferObject bo) {}
    @Override public void deleteBuffer(BufferObject bo) {}
    
    @Override public void popDebugGroup() { }
    @Override public void pushDebugGroup(String name) { }
    
    @Override public void resetGLObjects() {}
    @Override public void setDefaultAnisotropicFilter(int level) {}
    @Override public void setAlphaToCoverage(boolean value) {}
    @Override public void setMainFrameBufferSrgb(boolean srgb) {}
    @Override public void setLinearizeSrgbImages(boolean linearize) {}
    @Override public int[] generateProfilingTasks(int numTasks) { return new int[0]; }
    @Override public void startProfiling(int taskId) {}
    @Override public void stopProfiling() {}
    @Override public long getProfilingTime(int taskId) { return 0; }
    @Override public boolean isTaskResultAvailable(int taskId) { return false; }
    @Override public boolean getAlphaToCoverage() { return false; }
    @Override public int getDefaultAnisotropicFilter() { return 0; }
    @Override public float getMaxLineWidth() { return 1.0f; }
    @Override public boolean isLinearizeSrgbImages() { return false; }
    @Override public boolean isMainFrameBufferSrgb() { return false; }

    @Override
    public void setTextureImage(int unit, TextureImage tex) throws TextureUnitException {
    }

    @Override
    public void updateShaderStorageBufferObjectData(com.jme3.shader.bufferobject.BufferObject bo) {
    }

    @Override
    public void updateUniformBufferObjectData(com.jme3.shader.bufferobject.BufferObject bo) {
    }

    @Override
    public void setShaderStorageBufferObject(int bindingPoint, com.jme3.shader.bufferobject.BufferObject bufferObject) {
    }

    @Override
    public void setUniformBufferObject(int bindingPoint, com.jme3.shader.bufferobject.BufferObject bufferObject) {
    }
}