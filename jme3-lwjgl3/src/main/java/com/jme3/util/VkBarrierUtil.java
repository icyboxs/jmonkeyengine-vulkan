package com.jme3.renderer.vulkan.util;

import com.jme3.renderer.vulkan.frame.VulkanFrameInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 封装 Vulkan 图像内存屏障 (Image Memory Barrier) 和布局转换 (Layout Transition) 逻辑。
 */
public final class VkBarrierUtil {

    private VkBarrierUtil() {
        // 工具类，禁止实例化
    }

    public static void transitionSwapchainImage(VkCommandBuffer cmd, VulkanFrameInfo frame, int swapchainIndex, int oldLayout, int newLayout) {
        if (oldLayout == newLayout) return;
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(frame.swapchain.getImage(swapchainIndex));

            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            int srcStage;
            int dstStage;

            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            } else if (oldLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            } else if (oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
                barrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_MEMORY_READ_BIT);
                srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                dstStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
            } else {
                throw new UnsupportedOperationException("Unsupported swapchain layout transition: " + oldLayout + " -> " + newLayout);
            }

            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    public static void transitionDepthImage(VkCommandBuffer cmd, VulkanFrameInfo frame, int oldLayout, int newLayout) {
        long depthImage = frame.swapchain.getDepthImage();
        if (depthImage == 0L || oldLayout == newLayout) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(depthImage);

            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            int srcStage, dstStage;

            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                dstStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
            } else {
                throw new UnsupportedOperationException("Unsupported depth layout transition: " + oldLayout + " -> " + newLayout);
            }

            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    public static void transitionColorImage(VkCommandBuffer cmd, long image, int oldLayout, int newLayout) {
        if (oldLayout == newLayout) return;

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
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            int srcStage, dstStage, srcAccess, dstAccess;

            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                srcAccess = 0;
                dstAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            } else if (oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
                srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                dstStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                srcAccess = VK_ACCESS_SHADER_READ_BIT;
                dstAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            } else if (oldLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                srcStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
                dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                srcAccess = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                dstAccess = VK_ACCESS_SHADER_READ_BIT;
            } else {
                throw new UnsupportedOperationException("Unsupported color layout transition: " + oldLayout + " -> " + newLayout);
            }

            barrier.srcAccessMask(srcAccess);
            barrier.dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }
}