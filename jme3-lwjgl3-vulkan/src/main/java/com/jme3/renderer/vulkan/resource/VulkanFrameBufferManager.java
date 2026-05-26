package com.jme3.renderer.vulkan.resource;

import com.jme3.texture.FrameBuffer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * 负责管理 JME3 FrameBuffer 对应的底层 Vulkan 专属资源的生命周期。
 * @author icyboxs
 */
public final class VulkanFrameBufferManager {

    private final VkResourceFactory rf;
    private final VulkanDeferredReleaseQueue deferredReleaseQueue;
    private final IntSupplier frameIndexSupplier;

    // 缓存 JME3 的 FrameBuffer 对象
    private final Map<FrameBuffer, VkFrameBufferGpu> fbCache = new IdentityHashMap<>();

    public VulkanFrameBufferManager(VkResourceFactory rf,
                                    VulkanDeferredReleaseQueue deferredReleaseQueue,
                                    IntSupplier frameIndexSupplier) {
        if (rf == null) {
            throw new IllegalArgumentException("rf is null");
        }
        this.rf = rf;
        this.deferredReleaseQueue = deferredReleaseQueue;
        this.frameIndexSupplier = frameIndexSupplier;
    }

    /**
     * 获取或创建 FrameBuffer 对应的 GPU 资源容器。
     * (真实的资源分配将在后续实现 setFrameBuffer 渲染管线连通时触发)
     */
    public VkFrameBufferGpu getOrCreate(FrameBuffer fb) {
        if (fb == null) {
            return null;
        }
        VkFrameBufferGpu gpu = fbCache.get(fb);
        if (gpu != null) {
            return gpu;
        }
        gpu = new VkFrameBufferGpu();
        gpu.width = fb.getWidth();
        gpu.height = fb.getHeight();
        fbCache.put(fb, gpu);
        return gpu;
    }

    /**
     * 响应 JME3 前端的 deleteFrameBuffer 请求，销毁专属渲染缓冲资源。
     */
    public void deleteFrameBuffer(FrameBuffer fb) {
        if (fb == null) {
            return;
        }
        VkFrameBufferGpu gpu = fbCache.remove(fb);
        if (gpu != null) {
            deferDestroy(gpu);
        }
    }

    /**
     * 销毁所有缓存的 FrameBuffer 资源（用于引擎退出/重建）。
     */
    public void destroyAll() {
        for (VkFrameBufferGpu gpu : fbCache.values()) {
            if (gpu != null) {
                deferDestroy(gpu);
            }
        }
        fbCache.clear();
    }

    private void deferDestroy(VkFrameBufferGpu gpu) {
        // 核心安全保障：扔进延迟队列，确保这部分显存能在 GPU 渲染完相关的帧后再释放
        if (deferredReleaseQueue == null || frameIndexSupplier == null) {
            destroyGpuResourceNow(gpu);
            return;
        }
        deferredReleaseQueue.enqueue(frameIndexSupplier.getAsInt(), () -> destroyGpuResourceNow(gpu));
    }

    private void destroyGpuResourceNow(VkFrameBufferGpu gpu) {
        for (VkTexture tex : gpu.internalColorBuffers) {
            if (tex != null) {
                rf.destroyTexture(tex);
            }
        }
        gpu.internalColorBuffers.clear();

        if (gpu.internalDepthBuffer != null) {
            rf.destroyDepth(gpu.internalDepthBuffer);
            gpu.internalDepthBuffer = null;
        }
    }
}