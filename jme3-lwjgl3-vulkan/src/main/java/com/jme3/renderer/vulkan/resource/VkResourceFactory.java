package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.context.VkContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public final class VkResourceFactory {

    private static final Logger LOGGER = Logger.getLogger(VkResourceFactory.class.getName());

    private final VkContext vk;
    private long transferCommandPool;
    private VkCommandBuffer activeBatchCmd = null;
    private final List<VkBuffer> pendingStagingBuffers = new ArrayList<>();

    public VkResourceFactory(VkContext vk) {
        this.vk = vk;
    }

    public void beginTransferBatch() {
        if (activeBatchCmd != null) {
            throw new IllegalStateException("A transfer batch is already in progress.");
        }
        activeBatchCmd = beginSingleTimeCommands();
    }

    public void endTransferBatch() {
        if (activeBatchCmd == null) {
            throw new IllegalStateException("No transfer batch in progress.");
        }
        endSingleTimeCommands(activeBatchCmd);
        activeBatchCmd = null;

        for (VkBuffer stagingBuffer : pendingStagingBuffers) {
            destroyBuffer(stagingBuffer);
        }
        pendingStagingBuffers.clear();
    }

    private VkCommandBuffer getOrCreateTransferCmd() {
        return activeBatchCmd != null ? activeBatchCmd : beginSingleTimeCommands();
    }

    private void flushOrKeepTransferCmd(VkCommandBuffer cmd) {
        if (activeBatchCmd == null) {
            endSingleTimeCommands(cmd);
        }
    }

    public void destroyStagingBuffer(VkBuffer stagingBuffer) {
        if (activeBatchCmd != null) {
            pendingStagingBuffers.add(stagingBuffer);
        } else {
            destroyBuffer(stagingBuffer);
        }
    }

    public VkBuffer createBuffer(long size, int usage, int properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            boolean isHostVisible = (properties & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;

            if (isHostVisible) {
                allocInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST);
                allocInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
            } else {
                allocInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            }

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);

            int err = vmaCreateBuffer(vk.vmaAllocator(), ci, allocInfo, pBuffer, pAllocation, null);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vmaCreateBuffer failed: " + err);
            }

            VkBuffer b = new VkBuffer();
            b.handle = pBuffer.get(0);
            b.memory = pAllocation.get(0);
            b.capacity = size;             
            b.isHostVisible = isHostVisible; 
            return b;
        }
    }

    public void destroyBuffer(VkBuffer b) {
        if (b == null) return;
        if (b.handle != 0 && b.memory != 0) {
            vmaDestroyBuffer(vk.vmaAllocator(), b.handle, b.memory);
        }
        b.handle = 0;
        b.memory = 0;
    }

    public long createImageView(long image, int format, int aspect, boolean swizzleABGR) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);

            // =========================================================
            // 【核心修正】：正确映射 JME3 的 ABGR8 内存顺序到 Shader RGBA 
            // 内存：[A, B, G, R]
            // 当作 R8G8B8A8 加载后：Vulkan R = A, G = B, B = G, A = R
            // =========================================================
            if (swizzleABGR) {
                ci.components(c -> c
                        .r(VK_COMPONENT_SWIZZLE_A) // Shader.r 读取底层的 R 通道 (也就是真实像素的 R)
                        .g(VK_COMPONENT_SWIZZLE_B) // Shader.g 读取底层的 G 通道 (也就是真实像素的 G)
                        .b(VK_COMPONENT_SWIZZLE_G) // Shader.b 读取底层的 B 通道 (也就是真实像素的 B)
                        .a(VK_COMPONENT_SWIZZLE_R) // Shader.a 读取底层的 A 通道 (也就是真实像素的 A)
                );
            } else {
                ci.components(c -> c
                        .r(VK_COMPONENT_SWIZZLE_IDENTITY)
                        .g(VK_COMPONENT_SWIZZLE_IDENTITY)
                        .b(VK_COMPONENT_SWIZZLE_IDENTITY)
                        .a(VK_COMPONENT_SWIZZLE_IDENTITY)
                );
            }
            ci.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);

            LongBuffer pV = stack.mallocLong(1);
            vkCreateImageView(vk.device(), ci, null, pV);
            return pV.get(0);
        }
    }

    public long createImageView(long image, int format, int aspect) {
        return createImageView(image, format, aspect, false);
    }

    public VkDepthResources createDepth(int w, int h, int depthFormat) {
        VkImageAlloc img = createImage2D(w, h, depthFormat, VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        long view = createImageView(img.image, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT);
        VkDepthResources d = new VkDepthResources();
        d.image = img.image; d.memory = img.memory; d.view = view;
        return d;
    }

    public void destroyDepth(VkDepthResources d) {
        if (d == null) return;
        if (d.view != 0) vkDestroyImageView(vk.device(), d.view, null);
        if (d.image != 0 && d.memory != 0) vmaDestroyImage(vk.vmaAllocator(), d.image, d.memory);
        d.view = d.image = d.memory = 0;
    }

    public void writeToMemory(long vmaAllocation, ByteBuffer src) {
        PointerBuffer p = memAllocPointer(1);
        vmaMapMemory(vk.vmaAllocator(), vmaAllocation, p);
        memCopy(memAddress(src), p.get(0), src.remaining());
        vmaUnmapMemory(vk.vmaAllocator(), vmaAllocation);
        memFree(p);
    }

    public void writeToMemory(long vmaAllocation, Buffer src, int byteSize) {
        PointerBuffer p = memAllocPointer(1);
        vmaMapMemory(vk.vmaAllocator(), vmaAllocation, p);
        long dst = p.get(0);

        if (src instanceof ByteBuffer) {
            ByteBuffer bb = (ByteBuffer) src;
            memCopy(memAddress(bb) + bb.position(), dst, byteSize);
        } else if (src instanceof FloatBuffer) {
            FloatBuffer fb = (FloatBuffer) src;
            memCopy(memAddress(fb) + ((long) fb.position() * 4L), dst, byteSize);
        } else if (src instanceof IntBuffer) {
            IntBuffer ib = (IntBuffer) src;
            memCopy(memAddress(ib) + ((long) ib.position() * 4L), dst, byteSize);
        } else if (src instanceof ShortBuffer) {
            ShortBuffer sb = (ShortBuffer) src;
            memCopy(memAddress(sb) + ((long) sb.position() * 2L), dst, byteSize);
        } else {
            vmaUnmapMemory(vk.vmaAllocator(), vmaAllocation);
            memFree(p);
            throw new UnsupportedOperationException("Unsupported buffer type: " + src.getClass());
        }

        vmaUnmapMemory(vk.vmaAllocator(), vmaAllocation);
        memFree(p);
    }

    public VkTexture create1x1Rgba8Texture(int r, int g, int b, int a) {
        VkImageAlloc img = createImage2D(1, 1, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TILING_LINEAR,
                VK_IMAGE_USAGE_SAMPLED_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        PointerBuffer pp = memAllocPointer(1);
        vmaMapMemory(vk.vmaAllocator(), img.memory, pp);
        long ptr = pp.get(0);
        memPutByte(ptr + 0, (byte) r); memPutByte(ptr + 1, (byte) g);
        memPutByte(ptr + 2, (byte) b); memPutByte(ptr + 3, (byte) a);
        vmaUnmapMemory(vk.vmaAllocator(), img.memory);
        memFree(pp);

        VkCommandBuffer cmd = getOrCreateTransferCmd();
        transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        flushOrKeepTransferCmd(cmd);

        long view = createImageView(img.image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT);
        long sampler = createSamplerDefault();

        VkTexture tex = new VkTexture();
        tex.image = img.image; tex.memory = img.memory; tex.view = view; tex.sampler = sampler;
        tex.width = 1; tex.height = 1;
        return tex;
    }

    public void destroyTexture(VkTexture t) {
        if (t == null) return;
        if (t.sampler != 0) vkDestroySampler(vk.device(), t.sampler, null);
        if (t.view != 0) vkDestroyImageView(vk.device(), t.view, null);
        if (t.image != 0 && t.memory != 0) vmaDestroyImage(vk.vmaAllocator(), t.image, t.memory);
        t.sampler = t.view = t.image = t.memory = 0;
    }

    private void ensureTransferCommandPool() {
        if (transferCommandPool != 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queueFamilyIndex(vk.queueFamilyIndex())
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreateCommandPool(vk.device(), ci, null, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateCommandPool(transfer) failed: " + err);
            }
            transferCommandPool = p.get(0);
        }
    }

    private VkCommandBuffer beginSingleTimeCommands() {
        ensureTransferCommandPool();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(transferCommandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pCmd = stack.mallocPointer(1);
            int err = vkAllocateCommandBuffers(vk.device(), ai, pCmd);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkAllocateCommandBuffers(transfer) failed: " + err);
            }

            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), vk.device());

            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            err = vkBeginCommandBuffer(cmd, bi);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkBeginCommandBuffer(transfer) failed: " + err);
            }
            return cmd;
        }
    }

    private void endSingleTimeCommands(VkCommandBuffer cmd) {
        vkEndCommandBuffer(cmd);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(0);

            LongBuffer pFence = stack.mallocLong(1);
            int err = vkCreateFence(vk.device(), fenceInfo, null, pFence);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateFence failed: " + err);
            }
            long fence = pFence.get(0);

            VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(cmd));

            err = vkQueueSubmit(vk.queue(), si, fence);
            if (err != VK_SUCCESS) {
                vkDestroyFence(vk.device(), fence, null);
                throw new RuntimeException("vkQueueSubmit(transfer) failed: " + err);
            }

            err = vkWaitForFences(vk.device(), fence, true, Long.MAX_VALUE);
            if (err != VK_SUCCESS) {
                vkDestroyFence(vk.device(), fence, null);
                throw new RuntimeException("vkWaitForFences failed: " + err);
            }

            vkDestroyFence(vk.device(), fence, null);
            vkFreeCommandBuffers(vk.device(), transferCommandPool, cmd);
        }
    }

    private long createSamplerDefault() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .maxAnisotropy(1.0f)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                    .unnormalizedCoordinates(false);

            LongBuffer pSampler = stack.mallocLong(1);
            int err = vkCreateSampler(vk.device(), sci, null, pSampler);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateSampler failed: " + err);
            }
            return pSampler.get(0);
        }
    }

    private static final class VkImageAlloc {
        long image;
        long memory; 
    }

    private VkImageAlloc createImage2D(int w, int h, int format, int tiling, int usage, int properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(e -> e.set(w, h, 1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(tiling)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);

            if ((properties & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
                allocInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST);
                allocInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            } else {
                allocInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            }

            LongBuffer pImg = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);

            int err = vmaCreateImage(vk.vmaAllocator(), ici, allocInfo, pImg, pAlloc, null);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vmaCreateImage failed: " + err);
            }

            VkImageAlloc out = new VkImageAlloc();
            out.image = pImg.get(0);
            out.memory = pAlloc.get(0); 
            return out;
        }
    }

    private void transitionImageLayout(VkCommandBuffer cmd, long image, int oldLayout, int newLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);

            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            int srcStage;
            int dstStage;

            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;

            } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

            } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                    && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

            } else {
                throw new UnsupportedOperationException("Unsupported layout transition: " + oldLayout + " -> " + newLayout);
            }

            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    private void copyBufferToImage(VkCommandBuffer cmd, long buffer, long image, int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0);
            region.bufferRowLength(0);
            region.bufferImageHeight(0);

            region.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);

            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(w, h, 1);

            vkCmdCopyBufferToImage(cmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
    }

    public VkTexture createTexture2DFromBuffer(ByteBuffer pixels, int w, int h, int vkFormat, boolean swizzleABGR) {
        if (pixels == null) {
            throw new IllegalArgumentException("pixels is null");
        }

        int expected = pixels.remaining();

        VkBuffer staging = createBuffer(
                expected,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        writeToMemory(staging.memory, pixels, expected);

        VkImageAlloc img = createImage2D(
                w, h,
                vkFormat, 
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );

        VkCommandBuffer cmd = getOrCreateTransferCmd();
        transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        copyBufferToImage(cmd, staging.handle, img.image, w, h);
        transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        flushOrKeepTransferCmd(cmd);

        destroyStagingBuffer(staging);

        long view = createImageView(img.image, vkFormat, VK_IMAGE_ASPECT_COLOR_BIT, swizzleABGR); 
        long sampler = createSamplerDefault();

        VkTexture tex = new VkTexture();
        tex.image = img.image;
        tex.memory = img.memory;
        tex.view = view;
        tex.sampler = sampler;
        tex.width = w;
        tex.height = h;
        return tex;
    }

    public void destroy() {
        if (transferCommandPool != 0) {
            vkDestroyCommandPool(vk.device(), transferCommandPool, null);
            transferCommandPool = 0;
        }
    }

    public VkTexture createColorAttachmentTexture(int w, int h, int format) {
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;

        VkImageAlloc img = createImage2D(
                w, h, format,
                VK_IMAGE_TILING_OPTIMAL,
                usage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );

        long view = createImageView(img.image, format, VK_IMAGE_ASPECT_COLOR_BIT);

        VkTexture t = new VkTexture();
        t.image = img.image;
        t.memory = img.memory;
        t.view = view;
        t.sampler = 0L;
        t.width = w;
        t.height = h;
        return t;
    }

    public void copyBuffer(VkBuffer srcBuffer, VkBuffer dstBuffer, long size) {
        VkCommandBuffer cmd = getOrCreateTransferCmd();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.srcOffset(0);
            copyRegion.dstOffset(0);
            copyRegion.size(size);

            vkCmdCopyBuffer(cmd, srcBuffer.handle, dstBuffer.handle, copyRegion);
        }
        flushOrKeepTransferCmd(cmd);
    }
}