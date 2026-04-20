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

/**
 * 每帧描述符集分配器 (高水位线优化版)
 * * 核心优化：
 * 1. 引入 High Watermark 策略：避免渲染负载瞬间波动时暴力销毁 Pool 导致反复重建引发卡顿。
 * 2. 引入 currentPoolIndex 游标：实现 O(1) 极速分配，避免在前面的 Pool 填满时引发 O(n) 的无效分配尝试。
 */
public final class VulkanFrameDescriptors {

    private static final Logger LOGGER = Logger.getLogger(VulkanFrameDescriptors.class.getName());

    private final VkContext vk;

    // 初始预算
    private final int initialMaxSets;
    private final int initialMaxCombinedImageSamplers;

    // 当前池增长预算
    private int poolMaxSets;
    private int poolMaxCombinedImageSamplers;

    // 当前拥有的所有 descriptor pools
    private final List<Long> pools = new ArrayList<>();
    
    // 【优化 1】：记录当前正在向哪个 Pool 分配的游标，避免遍历已满的 Pool
    private int currentPoolIndex = 0;

    // 【优化 2】：高水位线，最多在内存中保留多少个闲置的 Pool
    // 设为 16 意味着即使当前帧负载瞬间掉下来，我们也保留这 16 个已分配的池，
    // 以备下一波渲染高峰（如大量动态对象突然进入视野）能直接复用，避免昂贵的显存分配。
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

        // 1. 高水位线缓慢回收：只有超过安全保留阈值的多余 Pool 才会被销毁，防止极端的内存泄漏
        while (pools.size() > MAX_POOL_RETAIN_COUNT) {
            long poolToDestroy = pools.remove(pools.size() - 1);
            vkDestroyDescriptorPool(vk.device(), poolToDestroy, null);
            LOGGER.fine("Shrinked descriptor pool (High Watermark reached), remaining: " + pools.size());
        }

        // 2. 极速重置当前保留的所有 Pool（vkResetDescriptorPool 几乎是零开销的）
        for (int i = 0; i < pools.size(); i++) {
            int err = vkResetDescriptorPool(vk.device(), pools.get(i), 0);
            if (err != VK_SUCCESS) {
                LOGGER.warning("Failed to reset descriptor pool: " + err);
            }
        }

        // 3. 将分配游标清零，这帧从第 0 个 Pool 开始按序分配
        currentPoolIndex = 0;
    }

    public long allocSet(long targetSetLayout) {
        if (pools.isEmpty()) {
            throw new IllegalStateException("not init");
        }
        if (targetSetLayout == 0L) {
            throw new IllegalArgumentException("targetSetLayout is 0");
        }

        // 循环尝试从当前指向的 Pool 开始分配
        while (currentPoolIndex < pools.size()) {
            long ds = tryAllocFromPool(pools.get(currentPoolIndex), targetSetLayout);
            if (ds != 0L) {
                return ds; // 分配成功，直接返回
            }
            // 走到这里说明当前池子满了（VK_ERROR_OUT_OF_POOL_MEMORY），指针直接推进到下一个池子
            currentPoolIndex++;
        }

        // 如果指针越界了，说明之前保留的所有池子全部被填满了，需要扩容新建
        growPools();
        
        // 在最新扩容的池子上分配
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

    // ---------- internal ----------
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
        
        // 扩容后，让游标立刻指向这个新创建的池子
        currentPoolIndex = pools.size() - 1;
    }

    private long createPool(int maxSets, int maxCombinedImageSamplers) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 预估合理的描述符数量，避免池子碎片化
            int dynamicUboCount = Math.max(maxSets * 4, 4096);
            int combinedSamplerCount = Math.max(maxCombinedImageSamplers, maxSets * 8);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .descriptorCount(dynamicUboCount);
            sizes.get(1)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(combinedSamplerCount);

            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    // 注意：flags 必须保持为 0，不能有 VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT，
                    // 这样才能支持全局的 vkResetDescriptorPool 极速回收。
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