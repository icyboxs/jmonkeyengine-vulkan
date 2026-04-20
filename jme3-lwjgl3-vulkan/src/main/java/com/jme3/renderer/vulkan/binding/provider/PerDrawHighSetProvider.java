package com.jme3.renderer.vulkan.binding.provider;

import com.jme3.renderer.vulkan.binding.cache.HighSetCacheKey;
import com.jme3.renderer.vulkan.binding.api.HighFrequencySetProvider;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindRequest;
import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.binding.api.HighSetResolveResult;
import com.jme3.renderer.vulkan.frame.VulkanFrameDriver;
import com.jme3.renderer.vulkan.reflection.ParamBindingPlan;
import com.jme3.renderer.vulkan.resource.VkTexture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Step C.7 / S7-T2: HIGH provider with per-frame cache lifecycle. Now fully
 * reflection-driven for descriptor bindings.
 */
public final class PerDrawHighSetProvider implements HighFrequencySetProvider {

    private final VulkanRuntime runtime;

    // frameIndex -> (HighSetCacheKey -> descriptorSet)
    private final Map<Integer, HashMap<HighSetCacheKey, Long>> cacheByFrame = new HashMap<>();

    public PerDrawHighSetProvider(VulkanRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime is null");
        }
        this.runtime = runtime;
    }

    @Override
    public HighSetResolveResult resolveSet(DescriptorBindRequest req, int setIndex, long setLayout) {
        if (req == null) {
            throw new IllegalArgumentException("req is null");
        }
        if (setLayout == 0L) {
            throw new IllegalArgumentException("setLayout is 0");
        }

        HighSetCacheKey key = new HighSetCacheKey(
                req.frameIndex,
                setIndex,
                setLayout,
                req.dynamicOffset,
                req.pipelineHash,
                req.materialHash,
                req.extraTexId,
                req.perDrawUboHandle,
                req.perDrawUboRange
        );

        HashMap<HighSetCacheKey, Long> frameMap
                = cacheByFrame.computeIfAbsent(req.frameIndex, k -> new HashMap<>());

        Long cached = frameMap.get(key);
        if (cached != null && cached != 0L) {
            return com.jme3.renderer.vulkan.binding.api.HighSetResolveResult.hit(cached);
        }

        long set = runtime.allocDescriptorSetByLayout(req.frameIndex, setLayout);

        ParamBindingPlan pbp = runtime.getPipelineParamBindingPlan(req.drawCmd.pipelineKey);
        ParamBindingPlan.BindingSlot texSlot = pbp.getTextureSlot(setIndex);
        ParamBindingPlan.BindingSlot uboSlot = pbp.getUboSlot(setIndex);

        // 若 Shader 声明了贴图，写入白图作为占位/安全兜底
        if (texSlot != null) {
            VkTexture white = runtime.getOrCreateVkTexture(null);
            long sampler = (white != null) ? white.sampler : 0L;
            if (white != null && white.view != 0L && sampler != 0L) {
                runtime.writeSingleImageToSet(set, texSlot.binding, white, sampler);
            }
        }

        // 若 Shader 声明了 UBO，写入真实动态缓冲
        if (uboSlot != null && req.perDrawUboHandle != 0L) {
            long range = (req.perDrawUboRange > 0) ? (long) req.perDrawUboRange : 256L;
            boolean isDynamic = uboSlot.type.contains("DYNAMIC");
            runtime.writeSingleBufferToSet(
                    set,
                    uboSlot.binding,
                    req.perDrawUboHandle,
                    0L,
                    range,
                    isDynamic
            );
        }

        frameMap.put(key, set);
        return com.jme3.renderer.vulkan.binding.api.HighSetResolveResult.miss(set);
    }

    @Override
    public void beginFrame(int frameIndex) {
        int frames = VulkanFrameDriver.MAX_FRAMES_IN_FLIGHT;
        int prev = (frameIndex + frames - 1) % frames;

        Iterator<Integer> it = cacheByFrame.keySet().iterator();
        while (it.hasNext()) {
            int fi = it.next();
            if (fi != frameIndex && fi != prev) {
                it.remove();
            }
        }
    }

    @Override
    public void cleanup() {
        cacheByFrame.clear();
    }
}
