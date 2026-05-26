package com.jme3.renderer.vulkan.frame;

import com.jme3.renderer.vulkan.VkCommandRecorder;
import com.jme3.renderer.vulkan.context.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanFrameDriver {

    public enum FrameResult {
        OK,
        NEEDS_SWAPCHAIN_RECREATE
    }

    public static final int MAX_FRAMES_IN_FLIGHT = 2;

    private final VkContext vk;

    private long[] imageAvailableSem;
    private long[] renderFinishedSem;
    private long[] inFlightFence;
    private long[] imagesInFlight; // 跟踪每个 Swapchain Image 正在使用的 Fence

    private int frameIndex = 0;
    private boolean initialized = false;
    private int lastImageIndex = 0;

    public int getLastImageIndex() {
        return lastImageIndex;
    }

    public VulkanFrameDriver(VkContext vk) {
        this.vk = vk;
    }

    public void init() {
        if (initialized) {
            return;
        }

        imageAvailableSem = new long[MAX_FRAMES_IN_FLIGHT];
        renderFinishedSem = new long[MAX_FRAMES_IN_FLIGHT];
        inFlightFence = new long[MAX_FRAMES_IN_FLIGHT];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo semCI = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fenceCI = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer p = stack.mallocLong(1);

            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                vkCreateSemaphore(vk.device(), semCI, null, p);
                imageAvailableSem[i] = p.get(0);

                vkCreateSemaphore(vk.device(), semCI, null, p);
                renderFinishedSem[i] = p.get(0);

                vkCreateFence(vk.device(), fenceCI, null, p);
                inFlightFence[i] = p.get(0);
            }
        }
        initialized = true;
    }

    public FrameResult renderOneFrame(VulkanCommands commands, VkCommandRecorder recorder, VulkanFrameInfo frameInfo) {
        if (!initialized) {
            init();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. 等待 CPU 槽位可用 (避免重用正在被 GPU 使用的信号量)
            long currentFence = inFlightFence[frameIndex];
            _check(vkWaitForFences(vk.device(), currentFence, true, Long.MAX_VALUE), "Wait Frame Fence");
            // 新增：该 frame slot 已安全可复用，回收其延迟销毁资源
            frameInfo.runtime.setCurrentFrameSlot(frameIndex);
            frameInfo.runtime.onFrameSlotBegin(frameIndex);
            // 2. 获取下一张图像
            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquire = vkAcquireNextImageKHR(
                    vk.device(),
                    frameInfo.swapchain.getSwapchain(),
                    Long.MAX_VALUE,
                    imageAvailableSem[frameIndex], // 该信号量将在图像可用时由 GPU 触发
                    VK_NULL_HANDLE,
                    pImageIndex
            );

            if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
                return FrameResult.NEEDS_SWAPCHAIN_RECREATE;
            }
            if (acquire != VK_SUCCESS && acquire != VK_SUBOPTIMAL_KHR) {
                throw new RuntimeException("Acquire failed: " + acquire);
            }

            int imageIndex = pImageIndex.get(0);
            this.lastImageIndex = imageIndex;
            // 3. 检查并等待该图像之前的 Fence (解决 WRITE_AFTER_PRESENT)
            ensureImagesInFlightCapacity(frameInfo.swapchain.getImageCount());
            if (imagesInFlight[imageIndex] != VK_NULL_HANDLE) {
                _check(vkWaitForFences(vk.device(), imagesInFlight[imageIndex], true, Long.MAX_VALUE), "Wait Image Fence");
            }
            imagesInFlight[imageIndex] = currentFence;

            // 4. 重置 Fence，准备提交任务
            _check(vkResetFences(vk.device(), currentFence), "Reset Fence");

            // 5. 录制命令
            VkCommandBuffer cmd = commands.beginRecording(imageIndex);
            recorder.recordFrame(cmd, imageIndex, frameIndex, frameInfo);
            commands.endRecording(imageIndex);

            // 6. 提交渲染请求
            // 注意：必须在 COLOR_ATTACHMENT_OUTPUT 阶段等待，否则会报 MissingAcquireWait
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(imageAvailableSem[frameIndex]))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)) // 重要修复
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(renderFinishedSem[frameIndex]));

            _check(vkQueueSubmit(vk.queue(), submitInfo, currentFence), "Queue Submit");

            // 7. 呈现图像
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(renderFinishedSem[frameIndex])) // 等待渲染完成
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(frameInfo.swapchain.getSwapchain()))
                    .pImageIndices(stack.ints(imageIndex));

            int present = vkQueuePresentKHR(vk.queue(), presentInfo);

            // 8. 轮转帧索引
            frameIndex = (frameIndex + 1) % MAX_FRAMES_IN_FLIGHT;

            if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR || acquire == VK_SUBOPTIMAL_KHR) {
                return FrameResult.NEEDS_SWAPCHAIN_RECREATE;
            }
            if (present != VK_SUCCESS) {
                throw new RuntimeException("Present failed: " + present);
            }

            return FrameResult.OK;
        }
    }

    // --- 辅助方法保持不变，但增加安全性检查 ---
    public void destroy() {
        if (!initialized) {
            return;
        }
        vkDeviceWaitIdle(vk.device()); // 销毁前确保 GPU 已停止
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            vkDestroySemaphore(vk.device(), imageAvailableSem[i], null);
            vkDestroySemaphore(vk.device(), renderFinishedSem[i], null);
            vkDestroyFence(vk.device(), inFlightFence[i], null);
        }
        initialized = false;
    }

    private void ensureImagesInFlightCapacity(int imageCount) {
        if (imagesInFlight == null || imagesInFlight.length != imageCount) {
            imagesInFlight = new long[imageCount];
            for (int i = 0; i < imageCount; i++) {
                imagesInFlight[i] = VK_NULL_HANDLE;
            }
        }
    }

    private static void _check(int err, String msg) {
        if (err != VK_SUCCESS) {
            throw new RuntimeException(msg + " failed: " + err);
        }
    }
}
