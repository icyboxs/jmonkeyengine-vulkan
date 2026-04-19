package com.jme3.renderer.vulkan;

import com.jme3.math.ColorRGBA;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.renderer.vulkan.pipeline.VulkanPipeline;
import com.jme3.renderer.vulkan.reflection.PipelineDescriptorBindingPlan;
import com.jme3.renderer.vulkan.runtime.VulkanRuntimeFacade;
import com.jme3.renderer.vulkan.runtime.VulkanRuntimeFrameLoop;
import com.jme3.renderer.vulkan.runtime.VulkanRuntimeLifecycle;
import com.jme3.renderer.vulkan.runtime.VulkanRuntimeState;
import com.jme3.renderer.vulkan.runtime.VulkanRuntimeStats;
import com.jme3.scene.Mesh;
import com.jme3.system.AppSettings;
import com.jme3.texture.Texture;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * 
 * @author icyboxs
 */
public final class VulkanRuntime {

    private static final Logger LOGGER = Logger.getLogger(VulkanRuntime.class.getName());
    static final boolean DEBUG = true;

    /**
     * cleanup 防重入标记（保证幂等/no-op 风格）
     */
    private final AtomicBoolean cleaningUp = new AtomicBoolean(false);

    // 统一状态容器
    final VulkanRuntimeState s;

    // 拆分组件
    private final VulkanRuntimeLifecycle lifecycle;
    private final VulkanRuntimeFacade facade;
    private final VulkanRuntimeFrameLoop frameLoop;

    public VulkanRuntimeStats getStats() {
        return s.stats;
    }

    public int materialSetCacheSize() {
        return facade.materialSetCacheSize();
    }

    public VulkanRuntime(AppSettings settings) {
        this.s = new VulkanRuntimeState(settings);
        this.lifecycle = new VulkanRuntimeLifecycle(this, s, LOGGER, DEBUG);
        this.facade = new VulkanRuntimeFacade(this, s);
        this.frameLoop = new VulkanRuntimeFrameLoop(this, s, lifecycle, cleaningUp, LOGGER);
    }

    public void init() throws IOException {
        if (s.window != null) {
            return;
        }
        LOGGER.fine("[VulkanRuntime] init begin");
        lifecycle.init();
        LOGGER.fine("[VulkanRuntime] init done");
    }

    public synchronized void ensureInitialized() {
        if (s.window != null) {
            return;
        }
        try {
            init();
        } catch (IOException e) {
            throw new RuntimeException("Failed to init VulkanRuntime", e);
        }
    }

    public VulkanPipeline getOrCreatePipeline(VkPipelineKey key, String vertSrc, String fragSrc) {
        if (s.pipelineManager == null) {
            throw new IllegalStateException("pipelineManager not initialized");
        }
        return s.pipelineManager.getOrCreatePipeline(key, vertSrc, fragSrc);
    }

    public void requestClose() {
        if (s.window != null) {
            s.window.requestClose();
        }
    }

    public void setTitle(String title) {
        if (s.window != null) {
            s.window.setTitle(title);
        }
    }

    public boolean isInitialized() {
        return s.window != null;
    }

    public void pollEvents() {
        if (s.window != null) {
            s.window.pollEvents();
        }
    }

    public boolean shouldClose() {
        return s.window != null && s.window.shouldClose();
    }

    public void requestResize(int w, int h) {
        if (w > 0 && h > 0) {
            s.settings.setWidth(w);
            s.settings.setHeight(h);
            s.reshapeRequested.set(true);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine("[VulkanRuntime] requestResize: " + w + "x" + h);
            }
        }
    }

    public void cleanup() {
        if (!cleaningUp.compareAndSet(false, true)) {
            LOGGER.fine("[VulkanRuntime] cleanup skipped: already in progress");
            return;
        }

        try {
            LOGGER.fine("[VulkanRuntime] cleanup begin");

            if (s.vk != null) {
                s.vk.waitIdle();
            }

            lifecycle.cleanup();

            s.reshapeRequested.set(false);
            s.angle = 0f;
            s.lastTimeNs = 0L;

            LOGGER.fine("[VulkanRuntime] cleanup done");
        } finally {
            cleaningUp.set(false);
        }
    }

    public void renderFrame(float tpf, VkCommandRecorder recorder, ColorRGBA clearColor) {
        frameLoop.renderFrame(tpf, recorder, clearColor);
    }

    // ===== facade delegates =====
    public com.jme3.renderer.vulkan.mesh.VkMeshGpu getOrCreateMeshGpu(Mesh mesh) {
        return facade.getOrCreateMeshGpu(mesh);
    }

    public void destroyMeshGpu(Mesh mesh) {
        facade.destroyMeshGpu(mesh);
    }

    public com.jme3.renderer.vulkan.resource.VkTexture getOrCreateVkTexture(Texture tex) {
        return facade.getOrCreateVkTexture(tex);
    }

    public int getFramebufferWidth() {
        return facade.getFramebufferWidth();
    }

    public int getFramebufferHeight() {
        return facade.getFramebufferHeight();
    }

    public long getWindowHandle() {
        return facade.getWindowHandle();
    }

    public com.jme3.renderer.vulkan.resource.VulkanFrameDescriptors getFrameDescriptors(int frameIndex) {
        return facade.getFrameDescriptors(frameIndex);
    }

    public long getMaterialPoolGrowCount() {
        return facade.getMaterialPoolGrowCount();
    }

    public com.jme3.renderer.vulkan.resource.VkOffscreenTarget getOffscreen() {
        return facade.getOffscreen();
    }

    public com.jme3.texture.Texture2D getOffscreenJmeTex() {
        return facade.getOffscreenJmeTex();
    }

    public com.jme3.renderer.vulkan.shader.VulkanShaders getShaders() {
        return facade.getShaders();
    }

    public long getOrCreateSampler(Texture tex) {
        return facade.getOrCreateSampler(tex);
    }

    public long chooseSamplerForTex0(Texture jmeTex0) {
        return facade.chooseSamplerForTex0(jmeTex0);
    }

    public long chooseSamplerForLight(Texture jmeLight) {
        return facade.chooseSamplerForLight(jmeLight);
    }

    public com.jme3.renderer.vulkan.pipeline.PassKey getDefaultPassKey() {
        return facade.getDefaultPassKey();
    }

    public com.jme3.renderer.vulkan.resource.VkUboLayout getPipelineUboLayout(VkPipelineKey key) {
        return facade.getPipelineUboLayout(key);
    }

    public int getMinUniformBufferOffsetAlignment() {
        return facade.getMinUniformBufferOffsetAlignment();
    }

    public long allocDescriptorSetOnly(int frameIndex) {
        return facade.allocDescriptorSetOnly(frameIndex);
    }

    public long allocAndWriteDummySet1(int frameIndex, int bindingInSet1) {
        return facade.allocAndWriteDummySet1(frameIndex, bindingInSet1);
    }

    public long allocDescriptorSetByLayout(int frameIndex, long setLayout) {
        return facade.allocDescriptorSetByLayout(frameIndex, setLayout);
    }

    public void writeSingleImageToSet(long dstSet, int dstBinding,
            com.jme3.renderer.vulkan.resource.VkTexture tex, long sampler) {
        facade.writeSingleImageToSet(dstSet, dstBinding, tex, sampler);
    }

    public com.jme3.renderer.vulkan.reflection.ParamBindingPlan getPipelineParamBindingPlan(VkPipelineKey key) {
        return facade.getPipelineParamBindingPlan(key);
    }

    public boolean pipelineHasPushConstants(VkPipelineKey key) {
        return facade.pipelineHasPushConstants(key);
    }

    public int getPipelinePushConstantSize(VkPipelineKey key) {
        return facade.getPipelinePushConstantSize(key);
    }

    public void writeSingleBufferToSet(long dstSet, int dstBinding, long buffer, long offset, long range, boolean dynamic) {
        facade.writeSingleBufferToSet(dstSet, dstBinding, buffer, offset, range, dynamic);
    }

    public PipelineDescriptorBindingPlan getPipelineBindingPlan(VkPipelineKey key) {
        return facade.getPipelineBindingPlan(key);
    }

    public void invalidateVkTexture(com.jme3.texture.Texture tex) {
        facade.invalidateVkTexture(tex);
    }

    public void invalidateVkTextureByImage(com.jme3.texture.Image image) {
        facade.invalidateVkTextureByImage(image);
    }

    public void invalidateAllMeshGpu() {
        facade.invalidateAllMeshGpu();
    }

    public void invalidateMeshGpuByVertexBuffer(com.jme3.scene.VertexBuffer vb) {
        facade.invalidateMeshGpuByVertexBuffer(vb);
    }

    public void setCurrentFrameSlot(int frameIndex) {
        s.currentFrameSlot = frameIndex;
    }

    public int getCurrentFrameSlot() {
        return s.currentFrameSlot;
    }

//    public void onFrameSlotBegin(int frameIndex) {
//        if (s.deferredReleaseQueue != null) {
//            s.deferredReleaseQueue.flushForFrame(frameIndex);
//        }
//    }
    
    public void onFrameSlotBegin(int frameIndex) {
        if (s.deferredReleaseQueue != null) {
            s.deferredReleaseQueue.flushForFrame(frameIndex);
        }
        
        // 【显存泄漏修复】：重置当前帧槽位的 DescriptorPool。
        // 在这之前，由于遗漏了对底层帧池的 reset 调用，导致 DescriptorPool
        // 只分配不回收，并不断触发内部的 growPools() 创建呈指数级变大的新池子，
        // 最终耗尽 Vulkan 底层的所有内存/显存。
        if (s.frameDesc != null && frameIndex >= 0 && frameIndex < s.frameDesc.length) {
            if (s.frameDesc[frameIndex] != null) {
                s.frameDesc[frameIndex].beginFrame();
            }
        }
    }
}
