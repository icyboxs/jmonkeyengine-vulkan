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
                writeSamplerBinding(req, set, setPlan.setIndex, e);
            } else if (dtype.contains("UNIFORM_BUFFER")) {
                writeBufferBinding(req, set, e);
            } else if (dtype.contains("STORAGE_BUFFER")) {
                writeStorageBufferBinding(req, set, e);
            } else if (dtype.contains("STORAGE_IMAGE")) {
                writeStorageImageBinding(req, set, e);
            } else {
                throw new IllegalStateException("Unsupported descriptorType: " + e.descriptorType);
            }
        }
        return set;
    }

    private void writeStorageBufferBinding(DescriptorBindRequest req, long dstSet, BindingPlanEntry e) {
        com.jme3.shader.bufferobject.BufferObject bo = null;
        if (req.computeCmd != null && e.binding < 16) {
            bo = req.computeCmd.ssbos[e.binding];
        } else if (req.drawCmd != null && e.binding < 16) {
            bo = req.drawCmd.ssbos[e.binding];
        }

        if (bo != null) {
            com.jme3.renderer.vulkan.resource.VkBuffer vkBuf = runtime.getOrCreateBufferObject(bo);
            if (vkBuf != null && vkBuf.handle != 0L) {
                runtime.writeStorageBufferToSet(dstSet, e.binding, vkBuf.handle, 0L, vkBuf.capacity);
            }
        }
    }

    private void writeStorageImageBinding(DescriptorBindRequest req, long dstSet, BindingPlanEntry e) {
        com.jme3.texture.TextureImage ti = null;
        if (req.computeCmd != null && e.binding < 16) {
            ti = req.computeCmd.images[e.binding];
        } else if (req.drawCmd != null && e.binding < 16) {
            ti = req.drawCmd.images[e.binding];
        }

        if (ti != null) {
            Texture tex = extractTexture(ti);
            if (tex != null) {
                VkTexture vkTex = runtime.getOrCreateVkTexture(tex);
                if (vkTex != null && vkTex.view != 0L) {
                    runtime.writeStorageImageToSet(dstSet, e.binding, vkTex);
                }
            }
        }
    }

    private Texture extractTexture(Object ti) {
        if (ti == null) {
            return null;
        }
        if (ti instanceof Texture) {
            return (Texture) ti;
        }
        try {
            return (Texture) ti.getClass().getMethod("getTexture").invoke(ti);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeSamplerBinding(DescriptorBindRequest req, long dstSet, int setIndex, BindingPlanEntry e) {
        Texture texJme = pickTexture(req, setIndex, e.binding);
        if (texJme == null && req.computeCmd != null && e.binding < 16) {
            texJme = extractTexture(req.computeCmd.images[e.binding]);
        }

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
                if (t == null && req.computeCmd != null && e.binding < 16) {
                    t = extractTexture(req.computeCmd.images[e.binding]);
                }

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
        boolean onlyDynamicUbo = true;
        if (setPlan.bindings != null) {
            for (com.jme3.renderer.vulkan.reflection.BindingPlanEntry e : setPlan.bindings) {
                // 如果存在非 Dynamic 绑定的内容 (比如 SSBO)，则不能全局共享
                if (!e.dynamic) {
                    onlyDynamicUbo = false;
                    break;
                }
            }
        }

        // 如果该 Set 里只包含了 DYNAMIC UBO，那么这个 Descriptor Set 的内容是全局固定的！
        // 剥离 objectId，让同管线的所有对象共享这唯一一个 Set 句柄，彻底消除分配开销！
        int objId = onlyDynamicUbo ? 0 : req.objectId;
        int texExt = onlyDynamicUbo ? 0 : req.extraTexId;

        return new FrameSetKey(req.frameIndex, setLayout, req.pipelineHash, objId, texExt);
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
