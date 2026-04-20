package com.jme3.renderer.vulkan.binding.provider;

import com.jme3.renderer.vulkan.binding.cache.FrameSetCache;
import com.jme3.renderer.vulkan.binding.api.HighFrequencySetProvider;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindRequest;
import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.binding.api.HighSetResolveResult;
import com.jme3.renderer.vulkan.reflection.ParamBindingPlan;
import com.jme3.renderer.vulkan.resource.VkTexture;

/**
 * Step B.2 / S7-T2: 最小 HIGH provider 动态查询 COMBINED_IMAGE_SAMPLER 绑定并可选写入白图兜底。
 */
public final class DummyHighFrequencySetProvider implements HighFrequencySetProvider {

    private final VulkanRuntime runtime;
    private final FrameSetCache cache = new FrameSetCache();
    private final boolean writeWhiteImage;

    public DummyHighFrequencySetProvider(VulkanRuntime runtime, boolean writeWhiteImage) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime is null");
        }
        this.runtime = runtime;
        this.writeWhiteImage = writeWhiteImage;
    }

    @Override
    public HighSetResolveResult resolveSet(DescriptorBindRequest req, int setIndex, long setLayout) {
        long cacheKey = setLayout ^ ((long) setIndex << 32);
        Long cached = cache.get(req.frameIndex, cacheKey);
        if (cached != null && cached != 0L) {
            return com.jme3.renderer.vulkan.binding.api.HighSetResolveResult.hit(cached);
        }

        long set = runtime.allocDescriptorSetByLayout(req.frameIndex, setLayout);

        if (writeWhiteImage) {
            ParamBindingPlan pbp = runtime.getPipelineParamBindingPlan(req.drawCmd.pipelineKey);
            ParamBindingPlan.BindingSlot texSlot = pbp.getTextureSlot(setIndex);
            //ParamBindingPlan.BindingSlot uboSlot = pbp.getUboSlot(setIndex);
            
            if (texSlot != null) {
                VkTexture white = runtime.getOrCreateVkTexture(null);
                long sampler = (white != null) ? white.sampler : 0L;
                if (white != null && white.view != 0L && sampler != 0L) {
                    runtime.writeSingleImageToSet(set, texSlot.binding, white, sampler);
                }
            }
        }

        cache.put(req.frameIndex, cacheKey, set);
        return com.jme3.renderer.vulkan.binding.api.HighSetResolveResult.miss(set);
    }

    public void cleanup() {
        cache.clearAll();
    }
}
