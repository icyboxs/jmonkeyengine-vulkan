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
import com.jme3.renderer.vulkan.cmd.ComputeCmd;
import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.pipeline.VulkanPipeline;
import com.jme3.renderer.vulkan.reflection.BindingPlanEntry;
import com.jme3.renderer.vulkan.reflection.PipelineDescriptorBindingPlan;
import com.jme3.renderer.vulkan.reflection.SetBindingPlan;

import java.util.ArrayList;

public final class DefaultDescriptorSetBinder implements DescriptorSetBinder {

    private final VulkanRuntime runtime;
    private final FrequencyLayerRegistry registry;
    private final FrameSetCache frameSetCache;
    private final LayeredBindingPlan plan;
    private final HighFrequencySetProvider highProvider;

    private final PlanDrivenSetResolver planDrivenResolver;

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
        if (req == null) throw new IllegalArgumentException("req is null");

        VulkanPipeline pipeline = req.pipeline;
        DrawCmd dc = req.drawCmd;

        if (pipeline == null || dc == null) throw new IllegalArgumentException("req missing pipeline/drawCmd");

        int setCount = pipeline.getDescriptorSetLayoutCount();
        if (setCount <= 0) throw new IllegalStateException("pipeline setCount <= 0");

        PipelineDescriptorBindingPlan bindingPlan = runtime.getPipelineBindingPlan(dc.pipelineKey);
        if (bindingPlan == null || bindingPlan.sets == null || bindingPlan.sets.isEmpty()) {
            throw new IllegalStateException("No reflection binding plan for pipeline: " + dc.pipelineKey);
        }

        return processBindingPlan(req, pipeline, setCount, bindingPlan);
    }
    
    @Override
    public DescriptorBindResult bindForCompute(DescriptorBindRequest req) {
        if (req == null) throw new IllegalArgumentException("req is null");

        VulkanPipeline pipeline = req.pipeline;
        ComputeCmd cc = req.computeCmd;

        if (pipeline == null || cc == null) throw new IllegalArgumentException("req missing pipeline/computeCmd");

        int setCount = pipeline.getDescriptorSetLayoutCount();
        if (setCount <= 0) return new DescriptorBindResult(0, new long[0], new int[0]);

        PipelineDescriptorBindingPlan bindingPlan = runtime.getComputeBindingPlan(cc.pipelineKey);
        if (bindingPlan == null || bindingPlan.sets == null || bindingPlan.sets.isEmpty()) {
            return new DescriptorBindResult(0, new long[0], new int[0]);
        }

        return processBindingPlan(req, pipeline, setCount, bindingPlan);
    }

    private DescriptorBindResult processBindingPlan(DescriptorBindRequest req, VulkanPipeline pipeline, int setCount, PipelineDescriptorBindingPlan bindingPlan) {
        ArrayList<Integer> setIndices = new ArrayList<>();
        ArrayList<Long> setHandles = new ArrayList<>();
        ArrayList<Integer> dynamicOffsets = new ArrayList<>();
        ArrayList<Integer> dynamicOffsetSetIndices = new ArrayList<>();

        for (SetBindingPlan setPlan : bindingPlan.sets) {
            if (setPlan == null) continue;
            int setIndex = setPlan.setIndex;
            if (setIndex < 0 || setIndex >= setCount) continue;

            long setLayout = pipeline.getDescriptorSetLayoutAt(setIndex);
            if (setLayout == 0L) continue;

            long setHandle = planDrivenResolver.resolveAndWriteSet(req, setPlan, setLayout);
            if (setHandle == 0L) throw new IllegalStateException("Failed to resolve descriptor set for set=" + setIndex);

            setIndices.add(setIndex);
            setHandles.add(setHandle);

            for (BindingPlanEntry e : setPlan.bindings) {
                if (e != null && e.dynamic) {
                    dynamicOffsets.add(req.dynamicOffset);
                    dynamicOffsetSetIndices.add(setIndex);
                }
            }
        }

        return DescriptorBindResult.ofSparse(
                toIntArray(setIndices),
                toLongArray(setHandles),
                toIntArray(dynamicOffsets),
                toIntArray(dynamicOffsetSetIndices)
        );
    }

    @Override
    public void beginFrame(int frameIndex) {
        planDrivenResolver.beginFrame(frameIndex);
        if (highProvider != null) {
            highProvider.beginFrame(frameIndex);
        }
    }

    @Override
    public void cleanup() {
        planDrivenResolver.cleanup();
        frameSetCache.clearAll();
        if (highProvider != null) {
            highProvider.cleanup();
        }
    }

    public long getStatSet1Bound() { return statSet1Bound; }
    public long getStatSet1CacheHit() { return statSet1CacheHit; }
    public long getStatSet1Alloc() { return statSet1Alloc; }
    public long getStatHighBound() { return statHighBound; }
    public long getStatHighHit() { return statHighHit; }
    public long getStatHighAlloc() { return statHighAlloc; }

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