package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.renderer.vulkan.runtime.VulkanRuntimeStats;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

public final class VulkanMaterialDescriptors {

    private final VkContext vk;
    private int poolMaxSets;
    private final int initialMaxSets;
    private final List<Long> pools = new ArrayList<>();
    private final VulkanRuntimeStats stats;
    private long growCount = 0;

    public VulkanMaterialDescriptors(VkContext vk, int initialMaxSets, VulkanRuntimeStats stats) {
        this.vk = vk;
        this.initialMaxSets = initialMaxSets;
        this.poolMaxSets = initialMaxSets;
        this.stats = stats;
    }

    public void init() {
        if (!pools.isEmpty()) return;
        pools.add(createPool(poolMaxSets));
    }

    public long allocSet(long targetSetLayout) {
        if (pools.isEmpty()) throw new IllegalStateException("not init");
        long ds = tryAllocFromPool(pools.get(pools.size() - 1), targetSetLayout);
        if (ds != 0L) return ds;
        growPools();
        ds = tryAllocFromPool(pools.get(pools.size() - 1), targetSetLayout);
        if (ds == 0L) throw new RuntimeException("fail");
        return ds;
    }

    public void destroy() {
        for (long p : pools) if (p != 0L) vkDestroyDescriptorPool(vk.device(), p, null);
        pools.clear();
    }

    private void growPools() {
        poolMaxSets = Math.max(poolMaxSets * 2, initialMaxSets);
        pools.add(createPool(poolMaxSets));
        growCount++;
        if (stats != null) stats.materialPoolGrow = growCount;
    }

    private long tryAllocFromPool(long pool, long targetSetLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(targetSetLayout));
            LongBuffer pSets = stack.mallocLong(1);
            int err = vkAllocateDescriptorSets(vk.device(), ai, pSets);
            if (err == VK_SUCCESS) return pSets.get(0);
            if (err == VK_ERROR_OUT_OF_POOL_MEMORY || err == VK_ERROR_FRAGMENTED_POOL) return 0L;
            throw new RuntimeException("fail");
        }
    }

    private long createPool(int maxSets) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(maxSets);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(maxSets * 2);

            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().flags(0).maxSets(maxSets).pPoolSizes(sizes);

            LongBuffer p = stack.mallocLong(1);
            if (vkCreateDescriptorPool(vk.device(), ci, null, p) != VK_SUCCESS) throw new RuntimeException("fail");
            return p.get(0);
        }
    }
    public long getGrowCount() { return growCount; }
}
