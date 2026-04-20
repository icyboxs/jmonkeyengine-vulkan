package com.jme3.renderer.vulkan.frame;

import com.jme3.renderer.vulkan.context.VkContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * VulkanCommands：命令缓冲资源管理。
 *
 * 功能：管理命令池和命令缓冲区
 * 原理：封装Vulkan命令缓冲区的生命周期管理
 *
 * 职责：
 * - 创建/销毁 command pool
 * - 为 swapchain image 分配对应的 VkCommandBuffer 数组
 * - 在 swapchain 重建后，重建 command pool + command buffers（简单可靠，不累积泄漏）
 *
 * 不负责：
 * - 具体绘制命令内容（由 VkCommandRecorder 负责）
 * @author icyboxs
 */
public final class VulkanCommands {

    private final VkContext vk;

    private long commandPool;
    private VkCommandBuffer[] drawCmds;

    public VulkanCommands(VkContext vk) {
        this.vk = vk;
    }

    /**
     * 初始化命令池
     * 功能：创建命令池
     * 原理：如果命令池不存在则创建
     */
    public void init() {
        if (commandPool != 0) return;
        commandPool = createCommandPool();
    }

    /**
     * 确保 command buffers 数量与 swapchain image 数量一致。
     * 功能：调整命令缓冲区数量匹配交换链图像
     * 原理：如果数量变化则重建命令池和缓冲区
     * 如果数量变化（swapchain 重建），这里采用"销毁并重建 commandPool"的简单策略。
     */
    public void ensureForSwapchain(int imageCount) {
        if (commandPool == 0) init();

        if (drawCmds != null && drawCmds.length == imageCount) {
            return;
        }

        // 简单安全：重建 command pool（会隐式释放旧 command buffers）
        if (commandPool != 0) {
            vkDestroyCommandPool(vk.device(), commandPool, null);
            commandPool = 0;
        }
        commandPool = createCommandPool();
        allocateCommandBuffers(imageCount);
    }

    /**
     * 获取指定索引的绘制命令缓冲区
     */
    public VkCommandBuffer getDrawCmd(int idx) {
        return drawCmds[idx];
    }

    /**
     * 重置并 begin 录制指定 image 的 command buffer。
     * 功能：准备命令缓冲区进行录制
     * 原理：重置缓冲区状态，开始命令录制
     * @return 准备好的命令缓冲区
     */
    public VkCommandBuffer beginRecording(int imageIndex) {
        VkCommandBuffer cmd = drawCmds[imageIndex];

        // reset 单个 command buffer
        vkResetCommandBuffer(cmd, 0);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            int err = vkBeginCommandBuffer(cmd, beginInfo);
            if (err != VK_SUCCESS) throw new RuntimeException("vkBeginCommandBuffer failed: " + err);
        }

        return cmd;
    }

    /**
     * 结束命令缓冲区录制
     * 功能：结束命令录制
     * 原理：调用vkEndCommandBuffer结束录制
     */
    public void endRecording(int imageIndex) {
        VkCommandBuffer cmd = drawCmds[imageIndex];
        int err = vkEndCommandBuffer(cmd);
        if (err != VK_SUCCESS) throw new RuntimeException("vkEndCommandBuffer failed: " + err);
    }

    /**
     * 销毁命令池和缓冲区
     * 功能：清理命令相关资源
     * 原理：销毁命令池（隐式释放所有命令缓冲区）
     */
    public void destroy() {
        if (commandPool != 0) {
            vkDestroyCommandPool(vk.device(), commandPool, null);
            commandPool = 0;
        }
        drawCmds = null;
    }

    /**
     * 分配命令缓冲区数组
     * 功能：为每个交换链图像分配命令缓冲区
     * 原理：批量分配主命令缓冲区
     */
    private void allocateCommandBuffers(int count) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(count);

            PointerBuffer pBuffers = stack.mallocPointer(count);
            int err = vkAllocateCommandBuffers(vk.device(), ai, pBuffers);
            if (err != VK_SUCCESS) throw new RuntimeException("vkAllocateCommandBuffers failed: " + err);

            drawCmds = new VkCommandBuffer[count];
            for (int i = 0; i < count; i++) {
                drawCmds[i] = new VkCommandBuffer(pBuffers.get(i), vk.device());
            }
        }
    }

    /**
     * 创建命令池
     * 功能：创建新的命令池
     * 原理：配置命令池创建信息，允许重置单个命令缓冲区
     */
    private long createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queueFamilyIndex(vk.queueFamilyIndex())
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreateCommandPool(vk.device(), ci, null, p);
            if (err != VK_SUCCESS) throw new RuntimeException("vkCreateCommandPool failed: " + err);
            return p.get(0);
        }
    }
}