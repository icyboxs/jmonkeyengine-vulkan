package com.jme3.renderer.vulkan.runtime;

import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.mesh.VkMeshGpu;
import com.jme3.renderer.vulkan.pipeline.PassKey;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.renderer.vulkan.reflection.ParamBindingPlan;
import com.jme3.renderer.vulkan.reflection.PipelineDescriptorBindingPlan;
import com.jme3.renderer.vulkan.resource.VkOffscreenTarget;
import com.jme3.renderer.vulkan.resource.VkTexture;
import com.jme3.renderer.vulkan.resource.VkUboLayout;
import com.jme3.renderer.vulkan.resource.VulkanFrameDescriptors;
import com.jme3.renderer.vulkan.shader.VulkanShaders;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;

public final class VulkanRuntimeFacade {

    private final VulkanRuntime owner;
    private final VulkanRuntimeState s;

    public VulkanRuntimeFacade(VulkanRuntime owner, VulkanRuntimeState s) {
        this.owner = owner;
        this.s = s;
    }

    public VkMeshGpu getOrCreateMeshGpu(Mesh mesh) {
        if (s.meshManager == null) {
            throw new IllegalStateException("meshManager not initialized");
        }
        return s.meshManager.getOrCreate(mesh);
    }

    public void destroyMeshGpu(Mesh mesh) {
        if (s.meshManager != null) {
            s.meshManager.destroy(mesh);
        }
    }

    public VkTexture getOrCreateVkTexture(Texture tex) {
        if (s.renderTargetManager != null && s.renderTargetManager.isOffscreenCarrier(tex)) {
            return s.renderTargetManager.getOffscreenColorOrWhite(s.whiteTex);
        }

        if (s.textureManager == null) {
            throw new IllegalStateException("textureManager not initialized");
        }
        return s.textureManager.getOrCreate(tex);
    }

    public void invalidateVkTexture(Texture tex) {
        if (s.textureManager != null) {
            s.textureManager.invalidate(tex);
        }
    }

    public void invalidateVkTextureByImage(Image image) {
        if (s.textureManager != null) {
            s.textureManager.invalidateByImage(image);
        }
    }

    public void invalidateAllMeshGpu() {
        if (s.meshManager != null) {
            s.meshManager.destroyAll();
        }
    }

    public int getFramebufferWidth() {
        return s.window != null ? s.window.fbWidth() : s.settings.getWidth();
    }

    public int getFramebufferHeight() {
        return s.window != null ? s.window.fbHeight() : s.settings.getHeight();
    }

    public long getWindowHandle() {
        return s.window != null ? s.window.handle() : 0L;
    }

    public VulkanFrameDescriptors getFrameDescriptors(int frameIndex) {
        if (s.frameDesc == null) {
            throw new IllegalStateException("frameDesc not initialized");
        }
        if (frameIndex < 0 || frameIndex >= s.frameDesc.length) {
            throw new IllegalArgumentException(
                    "frameIndex out of range: " + frameIndex + ", valid=[0," + (s.frameDesc.length - 1) + "]"
            );
        }
        VulkanFrameDescriptors fd = s.frameDesc[frameIndex];
        if (fd == null) {
            throw new IllegalStateException("frameDesc[" + frameIndex + "] is null");
        }
        return fd;
    }

    public int materialSetCacheSize() {
        return s.materialManager != null ? s.materialManager.cacheSize() : 0;
    }

    public long getMaterialPoolGrowCount() {
        return s.materialManager != null ? s.materialManager.getMaterialPoolGrowCount() : 0L;
    }

    public VkOffscreenTarget getOffscreen() {
        return s.renderTargetManager != null ? s.renderTargetManager.getOffscreen() : null;
    }

    public com.jme3.texture.Texture2D getOffscreenJmeTex() {
        return s.renderTargetManager != null ? s.renderTargetManager.getOffscreenJmeTex() : null;
    }

    public VulkanShaders getShaders() {
        return s.shaders;
    }

    public long getOrCreateSampler(Texture tex) {
        if (s.samplerManager == null) {
            throw new IllegalStateException("samplerManager not initialized");
        }
        return s.samplerManager.getOrCreateSampler(tex);
    }

    public long chooseSamplerForTex0(Texture jmeTex0) {
        if (jmeTex0 == null) {
            return (s.whiteTex != null) ? s.whiteTex.sampler : 0L;
        }
        return getOrCreateSampler(jmeTex0);
    }

    public long chooseSamplerForLight(Texture jmeLight) {
        if (jmeLight == null) {
            return (s.whiteTex != null) ? s.whiteTex.sampler : 0L;
        }
        return getOrCreateSampler(jmeLight);
    }

    public PassKey getDefaultPassKey() {
        if (s.defaultPassKey == null) {
            throw new IllegalStateException("defaultPassKey not initialized");
        }
        return s.defaultPassKey;
    }

    public VkUboLayout getPipelineUboLayout(VkPipelineKey key) {
        if (s.forceFixedUboLayout || s.pipelineManager == null) {
            return VkUboLayout.empty();
        }
        return s.pipelineManager.getUboLayout(key);
    }

    public int getMinUniformBufferOffsetAlignment() {
        if (s.vk == null) {
            return 256;
        }
        long v = s.vk.minUniformBufferOffsetAlignment();
        if (v <= 0L || v > Integer.MAX_VALUE) {
            return 256;
        }
        return (int) v;
    }

    public long allocDescriptorSetOnly(int frameIndex) {
        throw new UnsupportedOperationException("allocDescriptorSetOnly disabled in single-set baseline");
    }

    public long allocAndWriteDummySet1(int frameIndex, int bindingInSet1) {
        throw new UnsupportedOperationException("allocAndWriteDummySet1 disabled in single-set baseline");
    }

    public long allocDescriptorSetByLayout(int frameIndex, long setLayout) {
        if (s.frameDesc == null) {
            throw new IllegalStateException("frameDesc not initialized");
        }
        if (frameIndex < 0 || frameIndex >= s.frameDesc.length) {
            throw new IllegalArgumentException("frameIndex out of range");
        }
        return s.frameDesc[frameIndex].allocSet(setLayout);
    }

    public void writeSingleImageToSet(long dstSet, int dstBinding, VkTexture tex, long sampler) {
        if (s.descriptors == null) {
            throw new IllegalStateException("descriptors not initialized");
        }
        s.descriptors.writeSingleSampledImage(dstSet, dstBinding, tex, sampler);
    }

    public ParamBindingPlan getPipelineParamBindingPlan(VkPipelineKey key) {
        if (s.pipelineManager == null) {
            return new ParamBindingPlan();
        }
        return s.pipelineManager.getParamBindingPlan(key);
    }

    public boolean pipelineHasPushConstants(VkPipelineKey key) {
        return s.pipelineManager != null && s.pipelineManager.hasPushConstants(key);
    }

    public int getPipelinePushConstantSize(VkPipelineKey key) {
        return s.pipelineManager != null ? s.pipelineManager.getPushConstantSize(key) : 0;
    }

    public void writeSingleBufferToSet(long dstSet, int dstBinding, long buffer, long offset, long range, boolean dynamic) {
        if (s.descriptors == null) {
            throw new IllegalStateException("descriptors not initialized");
        }
        s.descriptors.writeSingleBufferToSet(dstSet, dstBinding, buffer, offset, range, dynamic);
    }

    public PipelineDescriptorBindingPlan getPipelineBindingPlan(VkPipelineKey key) {
        if (s.pipelineManager == null) {
            return PipelineDescriptorBindingPlan.empty();
        }
        return s.pipelineManager.getBindingPlan(key);
    }

    public void invalidateMeshGpuByVertexBuffer(VertexBuffer vb) {
        if (s.meshManager != null) {
            s.meshManager.destroyByVertexBuffer(vb);
        }
    }

    public void deleteFrameBuffer(com.jme3.texture.FrameBuffer fb) {
        if (s.frameBufferManager != null) {
            s.frameBufferManager.deleteFrameBuffer(fb);
        }
    }

    public void readFrameBuffer(com.jme3.texture.FrameBuffer fb, java.nio.ByteBuffer byteBuf, com.jme3.texture.Image.Format format) {
        if (s.vk == null || s.rf == null || byteBuf == null) {
            return;
        }

        // 强行同步：确保 GPU 已经完成所有绘制任务，以便截出最新画面
        org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(s.vk.device());

        long srcImage = 0;
        int srcLayout = org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
        int width = 0, height = 0;
        boolean isVulkanBGRA = false;

        if (fb == null) {
            // [模式A] 读取主屏幕 Swapchain 图像
            com.jme3.renderer.vulkan.swapchain.VulkanSwapchain sc = s.renderTargetManager.getSwapchain();
            if (sc == null) {
                return;
            }

            int idx = s.frameDriver != null ? s.frameDriver.getLastImageIndex() : 0;
            srcImage = sc.getImage(idx);
            srcLayout = sc.getImageLayout(idx);
            if (srcLayout == org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED) {
                srcLayout = org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            }
            width = sc.getWidth();
            height = sc.getHeight();

            int vkFormat = sc.getColorFormat();
            isVulkanBGRA = (vkFormat == org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM || vkFormat == org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB);
        } else {
            // [模式B] 读取离线 FBO
            com.jme3.texture.FrameBuffer.RenderBuffer rb = fb.getColorTarget();
            if (rb == null || rb.getTexture() == null) {
                return;
            }

            com.jme3.renderer.vulkan.resource.VkTexture vkTex = getOrCreateVkTexture(rb.getTexture());
            if (vkTex == null || vkTex.image == 0) {
                return;
            }

            srcImage = vkTex.image;
            srcLayout = org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            width = vkTex.width;
            height = vkTex.height;
            isVulkanBGRA = false; // 用户创建的 FBO 一般都是原生 RGBA8
        }

        if (srcImage == 0 || width <= 0 || height <= 0) {
            return;
        }

        s.rf.downloadImagePixels(srcImage, srcLayout, width, height, byteBuf, format, isVulkanBGRA);
    }

    public void setMainFrameBufferSrgb(boolean srgb) {
        if (s.mainFbSrgb != srgb) {
            s.mainFbSrgb = srgb;
            if (s.renderTargetManager != null) {
                // 触发 Swapchain 和 主屏幕渲染格式重建
                s.reshapeRequested.set(true);
            }

            if (s.vk != null) {
                // 1. 刷新 PassKey 的全局颜色格式，否则后续新创建的 Pipeline 会崩溃
                s.defaultPassKey = new com.jme3.renderer.vulkan.pipeline.PassKey(1, s.vk.getColorFormat(srgb), s.vk.depthFormat(), org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT);

                // 2. 强行销毁旧有的 Graphics Pipeline (管线无法适应改变了格式后的 RenderPass)
                if (s.pipelineManager != null) {
                    s.pipelineManager.setDefaultPassKey(s.defaultPassKey);
                    s.pipelineManager.destroy();
                    try {
                        s.pipelineManager.init(); // 重新拉起基础管线
                    } catch (java.io.IOException e) {
                        throw new RuntimeException("Failed to reinitialize pipelines for SRGB change", e);
                    }
                }
            }
        }
    }

    public boolean isMainFrameBufferSrgb() {
        return s.mainFbSrgb;
    }

    public void setLinearizeSrgbImages(boolean linearize) {
        if (s.linearizeSrgbImages != linearize) {
            s.linearizeSrgbImages = linearize;
            // 如果在运行时突然切换线性化开关，需要废弃当前所有已缓存的 Vulkan 纹理，迫使它们按新格式重新上传
            if (s.textureManager != null) {
                s.textureManager.destroyCachedTextures();
            }
        }
    }

    public boolean isLinearizeSrgbImages() {
        return s.linearizeSrgbImages;
    }
    
    // 在类的末尾新增：
    public void setDefaultAnisotropicFilter(int level) {
        // 限制最低为 1
        s.defaultAnisotropicFilter = Math.max(1, level);
    }

    public int getDefaultAnisotropicFilter() {
        return s.defaultAnisotropicFilter;
    }
}
