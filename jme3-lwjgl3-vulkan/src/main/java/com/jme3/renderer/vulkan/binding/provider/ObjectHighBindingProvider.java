package com.jme3.renderer.vulkan.binding.provider;

import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindRequest;
import com.jme3.renderer.vulkan.binding.api.HighFrequencySetProvider;
import com.jme3.renderer.vulkan.binding.api.HighSetResolveResult;
import com.jme3.renderer.vulkan.binding.cache.ObjectBindingKey;
import com.jme3.renderer.vulkan.frame.VulkanFrameDriver;
import com.jme3.renderer.vulkan.reflection.ParamBindingPlan;
import com.jme3.renderer.vulkan.resource.VkTexture;
import com.jme3.texture.Texture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * D3：对象级 HIGH 绑定
 * 完全由反射数据驱动确定贴图与 UBO_DYNAMIC 的 binding 槽位。
 * 并且使用 O(1) 查表和零分配（Zero-Allocation）数组进行匹配。
 */
public final class ObjectHighBindingProvider implements HighFrequencySetProvider {

    private final VulkanRuntime runtime;
    private final Map<Integer, HashMap<ObjectBindingKey, Long>> cacheByFrame = new HashMap<>();

    public ObjectHighBindingProvider(VulkanRuntime runtime) {
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

        int objectId = (req.objectId != 0) ? req.objectId : req.materialHash; // D4：优先真实对象ID

        ObjectBindingKey key = new ObjectBindingKey(
                req.frameIndex,
                setIndex,
                setLayout,
                objectId,
                req.pipelineHash,
                req.extraTexId,
                req.perDrawUboHandle,
                req.perDrawUboRange,
                req.dynamicOffset
        );

        HashMap<ObjectBindingKey, Long> frameMap
                = cacheByFrame.computeIfAbsent(req.frameIndex, k -> new HashMap<>());

        Long cached = frameMap.get(key);
        if (cached != null && cached != 0L) {
            return com.jme3.renderer.vulkan.binding.api.HighSetResolveResult.hit(cached);
        }

        long set = runtime.allocDescriptorSetByLayout(req.frameIndex, setLayout);

        // 极速 O(1) 查询绑定信息，不再遍历字符串
        ParamBindingPlan pbp = runtime.getPipelineParamBindingPlan(req.drawCmd.pipelineKey);
        ParamBindingPlan.BindingSlot texSlot = pbp.getTextureSlot(setIndex);
        ParamBindingPlan.BindingSlot uboSlot = pbp.getUboSlot(setIndex);

        // 写入贴图（如果 Shader 声明了）
        if (texSlot != null) {
            Texture objTexJme = pickObjectTexture(req, setIndex, texSlot.binding);
            VkTexture tex;
            long sampler;

            if (objTexJme != null) {
                tex = runtime.getOrCreateVkTexture(objTexJme);
                sampler = runtime.getOrCreateSampler(objTexJme);
            } else {
                tex = runtime.getOrCreateVkTexture(null);
                sampler = (tex != null) ? tex.sampler : 0L;
            }

            if (tex != null && tex.view != 0L && sampler != 0L) {
                runtime.writeSingleImageToSet(set, texSlot.binding, tex, sampler);
            }
        }

        // 写入对象 UBO (dynamic) （如果 Shader 声明了）
        if (uboSlot != null && req.perDrawUboHandle != 0L) {
            long range = (req.perDrawUboRange > 0) ? req.perDrawUboRange : 256L;
            boolean isDynamic = uboSlot.type != null && uboSlot.type.contains("DYNAMIC");
            runtime.writeSingleBufferToSet(set, uboSlot.binding, req.perDrawUboHandle, 0L, range, isDynamic);
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

    /**
     * 极速挑选对象纹理（使用零分配数组，告别 HashMap 迭代）
     */
    private static Texture pickObjectTexture(DescriptorBindRequest req, int targetSet, int targetBinding) {
        if (req == null || req.drawCmd == null) {
            return null;
        }

        // 1. 优先精确匹配 set 和 binding
        if (req.drawCmd.customImageCount > 0) {
            for (int i = 0; i < req.drawCmd.customImageCount; i++) {
                ParamBindingPlan.BindingSlot slot = req.drawCmd.customImageSlots[i];
                if (slot != null && slot.set == targetSet && slot.binding == targetBinding) {
                    Texture t = req.drawCmd.customImageTextures[i];
                    if (t != null && t.getImage() != null) {
                        return t;
                    }
                }
            }
            
            // 2. 泛选兜底：只要 set 匹配就拿来用（为了鲁棒性）
            for (int i = 0; i < req.drawCmd.customImageCount; i++) {
                ParamBindingPlan.BindingSlot slot = req.drawCmd.customImageSlots[i];
                if (slot != null && slot.set == targetSet) {
                    Texture t = req.drawCmd.customImageTextures[i];
                    if (t != null && t.getImage() != null) {
                        return t;
                    }
                }
            }
        }

        // 3. 退化为 extra snapshot
        Texture extra = req.drawCmd.useWhiteExtra ? null : req.drawCmd.jmeExtraSnapshot;
        if (extra != null && extra.getImage() != null) {
            return extra;
        }

        return null; // 上层会回退为 white
    }
}
