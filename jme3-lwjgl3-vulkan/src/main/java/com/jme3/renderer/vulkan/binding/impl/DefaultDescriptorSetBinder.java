package com.jme3.renderer.vulkan.binding.impl;

import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindRequest;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindResult;
import com.jme3.renderer.vulkan.binding.api.DescriptorSetBinder;
import com.jme3.renderer.vulkan.binding.api.HighFrequencySetProvider;
import com.jme3.renderer.vulkan.binding.cache.FrameSetCache;
import com.jme3.renderer.vulkan.binding.plan.FrequencyLayerRegistry;
import com.jme3.renderer.vulkan.binding.plan.LayeredBindingPlan;
import com.jme3.renderer.vulkan.binding.provider.PlanDrivenSetResolver;
import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.pipeline.VulkanPipeline;
import com.jme3.renderer.vulkan.reflection.BindingPlanEntry;
import com.jme3.renderer.vulkan.reflection.PipelineDescriptorBindingPlan;
import com.jme3.renderer.vulkan.reflection.SetBindingPlan;

import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * DefaultDescriptorSetBinder
 *
 * 设计目标： - 主链路仅使用 PipelineDescriptorBindingPlan（反射计划）驱动。 
 * 
 * @author icyboxs
 */
public final class DefaultDescriptorSetBinder implements DescriptorSetBinder {

    private static final Logger LOGGER = Logger.getLogger(DefaultDescriptorSetBinder.class.getName());

    private final VulkanRuntime runtime;
    @SuppressWarnings("unused")
    private final FrequencyLayerRegistry registry;
    @SuppressWarnings("unused")
    private final FrameSetCache frameSetCache;
    @SuppressWarnings("unused")
    private final LayeredBindingPlan plan;
    private final HighFrequencySetProvider highProvider; // 可空；仅生命周期透传

    private final PlanDrivenSetResolver planDrivenResolver;

    // 兼容旧统计字段（可后续统一替换）
    private long statSet1Bound;
    private long statSet1CacheHit;
    private long statSet1Alloc;
    private long statHighBound;
    private long statHighHit;
    private long statHighAlloc;

    public DefaultDescriptorSetBinder(VulkanRuntime runtime) {
        this(runtime, new FrequencyLayerRegistry(), new FrameSetCache(), null);
    }

    public DefaultDescriptorSetBinder(VulkanRuntime runtime,
            FrequencyLayerRegistry registry,
            FrameSetCache frameSetCache) {
        this(runtime, registry, frameSetCache, null);
    }

    public DefaultDescriptorSetBinder(VulkanRuntime runtime,
            FrequencyLayerRegistry registry,
            FrameSetCache frameSetCache,
            HighFrequencySetProvider highProvider) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime is null");
        }
        this.runtime = runtime;
        this.registry = (registry != null) ? registry : new FrequencyLayerRegistry();
        this.frameSetCache = (frameSetCache != null) ? frameSetCache : new FrameSetCache();
        this.plan = LayeredBindingPlan.from(this.registry);
        this.highProvider = highProvider;
        this.planDrivenResolver = new PlanDrivenSetResolver(runtime);
    }

    @Override
    public DescriptorBindResult bindForDraw(DescriptorBindRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("req is null");
        }

        VulkanPipeline pipeline = req.pipeline;
        DrawCmd dc = req.drawCmd;

        if (pipeline == null) {
            throw new IllegalArgumentException("req.pipeline is null");
        }
        if (dc == null) {
            throw new IllegalArgumentException("req.drawCmd is null");
        }

        int setCount = pipeline.getDescriptorSetLayoutCount();
        if (setCount <= 0) {
            throw new IllegalStateException("pipeline setCount <= 0");
        }

        PipelineDescriptorBindingPlan bindingPlan = runtime.getPipelineBindingPlan(dc.pipelineKey);
        if (bindingPlan == null || bindingPlan.sets == null || bindingPlan.sets.isEmpty()) {
            throw new IllegalStateException("No reflection binding plan for pipeline: " + dc.pipelineKey);
        }

        ArrayList<Integer> setIndices = new ArrayList<>();
        ArrayList<Long> setHandles = new ArrayList<>();
        ArrayList<Integer> dynamicOffsets = new ArrayList<>();
        ArrayList<Integer> dynamicOffsetSetIndices = new ArrayList<>();

        for (SetBindingPlan setPlan : bindingPlan.sets) {
            if (setPlan == null) {
                continue;
            }

            int setIndex = setPlan.setIndex;
            if (setIndex < 0 || setIndex >= setCount) {
                throw new IllegalStateException(
                        "Reflected set out of pipeline range: set=" + setIndex + ", setCount=" + setCount
                );
            }

            long setLayout = pipeline.getDescriptorSetLayoutAt(setIndex);
            if (setLayout == 0L) {
                throw new IllegalStateException("DescriptorSetLayout is 0 for reflected set=" + setIndex);
            }

            long setHandle = resolveSetByPlan(req, pipeline, setPlan);
            if (setHandle == 0L) {
                throw new IllegalStateException("Failed to resolve descriptor set for set=" + setIndex);
            }

            setIndices.add(setIndex);
            setHandles.add(setHandle);

            // 动态偏移：按 setPlan 中 dynamic binding 的数量收集
            for (BindingPlanEntry e : setPlan.bindings) {
                if (e != null && e.dynamic) {
                    dynamicOffsets.add(req.dynamicOffset);
                    dynamicOffsetSetIndices.add(setIndex);
                }
            }
        }

        if (setHandles.isEmpty()) {
            throw new IllegalStateException("No descriptor sets resolved for pipeline");
        }

        return DescriptorBindResult.ofSparse(
                toIntArray(setIndices),
                toLongArray(setHandles),
                toIntArray(dynamicOffsets),
                toIntArray(dynamicOffsetSetIndices)
        );
    }

    private long resolveSetByPlan(DescriptorBindRequest req,
            VulkanPipeline pipeline,
            SetBindingPlan setPlan) {
        if (setPlan == null) {
            return 0L;
        }

        long setLayout = pipeline.getDescriptorSetLayoutAt(setPlan.setIndex);
        if (setLayout == 0L) {
            return 0L;
        }

        return planDrivenResolver.resolveAndWriteSet(req, setPlan, setLayout);
    }

    @Override
    public void beginFrame(int frameIndex) {
        planDrivenResolver.beginFrame(frameIndex); // 新增
        if (highProvider != null) {
            highProvider.beginFrame(frameIndex);
        }
    }

    @Override
    public void cleanup() {
        planDrivenResolver.cleanup(); // 新增
        frameSetCache.clearAll();
        if (highProvider != null) {
            highProvider.cleanup();
        }
    }

    public long getStatSet1Bound() {
        return statSet1Bound;
    }

    public long getStatSet1CacheHit() {
        return statSet1CacheHit;
    }

    public long getStatSet1Alloc() {
        return statSet1Alloc;
    }

    public long getStatHighBound() {
        return statHighBound;
    }

    public long getStatHighHit() {
        return statHighHit;
    }

    public long getStatHighAlloc() {
        return statHighAlloc;
    }

    private static int[] toIntArray(java.util.List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static long[] toLongArray(java.util.List<Long> list) {
        long[] arr = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
