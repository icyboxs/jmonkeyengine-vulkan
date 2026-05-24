package com.jme3.renderer.vulkan.binding.provider;

import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindRequest;
import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.reflection.BindingPlanEntry;
import com.jme3.renderer.vulkan.reflection.CacheClass;
import com.jme3.renderer.vulkan.reflection.ResourceSemantic;
import com.jme3.renderer.vulkan.reflection.SetBindingPlan;
import com.jme3.renderer.vulkan.resource.VkTexture;
import com.jme3.texture.Texture;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public final class PlanDrivenSetResolver {

    private final VulkanRuntime runtime;

    private final Map<MaterialSetKey, Long> materialCache = new HashMap<>();
    private final Map<Integer, HashMap<FrameSetKey, Long>> frameCache = new HashMap<>();

    public PlanDrivenSetResolver(VulkanRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime is null");
        }
        this.runtime = runtime;
    }

    public void beginFrame(int frameIndex) {
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
        if (req == null || setPlan == null || setLayout == 0L) {
            throw new IllegalArgumentException();
        }

        CacheClass cc = (setPlan.cacheClass != null) ? setPlan.cacheClass : CacheClass.NONE;

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
                // 【核心修改】传入 setPlan.setIndex 用于坐标比对
                writeSamplerBinding(req, set, setPlan.setIndex, e);
            } else if (dtype.contains("UNIFORM_BUFFER")) {
                writeBufferBinding(req, set, e);
            } else {
                throw new IllegalStateException("Unsupported descriptorType: " + e.descriptorType);
            }
        }
        return set;
    }

    private void writeSamplerBinding(DescriptorBindRequest req, long dstSet, int setIndex, BindingPlanEntry e) {
        Texture texJme = pickTexture(req, setIndex, e.binding);
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
        if (semantic == ResourceSemantic.PER_DRAW_UBO || semantic == ResourceSemantic.UNKNOWN_UBO) {
            if (req.perDrawUboHandle == 0L) {
                throw new IllegalStateException("perDrawUboHandle == 0");
            }
            long range = (req.perDrawUboRange > 0) ? (long) req.perDrawUboRange : 256L;
            runtime.writeSingleBufferToSet(dstSet, e.binding, req.perDrawUboHandle, 0L, range, e.dynamic);
        } else {
            throw new IllegalStateException("Unsupported UBO semantic: " + semantic);
        }
    }

// 【修改】：使用绝对的 set/binding 物理槽位寻找动态数组里存放的对应贴图
    private static Texture pickTexture(DescriptorBindRequest req, int setIndex, int bindingIndex) {
        if (req == null || req.drawCmd == null) {
            return null;
        }
        DrawCmd cmd = req.drawCmd;

        for (int i = 0; i < cmd.customImageCount; i++) {
            com.jme3.renderer.vulkan.reflection.ParamBindingPlan.BindingSlot slot = cmd.customImageSlots[i];
            if (slot != null && slot.set == setIndex && slot.binding == bindingIndex) {
                Texture t = cmd.customImageTextures[i];
                if (t != null) {
                    return t;
                }
            }
        }

        // 终极保护网：如果没有匹配上，强送第一张基础纹理，杜绝画面丢失
        if (!cmd.useWhiteTex0 && cmd.jmeTex0Snapshot != null) {
            return cmd.jmeTex0Snapshot;
        }
        return null;
    }

    private MaterialSetKey buildMaterialKey(DescriptorBindRequest req, SetBindingPlan setPlan, long setLayout) {
        int samplerCount = 0;
        if (setPlan.bindings != null) {
            for (BindingPlanEntry e : setPlan.bindings) {
                if (e != null && e.semantic == ResourceSemantic.SAMPLED_IMAGE) {
                    samplerCount++;
                }
            }
        }

        long[] views = new long[samplerCount];
        long[] samplers = new long[samplerCount];
        int idx = 0;

        if (setPlan.bindings != null) {
            for (BindingPlanEntry e : setPlan.bindings) {
                if (e == null || e.semantic != ResourceSemantic.SAMPLED_IMAGE) {
                    continue;
                }

                Texture t = pickTexture(req, setPlan.setIndex, e.binding);
                VkTexture vk = runtime.getOrCreateVkTexture(t);

                long view = 0L;
                long samp = 0L;

                if (vk != null) {
                    view = vk.view;
                    samp = (t != null) ? runtime.getOrCreateSampler(t) : vk.sampler;
                } else {
                    vk = runtime.getOrCreateVkTexture(null);
                    if (vk != null) {
                        view = vk.view;
                        samp = vk.sampler;
                    }
                }

                views[idx] = view;
                samplers[idx] = samp;
                idx++;
            }
        }

        return new MaterialSetKey(setLayout, req.pipelineHash, views, samplers);
    }

    private FrameSetKey buildFrameKey(DescriptorBindRequest req, SetBindingPlan setPlan, long setLayout) {
        return new FrameSetKey(req.frameIndex, setLayout, req.pipelineHash, req.objectId, req.extraTexId);
    }

    private static final class MaterialSetKey {

        final long setLayout;
        final int pipelineHash;
        final long[] views;
        final long[] samplers;

        MaterialSetKey(long setLayout, int pipelineHash, long[] views, long[] samplers) {
            this.setLayout = setLayout;
            this.pipelineHash = pipelineHash;
            this.views = views;
            this.samplers = samplers;
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
                    && Arrays.equals(views, k.views) && Arrays.equals(samplers, k.samplers);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(setLayout, pipelineHash);
            result = 31 * result + Arrays.hashCode(views);
            result = 31 * result + Arrays.hashCode(samplers);
            return result;
        }
    }

    private static final class FrameSetKey {

        final int frameIndex;
        final long setLayout;
        final int pipelineHash;
        final int objectId;
        final int extraTexId;

        FrameSetKey(int frameIndex, long setLayout, int pipelineHash, int objectId, int extraTexId) {
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
            return frameIndex == k.frameIndex && setLayout == k.setLayout && pipelineHash == k.pipelineHash
                    && objectId == k.objectId && extraTexId == k.extraTexId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(frameIndex, setLayout, pipelineHash, objectId, extraTexId);
        }
    }
}
