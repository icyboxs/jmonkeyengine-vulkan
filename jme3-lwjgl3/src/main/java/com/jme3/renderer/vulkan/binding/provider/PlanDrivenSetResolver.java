package com.jme3.renderer.vulkan.binding.provider;

import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindRequest;
import com.jme3.renderer.vulkan.reflection.BindingPlanEntry;
import com.jme3.renderer.vulkan.reflection.CacheClass;
import com.jme3.renderer.vulkan.reflection.ResourceSemantic;
import com.jme3.renderer.vulkan.reflection.SetBindingPlan;
import com.jme3.renderer.vulkan.resource.VkTexture;
import com.jme3.texture.Texture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public final class PlanDrivenSetResolver {

    private final VulkanRuntime runtime;

    // 跨帧材质缓存（MATERIAL）
    private final Map<MaterialSetKey, Long> materialCache = new HashMap<>();

    // 按帧缓存（PER_FRAME）
    private final Map<Integer, HashMap<FrameSetKey, Long>> frameCache = new HashMap<>();

    public PlanDrivenSetResolver(VulkanRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime is null");
        }
        this.runtime = runtime;
    }

    public void beginFrame(int frameIndex) {
        // 只保留当前帧和上一帧（与 framesInFlight=2 匹配）
        int frames = com.jme3.renderer.vulkan.frame.VulkanFrameDriver.MAX_FRAMES_IN_FLIGHT;
        int prev = (frameIndex + frames - 1) % frames;

        Iterator<Integer> it = frameCache.keySet().iterator();
        while (it.hasNext()) {
            int fi = it.next();
            if (fi != frameIndex && fi != prev) {
                it.remove();
            }
        }
    }

    public void cleanup() {
        materialCache.clear();
        frameCache.clear();
    }

    public long resolveAndWriteSet(DescriptorBindRequest req, SetBindingPlan setPlan, long setLayout) {
        if (req == null) {
            throw new IllegalArgumentException("req is null");
        }
        if (setPlan == null) {
            throw new IllegalArgumentException("setPlan is null");
        }
        if (setLayout == 0L) {
            throw new IllegalArgumentException("setLayout is 0");
        }

        CacheClass cc = (setPlan.cacheClass != null) ? setPlan.cacheClass : CacheClass.NONE;

        // 1) 先查缓存
        if (cc == CacheClass.MATERIAL) {
            MaterialSetKey k = buildMaterialKey(req, setPlan, setLayout);
            Long hit = materialCache.get(k);
            if (hit != null && hit != 0L) {
                return hit;
            }

            long set = allocAndWrite(req, setPlan, setLayout);
            materialCache.put(k, set);
            return set;
        }

        if (cc == CacheClass.PER_FRAME) {
            FrameSetKey k = buildFrameKey(req, setPlan, setLayout);
            HashMap<FrameSetKey, Long> m = frameCache.computeIfAbsent(req.frameIndex, x -> new HashMap<>());
            Long hit = m.get(k);
            if (hit != null && hit != 0L) {
                return hit;
            }

            long set = allocAndWrite(req, setPlan, setLayout);
            m.put(k, set);
            return set;
        }

        // PER_DRAW / NONE
        return allocAndWrite(req, setPlan, setLayout);
    }

    private long allocAndWrite(DescriptorBindRequest req, SetBindingPlan setPlan, long setLayout) {
        long set = runtime.allocDescriptorSetByLayout(req.frameIndex, setLayout);
        if (set == 0L) {
            throw new IllegalStateException("allocDescriptorSetByLayout returned 0");
        }

        if (setPlan.bindings == null || setPlan.bindings.isEmpty()) {
            return set;
        }

        for (BindingPlanEntry e : setPlan.bindings) {
            if (e == null) {
                continue;
            }

            String dtype = (e.descriptorType != null) ? e.descriptorType.toUpperCase() : "";
            if (dtype.contains("COMBINED_IMAGE_SAMPLER")) {
                writeSamplerBinding(req, set, e);
            } else if (dtype.contains("UNIFORM_BUFFER")) {
                writeBufferBinding(req, set, e);
            } else {
                throw new IllegalStateException("Unsupported descriptorType: " + e.descriptorType);
            }
        }
        return set;
    }

    private void writeSamplerBinding(DescriptorBindRequest req, long dstSet, BindingPlanEntry e) {
        Texture texJme = pickTexture(req, e.semantic);
        VkTexture texVk;
        long sampler;

        if (texJme != null) {
            texVk = runtime.getOrCreateVkTexture(texJme);
            sampler = runtime.getOrCreateSampler(texJme);
        } else {
            texVk = runtime.getOrCreateVkTexture(null);
            sampler = (texVk != null) ? texVk.sampler : 0L;
        }

        if (texVk == null || texVk.view == 0L || sampler == 0L) {
            throw new IllegalStateException("Invalid sampler resource for binding=" + e.binding);
        }

        runtime.writeSingleImageToSet(dstSet, e.binding, texVk, sampler);
    }

    private void writeBufferBinding(DescriptorBindRequest req, long dstSet, BindingPlanEntry e) {
        ResourceSemantic semantic = (e.semantic != null) ? e.semantic : ResourceSemantic.UNKNOWN_UBO;

        switch (semantic) {
            case PER_DRAW_UBO:
            case UNKNOWN_UBO:
                if (req.perDrawUboHandle == 0L) {
                    throw new IllegalStateException("perDrawUboHandle == 0 for binding=" + e.binding);
                }
                long range = (req.perDrawUboRange > 0) ? (long) req.perDrawUboRange : 256L;
                runtime.writeSingleBufferToSet(dstSet, e.binding, req.perDrawUboHandle, 0L, range, e.dynamic);
                return;

            default:
                throw new IllegalStateException("Unsupported UBO semantic: " + semantic);
        }
    }

    // ---- key build ----
    private MaterialSetKey buildMaterialKey(DescriptorBindRequest req, SetBindingPlan setPlan, long setLayout) {
        long tex0View = 0L, tex0Samp = 0L;
        long lightView = 0L, lightSamp = 0L;
        long extraView = 0L, extraSamp = 0L;

        // 用 semantic 抽取稳定材质维度
        if (setPlan.bindings != null) {
            for (BindingPlanEntry e : setPlan.bindings) {
                if (e == null) {
                    continue;
                }
                ResourceSemantic s = (e.semantic != null) ? e.semantic : ResourceSemantic.UNKNOWN_SAMPLER;
                if (s == ResourceSemantic.COLOR_MAP) {
                    Texture t = (req.drawCmd != null && req.drawCmd.materialResolvePlan != null && !req.drawCmd.useWhiteTex0)
                            ? req.drawCmd.materialResolvePlan.tex0 : null;
                    VkTexture vk = runtime.getOrCreateVkTexture(t);
                    tex0View = (vk != null) ? vk.view : 0L;
                    tex0Samp = (t != null) ? runtime.getOrCreateSampler(t) : ((vk != null) ? vk.sampler : 0L);
                } else if (s == ResourceSemantic.LIGHT_MAP) {
                    Texture t = (req.drawCmd != null && req.drawCmd.materialResolvePlan != null && !req.drawCmd.useWhiteLight)
                            ? req.drawCmd.materialResolvePlan.light : null;
                    VkTexture vk = runtime.getOrCreateVkTexture(t);
                    lightView = (vk != null) ? vk.view : 0L;
                    lightSamp = (t != null) ? runtime.getOrCreateSampler(t) : ((vk != null) ? vk.sampler : 0L);
                } else if (s == ResourceSemantic.EXTRA_TEX) {
                    Texture t = (req.drawCmd != null && !req.drawCmd.useWhiteExtra) ? req.drawCmd.jmeExtraSnapshot : null;
                    VkTexture vk = runtime.getOrCreateVkTexture(t);
                    extraView = (vk != null) ? vk.view : 0L;
                    extraSamp = (t != null) ? runtime.getOrCreateSampler(t) : ((vk != null) ? vk.sampler : 0L);
                }
            }
        }

        return new MaterialSetKey(setLayout, req.pipelineHash, tex0View, tex0Samp, lightView, lightSamp, extraView, extraSamp);
    }

    private FrameSetKey buildFrameKey(DescriptorBindRequest req, SetBindingPlan setPlan, long setLayout) {
        return new FrameSetKey(
                req.frameIndex,
                setLayout,
                req.pipelineHash,
                req.objectId,
                req.extraTexId
        );
    }

    private static Texture pickTexture(DescriptorBindRequest req, ResourceSemantic semantic) {
        if (req == null || req.drawCmd == null) {
            return null;
        }
        if (semantic == null) {
            semantic = ResourceSemantic.UNKNOWN_SAMPLER;
        }

        switch (semantic) {
            case COLOR_MAP:
                return (req.drawCmd.materialResolvePlan != null && !req.drawCmd.useWhiteTex0)
                        ? req.drawCmd.materialResolvePlan.tex0 : null;
            case LIGHT_MAP:
                return (req.drawCmd.materialResolvePlan != null && !req.drawCmd.useWhiteLight)
                        ? req.drawCmd.materialResolvePlan.light : null;
            case EXTRA_TEX:
                return (!req.drawCmd.useWhiteExtra) ? req.drawCmd.jmeExtraSnapshot : null;
            default:
                if (!req.drawCmd.useWhiteExtra && req.drawCmd.jmeExtraSnapshot != null) {
                    return req.drawCmd.jmeExtraSnapshot;
                }
                if (!req.drawCmd.useWhiteTex0 && req.drawCmd.materialResolvePlan != null) {
                    return req.drawCmd.materialResolvePlan.tex0;
                }
                if (!req.drawCmd.useWhiteLight && req.drawCmd.materialResolvePlan != null) {
                    return req.drawCmd.materialResolvePlan.light;
                }
                return null;
        }
    }

    // ---- key classes ----
    private static final class MaterialSetKey {

        final long setLayout;
        final int pipelineHash;
        final long tex0View, tex0Samp, lightView, lightSamp, extraView, extraSamp;

        MaterialSetKey(long setLayout, int pipelineHash,
                long tex0View, long tex0Samp,
                long lightView, long lightSamp,
                long extraView, long extraSamp) {
            this.setLayout = setLayout;
            this.pipelineHash = pipelineHash;
            this.tex0View = tex0View;
            this.tex0Samp = tex0Samp;
            this.lightView = lightView;
            this.lightSamp = lightSamp;
            this.extraView = extraView;
            this.extraSamp = extraSamp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MaterialSetKey)) {
                return false;
            }
            MaterialSetKey k = (MaterialSetKey) o;
            return setLayout == k.setLayout && pipelineHash == k.pipelineHash
                    && tex0View == k.tex0View && tex0Samp == k.tex0Samp
                    && lightView == k.lightView && lightSamp == k.lightSamp
                    && extraView == k.extraView && extraSamp == k.extraSamp;
        }

        @Override
        public int hashCode() {
            return Objects.hash(setLayout, pipelineHash, tex0View, tex0Samp, lightView, lightSamp, extraView, extraSamp);
        }
    }

    private static final class FrameSetKey {

        final int frameIndex;
        final long setLayout;
        final int pipelineHash;
        final int objectId;
        final int extraTexId;

        FrameSetKey(int frameIndex, long setLayout, int pipelineHash,
                int objectId, int extraTexId) {
            this.frameIndex = frameIndex;
            this.setLayout = setLayout;
            this.pipelineHash = pipelineHash;
            this.objectId = objectId;
            this.extraTexId = extraTexId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FrameSetKey)) {
                return false;
            }
            FrameSetKey k = (FrameSetKey) o;
            return frameIndex == k.frameIndex
                    && setLayout == k.setLayout
                    && pipelineHash == k.pipelineHash
                    && objectId == k.objectId
                    && extraTexId == k.extraTexId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(frameIndex, setLayout, pipelineHash, objectId, extraTexId);
        }
    }

}
