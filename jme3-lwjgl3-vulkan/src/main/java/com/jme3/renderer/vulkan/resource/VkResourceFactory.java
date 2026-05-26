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

/**
 * Vulkan 资源工厂类。 负责集中管理 Vulkan 缓冲区(Buffer)、图像(Image)、纹理(Texture)及深度资源的创建与销毁。
 * 内部集成了 VMA (Vulkan Memory Allocator) 进行高效的内存管理，并封装了数据传输(Transfer)的批处理逻辑。
 */
public final class VkResourceFactory {

    private static final Logger LOGGER = Logger.getLogger(VkResourceFactory.class.getName());

    private final VkContext vk;
    // 用于执行单次数据传输命令(如 CPU 到 GPU 拷贝)的指令池
    private long transferCommandPool;
    // 当前处于活跃状态的批处理命令缓冲区
    private VkCommandBuffer activeBatchCmd = null;
    // 待销毁的中转缓冲区(Staging Buffers)列表，等待批处理命令执行完毕后统一释放
    private final List<VkBuffer> pendingStagingBuffers = new ArrayList<>();

    public VkResourceFactory(VkContext vk) {
        this.vk = vk;
    }

    /**
     * 开启数据传输的批处理模式。 在此模式下，多次数据上传/拷贝操作会记录到同一个 CommandBuffer 中，
     * 避免频繁提交队列，提升如动态网格(Dynamic Mesh)更新或批量纹理加载时的性能。
     */
    public void beginTransferBatch() {
        if (activeBatchCmd != null) {
            throw new IllegalStateException("A transfer batch is already in progress.");
        }
        activeBatchCmd = beginSingleTimeCommands();
    }

    /**
     * 结束数据传输批处理，提交命令到 GPU 执行并阻塞等待完成。 完成后统一清理在此期间使用过的中转缓冲区(Staging Buffer)。
     */
    public void endTransferBatch() {
        if (activeBatchCmd == null) {
            throw new IllegalStateException("No transfer batch in progress.");
        }
        endSingleTimeCommands(activeBatchCmd);
        activeBatchCmd = null;

        // 统一销毁已完成使命的中转缓冲区
        for (VkBuffer stagingBuffer : pendingStagingBuffers) {
            destroyBuffer(stagingBuffer);
        }
        pendingStagingBuffers.clear();
    }

    /**
     * 获取当前的命令缓冲区。如果在批处理模式下则复用，否则创建一个新的一次性命令缓冲区。
     */
    private VkCommandBuffer getOrCreateTransferCmd() {
        return activeBatchCmd != null ? activeBatchCmd : beginSingleTimeCommands();
    }

    /**
     * 如果不在批处理模式，则立即提交执行该命令缓冲区；如果在批处理模式，则保留以待后续统一提交。
     */
    private void flushOrKeepTransferCmd(VkCommandBuffer cmd) {
        if (activeBatchCmd == null) {
            endSingleTimeCommands(cmd);
        }
    }

    /**
     * 销毁中转缓冲区。如果正处于批处理状态，则延迟到批处理提交后销毁，防止 GPU 还在读取时内存被释放。
     */
    public void destroyStagingBuffer(VkBuffer stagingBuffer) {
        if (activeBatchCmd != null) {
            pendingStagingBuffers.add(stagingBuffer);
        } else {
            destroyBuffer(stagingBuffer);
        }
    }

    /**
     * 创建 Vulkan 缓冲区 (如 VBO, IBO, UBO)。
     *
     * * @param size 缓冲区字节大小
     * @param usage 缓冲区的用途标志位 (如 VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
     * @param properties 内存属性要求 (如 HOST_VISIBLE 或 DEVICE_LOCAL)
     * @return 封装了句柄和 VMA 内存分配信息的 VkBuffer
     */
    public VkBuffer createBuffer(long size, int usage, int properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            // 判断是否需要 CPU 可见(通常用于频繁更新的数据或 Staging Buffer)
            boolean isHostVisible = (properties & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;

            if (isHostVisible) {
                allocInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST);
                // 允许内存映射，且优化为顺序写入
                allocInfo.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
            } else {
                // GPU 本地内存(VRAM)，访问速度最快，但 CPU 无法直接写入
                allocInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            }

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);

            // 使用 VMA 分配内存并绑定到 Buffer
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

    /**
     * 销毁缓冲区并释放对应的 VMA 内存。
     */
    public void destroyBuffer(VkBuffer b) {
        if (b == null) {
            return;
        }
        if (b.handle != 0 && b.memory != 0) {
            vmaDestroyBuffer(vk.vmaAllocator(), b.handle, b.memory);
        }
        b.handle = 0;
        b.memory = 0;
    }

    /**
     * 为 Vulkan 图像创建视图(ImageView)，视图定义了如何访问图像数据(格式、层级、映射等)。
     */
    public long createImageView(long image, int format, int aspect) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);

            // =========================================================
            // 统一使用原样映射，因为我们在 CPU 端已保证数据是标准的 RGBA8 顺序
            // =========================================================
            ci.components(c -> c
                    .r(VK_COMPONENT_SWIZZLE_IDENTITY)
                    .g(VK_COMPONENT_SWIZZLE_IDENTITY)
                    .b(VK_COMPONENT_SWIZZLE_IDENTITY)
                    .a(VK_COMPONENT_SWIZZLE_IDENTITY)
            );

            ci.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);

            LongBuffer pV = stack.mallocLong(1);
            vkCreateImageView(vk.device(), ci, null, pV);
            return pV.get(0);
        }
    }

    /**
     * 创建深度/模板附件资源。
     */
    public VkDepthResources createDepth(int w, int h, int depthFormat) {
        // 标记该图像可用作深度附件，并支持后续的数据拷贝传输
        int usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        // 必须分配为 DEVICE_LOCAL，确保深度测试在 GPU 上全速运行
        VkImageAlloc img = createImage2D(w, h, depthFormat, VK_IMAGE_TILING_OPTIMAL, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        long view = createImageView(img.image, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT);

        VkDepthResources d = new VkDepthResources();
        d.image = img.image;
        d.memory = img.memory;
        d.view = view;
        return d;
    }

    public void destroyDepth(VkDepthResources d) {
        if (d == null) {
            return;
        }
        if (d.view != 0) {
            vkDestroyImageView(vk.device(), d.view, null);
        }
        if (d.image != 0 && d.memory != 0) {
            vmaDestroyImage(vk.vmaAllocator(), d.image, d.memory);
        }
        d.view = d.image = d.memory = 0;
    }

    /**
     * 将 CPU 端 ByteBuffer 数据直接写入由 VMA 管理的宿主可见内存。
     */
    public void writeToMemory(long vmaAllocation, ByteBuffer src) {
        PointerBuffer p = memAllocPointer(1);
        vmaMapMemory(vk.vmaAllocator(), vmaAllocation, p);
        memCopy(memAddress(src), p.get(0), src.remaining());
        vmaUnmapMemory(vk.vmaAllocator(), vmaAllocation);
        memFree(p);
    }

    /**
     * 通用的内存写入方法，支持多种 NIO Buffer 类型。
     */
    public void writeToMemory(long vmaAllocation, Buffer src, int byteSize) {
        PointerBuffer p = memAllocPointer(1);
        // 获取 VMA 内存块在 CPU 端的虚拟内存地址
        vmaMapMemory(vk.vmaAllocator(), vmaAllocation, p);
        long dst = p.get(0);

        // 根据 Buffer 类型计算正确的偏移量进行指针拷贝
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

    /**
     * 创建一个单像素 (1x1) 的纹理，通常用作默认材质或占位符。
     */
    public VkTexture create1x1Rgba8Texture(int r, int g, int b, int a) {
        // 对于 1x1 纹理，直接创建主机可见(HOST_VISIBLE)和线性平铺(LINEAR)的图像即可，省略了 Staging Buffer 拷贝过程
        VkImageAlloc img = createImage2D(1, 1, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TILING_LINEAR,
                VK_IMAGE_USAGE_SAMPLED_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        PointerBuffer pp = memAllocPointer(1);
        vmaMapMemory(vk.vmaAllocator(), img.memory, pp);
        long ptr = pp.get(0);
        memPutByte(ptr + 0, (byte) r);
        memPutByte(ptr + 1, (byte) g);
        memPutByte(ptr + 2, (byte) b);
        memPutByte(ptr + 3, (byte) a);
        vmaUnmapMemory(vk.vmaAllocator(), img.memory);
        memFree(pp);

        VkCommandBuffer cmd = getOrCreateTransferCmd();
        // 将图像布局从 UNDEFINED 转换为着色器只读最优布局(SHADER_READ_ONLY_OPTIMAL)
        transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        flushOrKeepTransferCmd(cmd);

        long view = createImageView(img.image, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT);
        long sampler = createSamplerDefault();

        VkTexture tex = new VkTexture();
        tex.image = img.image;
        tex.memory = img.memory;
        tex.view = view;
        tex.sampler = sampler;
        tex.width = 1;
        tex.height = 1;
        return tex;
    }

    public void destroyTexture(VkTexture t) {
        if (t == null) {
            return;
        }
        if (t.sampler != 0) {
            vkDestroySampler(vk.device(), t.sampler, null);
        }
        if (t.view != 0) {
            vkDestroyImageView(vk.device(), t.view, null);
        }
        if (t.image != 0 && t.memory != 0) {
            vmaDestroyImage(vk.vmaAllocator(), t.image, t.memory);
        }
        t.sampler = t.view = t.image = t.memory = 0;
    }

    /**
     * 确保传输命令池已初始化。由于传输操作生命周期短，使用 TRANSIENT_BIT 进行优化。
     */
    private void ensureTransferCommandPool() {
        if (transferCommandPool != 0) {
            return;
        }
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

    /**
     * 开始记录单次执行(One-Time Submit)的命令缓冲区。
     */
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

            // 告诉驱动这是一个一次性提交的指令，驱动会进行相应优化
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

    /**
     * 结束并提交单次执行命令缓冲区。 此方法会创建栅栏(Fence)并阻塞当前 CPU 线程，直到 GPU 执行完毕。
     */
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

            // 阻塞 CPU 等待 GPU 队列完成该指令
            err = vkWaitForFences(vk.device(), fence, true, Long.MAX_VALUE);
            if (err != VK_SUCCESS) {
                vkDestroyFence(vk.device(), fence, null);
                throw new RuntimeException("vkWaitForFences failed: " + err);
            }

            // 执行完毕，清理栅栏并释放 CommandBuffer
            vkDestroyFence(vk.device(), fence, null);
            vkFreeCommandBuffers(vk.device(), transferCommandPool, cmd);
        }
    }

    /**
     * 创建默认的纹理采样器 (双线性过滤，边缘重复，各向异性为1.0)。
     */
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

    /**
     * 底层调用 VMA 创建 2D 图像对象。
     */
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
                    .tiling(tiling) // OPTIMAL 代表显卡内部优化的不透明内存排列，LINEAR 为行优先排列
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

    /**
     * 录制图像布局转换指令(Pipeline Barrier)。 Vulkan 要求图像在被特定管线阶段访问前，必须显式转换为适当的布局。
     */
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

            // 情景 1：未定义状态 -> 数据传输目的地 (用于准备接受 Buffer 到 Image 的拷贝)
            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;

                // 情景 2：传输完成 -> 着色器只读最优 (用于准备在 Fragment Shader 中进行采样)
            } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

                // 情景 3：未定义状态 -> 着色器只读最优 (通常用于上面纯颜色填充的 1x1 纹理)
            } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                    && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

            } else {
                throw new UnsupportedOperationException("Unsupported layout transition: " + oldLayout + " -> " + newLayout);
            }

            // 插入管线屏障，同步执行阶段
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    /**
     * 将 Buffer 缓冲区数据拷贝到 Image 中。
     */
    private void copyBufferToImage(VkCommandBuffer cmd, long buffer, long image, int w, int h) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0);
            // 0 表示紧密排列
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

    /**
     * 将 CPU 端的像素 Buffer 数据转化为最终可供 GPU 采样的 Vulkan 2D 纹理。 标准的高性能流程：CPU Buffer ->
     * 中转缓冲区(Staging Buffer) -> GPU VRAM Image。
     */
    public VkTexture createTexture2DFromBuffer(ByteBuffer pixels, int w, int h, int vkFormat) {
        if (pixels == null) {
            throw new IllegalArgumentException("pixels is null");
        }

        int expected = pixels.remaining();

        // 1. 创建 CPU 可见的中转缓冲区并写入数据
        VkBuffer staging = createBuffer(
                expected,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        writeToMemory(staging.memory, pixels, expected);

        // 2. 创建 GPU 本地的目标图像 (DEVICE_LOCAL, TILING_OPTIMAL 读写性能最好)
        VkImageAlloc img = createImage2D(
                w, h,
                vkFormat,
                VK_IMAGE_TILING_OPTIMAL,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, // 允许作为传输目标和采样器源
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );

        VkCommandBuffer cmd = getOrCreateTransferCmd();

        // 3. 布局转换：Undefined -> Transfer_Dst (准备接收拷贝数据)
        transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        // 4. 将数据从中转缓冲区拷贝至 GPU 图像内存
        copyBufferToImage(cmd, staging.handle, img.image, w, h);

        // 5. 布局转换：Transfer_Dst -> Shader_ReadOnly (准备供 Shader 进行纹理采样)
        transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        // 如果处于批处理模式则保留，否则立刻提交队列阻塞等待完成
        flushOrKeepTransferCmd(cmd);

        // 提交清理 Staging 内存
        destroyStagingBuffer(staging);

        // 6. 创建 ImageView 和 Sampler 供管线绑定使用
        long view = createImageView(img.image, vkFormat, VK_IMAGE_ASPECT_COLOR_BIT);
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

    /**
     * 创建用于 Framebuffer 绑定的颜色附件 (Color Attachment)。
     */
    public VkTexture createColorAttachmentTexture(int w, int h, int format) {
        // 包含 COLOR_ATTACHMENT_BIT，允许用于 RenderPass 目标
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

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
        t.sampler = 0L; // 颜色附件默认不绑定特定的采样器
        t.width = w;
        t.height = h;
        return t;
    }

    /**
     * 缓冲区间的显存拷贝 (如 CPU 端 StagingBuffer 拷贝至 GPU 端 VBO/IBO)。
     */
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

    /**
     * 同步将显存图像读取到 CPU 的 ByteBuffer，并完成 Y 轴翻转及格式转换。
     */
    public void downloadImagePixels(long srcImage, int srcLayout, int width, int height, ByteBuffer destBuf, com.jme3.texture.Image.Format destFormat, boolean isVulkanBGRA) {
        long size = (long) width * height * 4L; // 底层总是拉取完整的 32bit RGBA/BGRA

        // 1. 创建 CPU 可见的中转缓冲
        VkBuffer staging = createBuffer(
                size,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );

        // 2. 录制转移指令
        VkCommandBuffer cmd = beginSingleTimeCommands();
        transitionImageLayoutForRead(cmd, srcImage, srcLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(width, height, 1);

            vkCmdCopyImageToBuffer(cmd, srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.handle, region);
        }

        // 恢复图像原有的布局以供后续管线正常渲染
        transitionImageLayoutForRead(cmd, srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, srcLayout);

        // 同步等待 GPU 复制完成
        endSingleTimeCommands(cmd);

        // 3. 映射缓冲到 CPU
        PointerBuffer p = memAllocPointer(1);
        vmaMapMemory(vk.vmaAllocator(), staging.memory, p);
        ByteBuffer mapped = memByteBuffer(p.get(0), (int) size);

        // 4. 解析目标格式参数
        int bytesPerPixel = 4;
        int rowBytesVulkan = width * bytesPerPixel;
        int destBytesPerPixel;

        switch (destFormat) {
            case RGB8:
                destBytesPerPixel = 3;
                break;
            case RGBA8:
                destBytesPerPixel = 4;
                break;
            case BGR8:
                destBytesPerPixel = 3;
                break;
            case ABGR8:
                destBytesPerPixel = 4;
                break;
            case Luminance8:
                destBytesPerPixel = 1;
                break;
            case Luminance8Alpha8:
                destBytesPerPixel = 2;
                break;
            default:
                destBytesPerPixel = 4;
                break;
        }

        int rowBytesJme = width * destBytesPerPixel;
        int startPos = destBuf.position();

        // 5. Y 轴翻转与像素格式重组
        for (int y = 0; y < height; y++) {
            // jME3 (OpenGL 体系) 期望数据是从底部向上排列的
            int srcY = height - 1 - y;
            int srcOffset = srcY * rowBytesVulkan;
            int dstOffset = startPos + y * rowBytesJme;

            for (int x = 0; x < width; x++) {
                int srcPx = srcOffset + x * bytesPerPixel;
                int dstPx = dstOffset + x * destBytesPerPixel;

                // 防止因 jME3 Buffer 分配稍小越界
                if (dstPx + destBytesPerPixel > destBuf.capacity()) {
                    continue;
                }

                byte v0 = mapped.get(srcPx + 0);
                byte v1 = mapped.get(srcPx + 1);
                byte v2 = mapped.get(srcPx + 2);
                byte v3 = mapped.get(srcPx + 3);

                // 若源是 Swapchain 的 BGRA，则 v0 为 B，v2 为 R
                byte r = isVulkanBGRA ? v2 : v0;
                byte g = v1;
                byte b = isVulkanBGRA ? v0 : v2;
                byte a = v3;

                switch (destFormat) {
                    case RGB8:
                        destBuf.put(dstPx + 0, r);
                        destBuf.put(dstPx + 1, g);
                        destBuf.put(dstPx + 2, b);
                        break;
                    case RGBA8:
                        destBuf.put(dstPx + 0, r);
                        destBuf.put(dstPx + 1, g);
                        destBuf.put(dstPx + 2, b);
                        destBuf.put(dstPx + 3, a);
                        break;
                    case BGR8:
                        destBuf.put(dstPx + 0, b);
                        destBuf.put(dstPx + 1, g);
                        destBuf.put(dstPx + 2, r);
                        break;
                    case ABGR8:
                        destBuf.put(dstPx + 0, a);
                        destBuf.put(dstPx + 1, b);
                        destBuf.put(dstPx + 2, g);
                        destBuf.put(dstPx + 3, r);
                        break;
                    case Luminance8:
                        byte lum1 = (byte) (((r & 0xFF) + (g & 0xFF) + (b & 0xFF)) / 3);
                        destBuf.put(dstPx + 0, lum1);
                        break;
                    case Luminance8Alpha8:
                        byte lum2 = (byte) (((r & 0xFF) + (g & 0xFF) + (b & 0xFF)) / 3);
                        destBuf.put(dstPx + 0, lum2);
                        destBuf.put(dstPx + 1, a);
                        break;
                    default:
                        destBuf.put(dstPx + 0, r);
                        destBuf.put(dstPx + 1, g);
                        destBuf.put(dstPx + 2, b);
                        destBuf.put(dstPx + 3, a);
                        break;
                }
            }
        }

        // 清理回收
        vmaUnmapMemory(vk.vmaAllocator(), staging.memory);
        memFree(p);
        destroyBuffer(staging);
    }

    private void transitionImageLayoutForRead(VkCommandBuffer cmd, long image, int oldLayout, int newLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);

            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

            barrier.srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT);
            barrier.dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT);

            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
        }
    }
}
