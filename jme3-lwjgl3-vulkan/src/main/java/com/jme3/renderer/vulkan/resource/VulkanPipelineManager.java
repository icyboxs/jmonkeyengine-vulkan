package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.renderer.vulkan.pipeline.PassKey;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.renderer.vulkan.pipeline.VkShaderKey;
import com.jme3.renderer.vulkan.pipeline.VkVariantKey;
import com.jme3.renderer.vulkan.pipeline.VulkanPipeline;
import com.jme3.renderer.vulkan.reflection.BindingPlanEntry;
import com.jme3.renderer.vulkan.reflection.CacheClass;
import com.jme3.renderer.vulkan.reflection.DynamicBindingRef;
import com.jme3.renderer.vulkan.reflection.ParamBindingPlan;
import com.jme3.renderer.vulkan.reflection.PipelineDescriptorBindingPlan;
import com.jme3.renderer.vulkan.reflection.ResourceSemantic;
import com.jme3.renderer.vulkan.reflection.SetBindingPlan;
import com.jme3.renderer.vulkan.reflection.VkPipelineLayoutSignature;
import com.jme3.renderer.vulkan.reflection.VkReflectionManager;
import com.jme3.renderer.vulkan.reflection.VkReflectionResult;
import com.jme3.renderer.vulkan.runtime.VulkanRuntimeStats;
import com.jme3.renderer.vulkan.shader.ShaderArtifact;
import com.jme3.renderer.vulkan.shader.VulkanShaders;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.io.IOException;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import static org.lwjgl.vulkan.VK10.*;

public final class VulkanPipelineManager {

    private static final Logger LOGGER = Logger.getLogger(VulkanPipelineManager.class.getName());

    private final Map<VkPipelineKey, Integer> pipelinePushConstantSize = new HashMap<>();
    private final Map<VkPipelineKey, Boolean> pipelineHasPushConstants = new HashMap<>();
    private final Map<VkPipelineKey, ParamBindingPlan> pipelineParamPlanCache = new HashMap<>();
    private final Map<VkPipelineKey, PipelineDescriptorBindingPlan> pipelineBindingPlanCache = new HashMap<>();

    private final VkContext vk;
    private final VulkanShaders shaders;
    private final PassKey defaultPassKey;
    private final VulkanRuntimeStats stats;

    private VulkanPipeline basePipeline;
    private final Map<VkPipelineKey, VulkanPipeline> pipelineCache = new HashMap<>();
    private final Map<VkPipelineKey, VkUboLayout> pipelineUboLayoutCache = new HashMap<>();

    private final VkReflectionManager reflectionManager;
    private final Map<VkPipelineLayoutSignature, Long> layoutCache = new HashMap<>();
    private final Map<SetLayoutSignature, Long> descriptorSetLayoutCache = new HashMap<>();

    public VulkanPipelineManager(VkContext vk, VulkanShaders shaders, PassKey defaultPassKey, VulkanRuntimeStats stats) {
        this.vk = vk;
        this.shaders = shaders;
        this.defaultPassKey = defaultPassKey;
        this.stats = stats;
        this.reflectionManager = new VkReflectionManager();
        this.reflectionManager.setStatsSink(new VkReflectionManager.StatsSink() {
            @Override
            public void onHit() {
                stats.reflectionHit++;
                stats.frameReflectionHit++;
            }

            @Override
            public void onMiss() {
                stats.reflectionMiss++;
                stats.frameReflectionMiss++;
            }
        });
    }

    public void init() throws IOException {
        if (basePipeline != null) {
            return;
        }

        VkPipelineKey baseKey = new VkPipelineKey(
                new VkShaderKey(0, 0, new VkVariantKey(false, false, false)),
                defaultPassKey, VK_CULL_MODE_NONE, true, true, VK_COMPARE_OP_LESS_OR_EQUAL, VkPipelineKey.Blend.Off, 0
        );
        pipelinePushConstantSize.put(baseKey, 0);

        long[] baseSetLayouts = buildSetLayoutsForSignature(VkPipelineLayoutSignature.empty());

        basePipeline = new VulkanPipeline(vk, shaders, baseSetLayouts, baseKey, null, null);
        basePipeline.init();
        pipelineCache.put(baseKey, basePipeline);

        layoutCache.put(VkPipelineLayoutSignature.empty(), basePipeline.getPipelineLayout());

        pipelineUboLayoutCache.put(baseKey, VkUboLayout.empty());
        pipelineHasPushConstants.put(baseKey, false);
    }

    public VulkanPipeline getOrCreatePipeline(VkPipelineKey key, String vertSrc, String fragSrc) {
        VulkanPipeline p = pipelineCache.get(key);
        if (p != null) {
            pipelineUboLayoutCache.putIfAbsent(key, VkUboLayout.empty());
            pipelineHasPushConstants.putIfAbsent(key, false);
            return p;
        }

        try {
            ShaderArtifact vertArt = shaders.getOrCreateArtifactFromRawGlsl(vertSrc, VK_SHADER_STAGE_VERTEX_BIT, "pm-vert");
            ShaderArtifact fragArt = shaders.getOrCreateArtifactFromRawGlsl(fragSrc, VK_SHADER_STAGE_FRAGMENT_BIT, "pm-frag");

            VkReflectionResult rr = reflectionManager.reflect(vertArt, fragArt);

            int pcSize = 0;
            if (rr != null && rr.pushConstantRanges != null) {
                for (com.jme3.renderer.vulkan.reflection.VkPushConstantRangeInfo pc : rr.pushConstantRanges) {
                    if (pc != null && pc.size > pcSize) {
                        pcSize = pc.size;
                    }
                }
            }

            PipelineDescriptorBindingPlan bindingPlan = buildPipelineBindingPlan(rr);
            pipelineBindingPlanCache.put(key, bindingPlan);

            pipelinePushConstantSize.put(key, pcSize);
            pipelineParamPlanCache.put(key, buildParamBindingPlan(rr));
            pipelineUboLayoutCache.put(key, VkUboLayout.fromReflection(rr));

            VkPipelineLayoutSignature sig = VkPipelineLayoutSignature.fromReflection(rr);
            pipelineHasPushConstants.put(key, (sig != null && sig.pushConstants != null && !sig.pushConstants.isEmpty()));

            long[] setLayouts = buildSetLayoutsForSignature(sig);

            long chosenLayout = layoutCache.getOrDefault(sig, 0L);
            if (chosenLayout == 0L) {
                stats.layoutMiss++;
                stats.frameLayoutMiss++;
                chosenLayout = createPipelineLayoutWithPushConstants(sig, setLayouts);
                layoutCache.put(sig, chosenLayout);
            } else {
                stats.layoutHit++;
                stats.frameLayoutHit++;
            }

            VulkanPipeline np = new VulkanPipeline(
                    vk, shaders, setLayouts, key, chosenLayout, vertSrc, fragSrc
            );
            np.init();
            pipelineCache.put(key, np);

            LOGGER.info("[BindingPlan] key=" + key.hashCode() + " plan=" + bindingPlan);
            return np;

        } catch (IOException e) {
            throw new RuntimeException("Failed to create pipeline for key", e);
        }
    }

    private long createPipelineLayoutWithPushConstants(VkPipelineLayoutSignature sig, long[] setLayouts) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer setLayoutsBuf = stack.mallocLong(setLayouts.length);
            for (long l : setLayouts) {
                setLayoutsBuf.put(l);
            }
            setLayoutsBuf.flip();

            VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(setLayoutsBuf);

            if (sig != null && sig.pushConstants != null && !sig.pushConstants.isEmpty()) {
                VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(sig.pushConstants.size(), stack);
                for (int i = 0; i < sig.pushConstants.size(); i++) {
                    VkPipelineLayoutSignature.PushConstantSig s = sig.pushConstants.get(i);
                    pcr.get(i).offset(s.offset).size(s.size).stageFlags(s.stageFlags);
                }
                ci.pPushConstantRanges(pcr);
            }

            LongBuffer p = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vk.device(), ci, null, p) != VK_SUCCESS) {
                throw new RuntimeException("vkCreatePipelineLayout failed");
            }
            return p.get(0);
        }
    }

    public VulkanPipeline getBasePipeline() {
        return basePipeline;
    }

    public void destroy() {
        // 1) 先销毁所有 graphics pipeline（不在这里销毁 pipelineLayout，避免重复）
        for (VulkanPipeline p : pipelineCache.values()) {
            if (p != null) {
                p.destroy(false);
            }
        }
        pipelineCache.clear();

        // 2) 销毁所有缓存的 pipeline layout（唯一可信来源：layoutCache）
        if (vk != null && vk.device() != null) {
            java.util.HashSet<Long> destroyed = new java.util.HashSet<>();
            for (Long layout : layoutCache.values()) {
                if (layout != null && layout != 0L && destroyed.add(layout)) {
                    vkDestroyPipelineLayout(vk.device(), layout, null);
                }
            }
        }
        layoutCache.clear();
        basePipeline = null;

        // 3) 销毁 descriptor set layouts
        if (vk != null && vk.device() != null) {
            for (long layout : descriptorSetLayoutCache.values()) {
                if (layout != 0L) {
                    vkDestroyDescriptorSetLayout(vk.device(), layout, null);
                }
            }
        }
        descriptorSetLayoutCache.clear();

        // 4) 清理其他缓存
        pipelineUboLayoutCache.clear();
        pipelineHasPushConstants.clear();
        pipelineParamPlanCache.clear();
        pipelinePushConstantSize.clear();
        pipelineBindingPlanCache.clear();
    }

    public VkUboLayout getUboLayout(VkPipelineKey key) {
        VkUboLayout l = pipelineUboLayoutCache.get(key);
        return (l != null) ? l : VkUboLayout.empty();
    }

    public boolean hasPushConstants(VkPipelineKey key) {
        return key != null && pipelineHasPushConstants.getOrDefault(key, false);
    }

    public ParamBindingPlan getParamBindingPlan(VkPipelineKey key) {
        return pipelineParamPlanCache.getOrDefault(key, new ParamBindingPlan());
    }

    public int getPushConstantSize(VkPipelineKey key) {
        return pipelinePushConstantSize.getOrDefault(key, 0);
    }

    public PipelineDescriptorBindingPlan getBindingPlan(VkPipelineKey key) {
        PipelineDescriptorBindingPlan p = pipelineBindingPlanCache.get(key);
        return (p != null) ? p : PipelineDescriptorBindingPlan.empty();
    }

    private long[] buildSetLayoutsForSignature(VkPipelineLayoutSignature sig) {
        int maxSet = 0;
        if (sig != null && sig.bindings != null) {
            for (VkPipelineLayoutSignature.BindingSig b : sig.bindings) {
                if (b != null && b.set > maxSet) {
                    maxSet = b.set;
                }
            }
        }

        int setCount = Math.max(1, maxSet + 1);
        long[] layouts = new long[setCount];

        for (int i = 0; i < setCount; i++) {
            List<VkPipelineLayoutSignature.BindingSig> setBindings = new ArrayList<>();
            if (sig != null && sig.bindings != null) {
                for (VkPipelineLayoutSignature.BindingSig b : sig.bindings) {
                    if (b != null && b.set == i) {
                        setBindings.add(b);
                    }
                }
            }
            layouts[i] = getOrCreateDescriptorSetLayout(i, setBindings);
        }
        return layouts;
    }

    private long getOrCreateDescriptorSetLayout(int setIndex, List<VkPipelineLayoutSignature.BindingSig> bindings) {
        SetLayoutSignature sigKey = new SetLayoutSignature(bindings);
        Long cached = descriptorSetLayoutCache.get(sigKey);
        if (cached != null) {
            return cached;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();

            if (!bindings.isEmpty()) {
                VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
                for (int i = 0; i < bindings.size(); i++) {
                    VkPipelineLayoutSignature.BindingSig bs = bindings.get(i);
                    b.get(i).binding(bs.binding).descriptorType(parseDescriptorType(bs.type))
                            .descriptorCount(1).stageFlags(bs.stageFlags);
                }
                ci.pBindings(b);
            }

            LongBuffer p = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(vk.device(), ci, null, p) != VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorSetLayout failed");
            }
            descriptorSetLayoutCache.put(sigKey, p.get(0));
            return p.get(0);
        }
    }

    private static int parseDescriptorType(String type) {
        if (type == null) {
            return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
        switch (type.toUpperCase()) {
            case "UNIFORM_BUFFER_DYNAMIC":
                return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
            case "UNIFORM_BUFFER":
                return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case "COMBINED_IMAGE_SAMPLER":
                return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            default:
                return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        }
    }

    private ParamBindingPlan buildParamBindingPlan(VkReflectionResult rr) {
        ParamBindingPlan p = new ParamBindingPlan();
        if (rr == null || rr.descriptorBindings == null) {
            return p;
        }

        for (VkReflectionResult.DescriptorBinding b : rr.descriptorBindings) {
            if (b == null) {
                continue;
            }
            String t = (b.type != null) ? b.type.toUpperCase() : "";
            ParamBindingPlan.BindingSlot slot = new ParamBindingPlan.BindingSlot(b.set, b.binding, b.type);

            if (t.contains("COMBINED_IMAGE_SAMPLER")) {
                ParamBindingPlan.BindingSlot extTex = p.getTextureSlot(b.set);
                if (extTex == null || b.binding < extTex.binding) {
                    p.setTextureSlot(b.set, slot);
                }

                // 【核心修复】：绝对限制只有真实的贴图才能放入 samplerByName！
                String norm = ParamBindingPlan.normalizeParamName((b.name != null) ? b.name : "");
                if (!norm.isEmpty()) {
                    p.samplerByName.put(norm, slot);
                }

            } else if (t.contains("UNIFORM_BUFFER")) {
                ParamBindingPlan.BindingSlot extUbo = p.getUboSlot(b.set);
                if (extUbo == null || b.binding < extUbo.binding) {
                    p.setUboSlot(b.set, slot);
                }
            }
        }
        return p;
    }

    private static final class SetLayoutSignature {

        private final List<VkPipelineLayoutSignature.BindingSig> bindings;

        public SetLayoutSignature(List<VkPipelineLayoutSignature.BindingSig> bindings) {
            this.bindings = new ArrayList<>(bindings);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SetLayoutSignature)) {
                return false;
            }
            return Objects.equals(bindings, ((SetLayoutSignature) o).bindings);
        }

        @Override
        public int hashCode() {
            return Objects.hash(bindings);
        }
    }

    private PipelineDescriptorBindingPlan buildPipelineBindingPlan(VkReflectionResult rr) {
        if (rr == null || rr.descriptorBindings == null || rr.descriptorBindings.isEmpty()) {
            return PipelineDescriptorBindingPlan.empty();
        }

        Map<Integer, List<BindingPlanEntry>> bySet = new HashMap<>();

        for (VkReflectionResult.DescriptorBinding b : rr.descriptorBindings) {
            if (b == null) {
                continue;
            }

            String type = (b.type != null) ? b.type.toUpperCase() : "";
            boolean dynamic = type.contains("DYNAMIC");
            ResourceSemantic semantic = classifySemantic(b);

            BindingPlanEntry e = new BindingPlanEntry(
                    b.binding,
                    b.type,
                    semantic,
                    dynamic,
                    b.stageFlags,
                    b.name
            );

            bySet.computeIfAbsent(b.set, k -> new ArrayList<>()).add(e);
        }

        List<Integer> setIndices = new ArrayList<>(bySet.keySet());
        setIndices.sort(Integer::compareTo);

        List<SetBindingPlan> setPlans = new ArrayList<>();
        List<DynamicBindingRef> dynamicOrder = new ArrayList<>();

        for (Integer setIndex : setIndices) {
            List<BindingPlanEntry> entries = bySet.get(setIndex);
            if (entries == null) {
                continue;
            }

            entries.sort((a, b) -> Integer.compare(a.binding, b.binding));
            CacheClass cacheClass = chooseCacheClass(entries);

            setPlans.add(new SetBindingPlan(setIndex, entries, cacheClass));

            for (BindingPlanEntry e : entries) {
                if (e.dynamic) {
                    dynamicOrder.add(new DynamicBindingRef(setIndex, e.binding));
                }
            }
        }

        return new PipelineDescriptorBindingPlan(setPlans, dynamicOrder);
    }

    private static ResourceSemantic classifySemantic(VkReflectionResult.DescriptorBinding b) {
        String type = (b.type != null) ? b.type.toUpperCase() : "";
        String normName = ParamBindingPlan.normalizeParamName(b.name);

        if (type.contains("UNIFORM_BUFFER")) {
            if ("jmeuniforms".equals(normName) || "perdraw".equals(normName)) {
                return ResourceSemantic.PER_DRAW_UBO;
            }
            if ("alphaparams".equals(normName)) {
                return ResourceSemantic.ALPHA_PARAMS;
            }
            if ("desaturationparams".equals(normName)) {
                return ResourceSemantic.DESATURATION_PARAMS;
            }
            if (b.set == 0 && b.binding == 0) {
                return ResourceSemantic.PER_DRAW_UBO;
            }
            return ResourceSemantic.UNKNOWN_UBO;
        }

        // 废除 COLOR_MAP 等死板枚举
        if (type.contains("COMBINED_IMAGE_SAMPLER")) {
            return ResourceSemantic.SAMPLED_IMAGE;
        }
        return ResourceSemantic.UNKNOWN_SAMPLER;
    }

    private static CacheClass chooseCacheClass(List<BindingPlanEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return CacheClass.NONE;
        }

        boolean hasDynamic = false;
        boolean hasPerDraw = false;
        boolean hasAnyUbo = false;
        boolean hasMaterial = false;

        for (BindingPlanEntry e : entries) {
            if (e == null) {
                continue;
            }
            String dt = (e.descriptorType != null) ? e.descriptorType.toUpperCase() : "";

            if (e.dynamic) {
                hasDynamic = true;
            }
            if (dt.contains("UNIFORM_BUFFER")) {
                hasAnyUbo = true;
            }
            // 只要里面包含了贴图，就具备材质持久缓存潜力
            if (e.semantic == ResourceSemantic.SAMPLED_IMAGE) {
                hasMaterial = true;
            }

            if (e.semantic == ResourceSemantic.PER_DRAW_UBO || e.semantic == ResourceSemantic.UNKNOWN_UBO
                    || e.semantic == ResourceSemantic.ALPHA_PARAMS || e.semantic == ResourceSemantic.DESATURATION_PARAMS) {
                hasPerDraw = true;
            }
        }

        if (hasDynamic || hasPerDraw || hasAnyUbo) {
            return CacheClass.PER_DRAW;
        }
        if (hasMaterial) {
            return CacheClass.MATERIAL;
        }

        return CacheClass.NONE;
    }

}
