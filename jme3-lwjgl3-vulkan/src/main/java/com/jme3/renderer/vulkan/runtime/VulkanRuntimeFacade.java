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
        // S7-T1: 不再兜底 fixedStage1()
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

}
