package com.jme3.renderer.vulkan.frame;

import com.jme3.math.ColorRGBA;
import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.renderer.vulkan.resource.VkBuffer;
import com.jme3.renderer.vulkan.resource.VkResourceFactory;
import com.jme3.renderer.vulkan.resource.VkTexture;
import com.jme3.renderer.vulkan.resource.VkUboLayout;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.logging.Logger;

import static org.lwjgl.system.MemoryUtil.memAllocPointer;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.util.vma.Vma.vmaFlushAllocation;
import static org.lwjgl.util.vma.Vma.vmaMapMemory;
import static org.lwjgl.util.vma.Vma.vmaUnmapMemory;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanDescriptors {

    private static final Logger LOGGER = Logger.getLogger(VulkanDescriptors.class.getName());

    // 复用临时矩阵数组，避免每 draw 分配
    private final float[] tmpMat16 = new float[16];

    private final VkContext vk;
    private final VkResourceFactory rf;
    private final int framesInFlight;

    private VkBuffer ubo;
    private VkTexture whiteTex;

    // 持久化映射指针
    private long uboMappedPointer = 0L;

    private static final int DEFAULT_PER_DRAW_ALIGNMENT = 256;
    private static final int DEFAULT_PER_DRAW_SLICE_SIZE = 256;
    private static final int PER_FRAME_MAX_DRAWS = 65536;

    private int perDrawAlignment = DEFAULT_PER_DRAW_ALIGNMENT;
    private int perDrawSliceSize = DEFAULT_PER_DRAW_SLICE_SIZE;
    private int perFrameBytes = PER_FRAME_MAX_DRAWS * DEFAULT_PER_DRAW_SLICE_SIZE;

    private int[] headBytes;

    public VulkanDescriptors(VkContext vk, VkResourceFactory rf, int framesInFlight) {
        if (vk == null) {
            throw new IllegalArgumentException("vk is null");
        }
        if (rf == null) {
            throw new IllegalArgumentException("rf is null");
        }
        if (framesInFlight <= 0) {
            throw new IllegalArgumentException("framesInFlight must be > 0");
        }
        this.vk = vk;
        this.rf = rf;
        this.framesInFlight = framesInFlight;
    }

    public void init(VkTexture whiteTex) {
        this.whiteTex = whiteTex;

        int totalSize = framesInFlight * perFrameBytes;
        ubo = rf.createBuffer(
                totalSize,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        if (ubo == null || ubo.memory == 0L || ubo.handle == 0L) {
            throw new IllegalStateException("createBuffer failed for UBO");
        }

        headBytes = new int[framesInFlight];

        PointerBuffer p = memAllocPointer(1);
        try {
            // [VMA 修改]: 使用 vmaMapMemory 代替 vkMapMemory
            int err = vmaMapMemory(vk.vmaAllocator(), ubo.memory, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vmaMapMemory failed on init, err=" + err);
            }
            uboMappedPointer = p.get(0);
            if (uboMappedPointer == 0L) {
                throw new IllegalStateException("vmaMapMemory returned null pointer");
            }
        } finally {
            memFree(p);
        }
    }

    public VkBuffer getUbo() {
        return ubo;
    }

    public void destroy() {
        if (ubo != null) {
            if (uboMappedPointer != 0L) {
                // [VMA 修改]: 使用 vmaUnmapMemory 代替 vkUnmapMemory
                vmaUnmapMemory(vk.vmaAllocator(), ubo.memory);
                uboMappedPointer = 0L;
            }
            rf.destroyBuffer(ubo);
            ubo = null;
        }
        whiteTex = null;
        headBytes = null;
    }

    public void beginFrame(int frameIndex) {
        checkFrameIndex(frameIndex);
        headBytes[frameIndex] = 0;
    }

    public int allocPerDraw(int frameIndex) {
        checkFrameIndex(frameIndex);
        int head = headBytes[frameIndex];

        if ((head % perDrawAlignment) != 0) {
            throw new AssertionError("unaligned head=" + head + ", alignment=" + perDrawAlignment);
        }

        if (head + perDrawSliceSize > perFrameBytes) {
            throw new IllegalStateException(
                    "UBO overflow: frame=" + frameIndex
                    + ", head=" + head
                    + ", slice=" + perDrawSliceSize
                    + ", perFrame=" + perFrameBytes
                    + ", drawsUsed=" + (head / Math.max(1, perDrawSliceSize))
            );
        }

        headBytes[frameIndex] = head + perDrawSliceSize;
        return head;
    }

    public void writePerDraw(
            int frameIndex,
            int dynamicOffsetWithinFrame,
            float[] uboData, // 【修改点】：直接接收拍平好的纯二进制浮点数组
            int fbWidth,
            int fbHeight,
            float timeSeconds,
            VkUboLayout layout
    ) {
        checkFrameIndex(frameIndex);
        if (layout == null) {
            throw new IllegalArgumentException("layout is null");
        }
        if (ubo == null || uboMappedPointer == 0L) {
            throw new IllegalStateException("UBO not initialized or not mapped");
        }

        ensurePerDrawSliceSizeAtLeast(layout.sliceSize);

        if (dynamicOffsetWithinFrame < 0) {
            throw new IllegalArgumentException("dynamicOffsetWithinFrame < 0: " + dynamicOffsetWithinFrame);
        }
        if (dynamicOffsetWithinFrame + layout.sliceSize > perFrameBytes) {
            throw new IllegalArgumentException(
                    "dynamic offset out of frame range: off=" + dynamicOffsetWithinFrame
                    + ", slice=" + layout.sliceSize
                    + ", perFrameBytes=" + perFrameBytes);
        }

        long base = frameBaseBytes(frameIndex) + (long) dynamicOffsetWithinFrame;
        long mapped = uboMappedPointer + frameBaseBytes(frameIndex) + (long) dynamicOffsetWithinFrame;

        // 【极速批处理】：利用 NIO 视图实现一次性 JNI bulk-copy 写入
        if (uboData != null) {
            int floatCount = layout.sliceSize / 4;
            java.nio.FloatBuffer dstFb = org.lwjgl.system.MemoryUtil.memFloatBuffer(mapped, floatCount);
            dstFb.put(uboData, 0, Math.min(uboData.length, floatCount));
        }

        // 2. 补漏机制：针对每帧刷新的全局系统数据
        if (layout.offResolution >= 0) {
            long dst = mapped + layout.offResolution;
            memPutFloat(dst, (float) fbWidth);
            memPutFloat(dst + 4, (float) fbHeight);
            memPutFloat(dst + 8, 0f);
            memPutFloat(dst + 12, 0f);
        }

        if (layout.offTime >= 0) {
            long dst = mapped + layout.offTime;
            memPutFloat(dst, timeSeconds);
            memPutFloat(dst + 4, 0f);
            memPutFloat(dst + 8, 0f);
            memPutFloat(dst + 12, 0f);
        }
    }

    private void checkFrameIndex(int frameIndex) {
        if (headBytes == null) {
            throw new IllegalStateException("VulkanDescriptors not initialized");
        }
        if (frameIndex < 0 || frameIndex >= framesInFlight) {
            throw new IllegalArgumentException(
                    "frameIndex out of range: " + frameIndex + ", framesInFlight=" + framesInFlight);
        }
    }

    private long frameBaseBytes(int frameIndex) {
        return (long) frameIndex * perFrameBytes;
    }

    public void setPerDrawAlignment(int alignment) {
        int a = Math.max(16, alignment > 0 ? alignment : DEFAULT_PER_DRAW_ALIGNMENT);
        this.perDrawAlignment = a;
        this.perDrawSliceSize = alignUp(this.perDrawSliceSize, this.perDrawAlignment);
        this.perFrameBytes = PER_FRAME_MAX_DRAWS * this.perDrawSliceSize;
    }

    public void ensurePerDrawSliceSizeAtLeast(int requiredSliceSize) {
        int req = Math.max(16, requiredSliceSize);
        int alignedReq = alignUp(req, perDrawAlignment);
        if (alignedReq > perDrawSliceSize) {
            perDrawSliceSize = alignedReq;
            perFrameBytes = PER_FRAME_MAX_DRAWS * perDrawSliceSize;
        }
    }

    private static int alignUp(int v, int a) {
        return a <= 0 ? v : ((v + a - 1) / a) * a;
    }

    public void writeSingleSampledImage(long dstSet, int dstBinding, VkTexture tex, long sampler) {
        if (dstSet == 0L) {
            throw new IllegalArgumentException("dstSet == 0");
        }
        if (dstBinding < 0) {
            throw new IllegalArgumentException("dstBinding < 0: " + dstBinding);
        }
        if (tex == null) {
            throw new IllegalArgumentException("tex == null");
        }
        if (tex.view == 0L) {
            throw new IllegalArgumentException("tex.view == 0");
        }

        long useSampler = (sampler != 0L) ? sampler : tex.sampler;
        if (useSampler == 0L) {
            throw new IllegalArgumentException("sampler == 0 and tex.sampler == 0");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(useSampler)
                    .imageView(tex.view)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(1, stack);
            wr.get(0)
                    .sType$Default()
                    .dstSet(dstSet)
                    .dstBinding(dstBinding)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(ii);

            vkUpdateDescriptorSets(vk.device(), wr, null);
        }
    }

    public void writeSingleBufferToSet(
            long dstSet,
            int dstBinding,
            long buffer,
            long offset,
            long range,
            boolean dynamic
    ) {
        if (dstSet == 0L) {
            throw new IllegalArgumentException("dstSet == 0");
        }
        if (dstBinding < 0) {
            throw new IllegalArgumentException("dstBinding < 0: " + dstBinding);
        }
        if (buffer == 0L) {
            throw new IllegalArgumentException("buffer == 0");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset < 0: " + offset);
        }
        if (range <= 0) {
            throw new IllegalArgumentException("range <= 0: " + range);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer)
                    .offset(offset)
                    .range(range);

            int dtype = dynamic ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;

            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(1, stack);
            wr.get(0)
                    .sType$Default()
                    .dstSet(dstSet)
                    .dstBinding(dstBinding)
                    .dstArrayElement(0)
                    .descriptorType(dtype)
                    .descriptorCount(1)
                    .pBufferInfo(bi);

            vkUpdateDescriptorSets(vk.device(), wr, null);
        }
    }
    public void writeSingleStorageBufferToSet(long dstSet, int dstBinding, long buffer, long offset, long range) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDescriptorBufferInfo.Buffer bi = org.lwjgl.vulkan.VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer).offset(offset).range(range);

            org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer wr = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
            wr.get(0).sType$Default().dstSet(dstSet).dstBinding(dstBinding).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).pBufferInfo(bi);

            vkUpdateDescriptorSets(vk.device(), wr, null);
        }
    }

    public void writeSingleStorageImageToSet(long dstSet, int dstBinding, VkTexture tex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDescriptorImageInfo.Buffer ii = org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(tex.view).imageLayout(tex.imageLayout); // 使用挂载了正确图像数据的 Layout 枚举进行匹配

            org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer wr = org.lwjgl.vulkan.VkWriteDescriptorSet.calloc(1, stack);
            wr.get(0).sType$Default().dstSet(dstSet).dstBinding(dstBinding).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).pImageInfo(ii);

            vkUpdateDescriptorSets(vk.device(), wr, null);
        }
    }
}
