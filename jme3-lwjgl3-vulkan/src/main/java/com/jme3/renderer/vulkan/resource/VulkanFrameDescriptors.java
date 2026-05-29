package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.context.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_FRAGMENTED_POOL;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

public final class VulkanFrameDescriptors {

    private static final Logger LOGGER = Logger.getLogger(VulkanFrameDescriptors.class.getName());

    private final VkContext vk;

    private final int initialMaxSets;
    private final int initialMaxCombinedImageSamplers;

    private int poolMaxSets;
    private int poolMaxCombinedImageSamplers;

    private final List<Long> pools = new ArrayList<>();
    
    private int currentPoolIndex = 0;

    private static final int MAX_POOL_RETAIN_COUNT = 16;

    public VulkanFrameDescriptors(VkContext vk, int maxSets, int maxCombinedImageSamplers) {
        this.vk = vk;
        this.initialMaxSets = Math.max(256, maxSets);
        this.initialMaxCombinedImageSamplers = Math.max(512, maxCombinedImageSamplers);

        this.poolMaxSets = this.initialMaxSets;
        this.poolMaxCombinedImageSamplers = this.initialMaxCombinedImageSamplers;
    }

    public void init() {
        if (!pools.isEmpty()) {
            return;
        }
        pools.add(createPool(poolMaxSets, poolMaxCombinedImageSamplers));
    }

    public void beginFrame() {
        if (pools.isEmpty()) {
            return;
        }

        while (pools.size() > MAX_POOL_RETAIN_COUNT) {
            long poolToDestroy = pools.remove(pools.size() - 1);
            vkDestroyDescriptorPool(vk.device(), poolToDestroy, null);
        }

        for (int i = 0; i < pools.size(); i++) {
            int err = vkResetDescriptorPool(vk.device(), pools.get(i), 0);
        }

        currentPoolIndex = 0;
    }

    public long allocSet(long targetSetLayout) {
        if (pools.isEmpty()) throw new IllegalStateException("not init");
        if (targetSetLayout == 0L) throw new IllegalArgumentException("targetSetLayout is 0");

        while (currentPoolIndex < pools.size()) {
            long ds = tryAllocFromPool(pools.get(currentPoolIndex), targetSetLayout);
            if (ds != 0L) {
                return ds;
            }
            currentPoolIndex++;
        }

        growPools();
        
        long ds = tryAllocFromPool(pools.get(currentPoolIndex), targetSetLayout);
        if (ds == 0L) {
            throw new RuntimeException("vkAllocateDescriptorSets failed after grow");
        }
        return ds;
    }

    public void destroy() {
        for (long p : pools) {
            if (p != 0L) {
                vkDestroyDescriptorPool(vk.device(), p, null);
            }
        }
        pools.clear();
    }

    private long tryAllocFromPool(long pool, long targetSetLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(targetSetLayout));

            LongBuffer pSets = stack.mallocLong(1);
            int err = vkAllocateDescriptorSets(vk.device(), ai, pSets);
            if (err == VK_SUCCESS) {
                return pSets.get(0);
            }

            if (err == VK_ERROR_OUT_OF_POOL_MEMORY || err == VK_ERROR_FRAGMENTED_POOL) {
                return 0L;
            }

            throw new RuntimeException("vkAllocateDescriptorSets failed: " + err);
        }
    }

    private void growPools() {
        poolMaxSets = Math.max(poolMaxSets * 2, initialMaxSets);
        poolMaxCombinedImageSamplers = Math.max(poolMaxCombinedImageSamplers * 2, initialMaxCombinedImageSamplers);

        long p = createPool(poolMaxSets, poolMaxCombinedImageSamplers);
        pools.add(p);
        
        currentPoolIndex = pools.size() - 1;
    }

    private long createPool(int maxSets, int maxCombinedImageSamplers) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int dynamicUboCount = Math.max(maxSets * 4, 4096);
            int combinedSamplerCount = Math.max(maxCombinedImageSamplers, maxSets * 8);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(dynamicUboCount);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(combinedSamplerCount);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(Math.max(maxSets, 512));
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.max(maxSets, 512));

            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(0) 
                    .maxSets(Math.max(maxSets, 1024))
                    .pPoolSizes(sizes);

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreateDescriptorPool(vk.device(), ci, null, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorPool failed: " + err);
            }
            return p.get(0);
        }
    }
}