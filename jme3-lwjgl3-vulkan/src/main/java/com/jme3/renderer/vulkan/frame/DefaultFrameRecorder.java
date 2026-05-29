package com.jme3.renderer.vulkan.frame;

import com.jme3.math.ColorRGBA;
import com.jme3.renderer.vulkan.VkCommandRecorder;
import com.jme3.renderer.vulkan.cmd.ComputeCmd;
import com.jme3.renderer.vulkan.cmd.CopyCmd;
import com.jme3.renderer.vulkan.queue.DrawQueue;
import com.jme3.renderer.vulkan.state.FrontendStateTracker;
import com.jme3.renderer.vulkan.util.VkBarrierUtil;
import com.jme3.texture.FrameBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

public final class DefaultFrameRecorder implements VkCommandRecorder {

    private final FrontendStateTracker stateTracker;
    private final DrawQueue drawQueue;
    private final DrawExecutor drawExecutor;

    public DefaultFrameRecorder(FrontendStateTracker stateTracker, DrawQueue drawQueue, DrawExecutor drawExecutor) {
        this.stateTracker = stateTracker;
        this.drawQueue = drawQueue;
        this.drawExecutor = drawExecutor;
    }

    @Override
    public void recordFrame(VkCommandBuffer cmd, int swapchainIndex, int frameIndex, VulkanFrameInfo frame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            frame.runtime.getStats().beginFrame();
            frame.descriptors.beginFrame(frameIndex);
            drawExecutor.beginFrame(frameIndex);

            int fbW = frame.width;
            int fbH = frame.height;

            int oldColor = frame.swapchain.getImageLayout(swapchainIndex);
            VkBarrierUtil.transitionSwapchainImage(cmd, frame, swapchainIndex, oldColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            frame.swapchain.setImageLayout(swapchainIndex, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            int oldDepth = frame.swapchain.getDepthLayout();
            VkBarrierUtil.transitionDepthImage(cmd, frame, oldDepth, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            frame.swapchain.setDepthLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            drawQueue.precomputeAndSort(frame);
            
            java.util.List<ComputeCmd> computes = drawQueue.getComputeCommands();
            if (computes != null && !computes.isEmpty()) {
                drawExecutor.executeComputeList(cmd, stack, frameIndex, frame, computes);
            }

            ColorRGBA background = stateTracker.getBackground();
            VkClearValue.Buffer clearValues = VkClearValue.calloc(2, stack);
            clearValues.get(0).color().float32(0, background.r).float32(1, background.g).float32(2, background.b).float32(3, background.a);
            clearValues.get(1).depthStencil().depth(1f).stencil(0);

            VkRenderingAttachmentInfoKHR.Buffer colorAtt = VkRenderingAttachmentInfoKHR.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(frame.swapchain.getImageView(swapchainIndex))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .clearValue(clearValues.get(0));

            VkRenderingAttachmentInfoKHR depthAtt = VkRenderingAttachmentInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(frame.swapchain.getDepthView())
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .clearValue(clearValues.get(1));

            VkRenderingInfoKHR ri = VkRenderingInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                    .layerCount(1).pColorAttachments(colorAtt).pDepthAttachment(depthAtt);
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(frame.width, frame.height);

            vkCmdBeginRenderingKHR(cmd, ri);
            drawExecutor.executeList(cmd, stack, frameIndex, frame, drawQueue.getOpaqueCommands());
            drawExecutor.executeList(cmd, stack, frameIndex, frame, drawQueue.getGuiCommands());
            vkCmdEndRenderingKHR(cmd);

            executeCopies(cmd, stack, frame, swapchainIndex);

            VkBarrierUtil.transitionSwapchainImage(cmd, frame, swapchainIndex,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            frame.swapchain.setImageLayout(swapchainIndex, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

        } finally {
            drawQueue.clear(); 
        }
    }

    private void executeCopies(VkCommandBuffer cmd, MemoryStack stack, VulkanFrameInfo frame, int swapchainIndex) {
        java.util.List<CopyCmd> copies = drawQueue.getCopyCommands();
        if (copies == null || copies.isEmpty()) return;

        for (CopyCmd copy : copies) {
            if (copy.copyColor) executeSingleCopy(cmd, stack, frame, swapchainIndex, copy.src, copy.dst, true);
            if (copy.copyDepth) executeSingleCopy(cmd, stack, frame, swapchainIndex, copy.src, copy.dst, false);
        }
    }

    private void executeSingleCopy(VkCommandBuffer cmd, MemoryStack stack, VulkanFrameInfo frame, int swapchainIndex, FrameBuffer srcFb, FrameBuffer dstFb, boolean isColor) {
        long srcImage = 0; int srcW = 0, srcH = 0; int srcSamples = 1;
        long dstImage = 0; int dstW = 0, dstH = 0; int dstSamples = 1;

        int aspectMask = isColor ? VK_IMAGE_ASPECT_COLOR_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;

        if (srcFb == null) {
            srcImage = isColor ? frame.swapchain.getImage(swapchainIndex) : frame.swapchain.getDepthImage();
            srcW = frame.width; srcH = frame.height;
        } else {
            FrameBuffer.RenderBuffer rb = isColor ? srcFb.getColorBuffer() : srcFb.getDepthBuffer();
            if (rb != null && rb.getTexture() != null) {
                com.jme3.renderer.vulkan.resource.VkTexture vkTex = frame.runtime.getOrCreateVkTexture(rb.getTexture());
                if (vkTex != null && vkTex.image != 0) { srcImage = vkTex.image; srcW = vkTex.width; srcH = vkTex.height; }
            }
            srcSamples = srcFb.getSamples() > 1 ? srcFb.getSamples() : 1;
        }

        if (dstFb == null) {
            dstImage = isColor ? frame.swapchain.getImage(swapchainIndex) : frame.swapchain.getDepthImage();
            dstW = frame.width; dstH = frame.height;
        } else {
            FrameBuffer.RenderBuffer rb = isColor ? dstFb.getColorBuffer() : dstFb.getDepthBuffer();
            if (rb != null && rb.getTexture() != null) {
                com.jme3.renderer.vulkan.resource.VkTexture vkTex = frame.runtime.getOrCreateVkTexture(rb.getTexture());
                if (vkTex != null && vkTex.image != 0) { dstImage = vkTex.image; dstW = vkTex.width; dstH = vkTex.height; }
            }
            dstSamples = dstFb.getSamples() > 1 ? dstFb.getSamples() : 1;
        }

        if (srcImage == 0 || dstImage == 0 || srcImage == dstImage) return;

        transitionImageForCopy(cmd, stack, srcImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, aspectMask);
        transitionImageForCopy(cmd, stack, dstImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, aspectMask);

        if (srcSamples > 1 && dstSamples == 1) {
            VkImageResolve.Buffer resolve = VkImageResolve.calloc(1, stack);
            resolve.srcSubresource().aspectMask(aspectMask).layerCount(1);
            resolve.dstSubresource().aspectMask(aspectMask).layerCount(1);
            resolve.extent().set(Math.min(srcW, dstW), Math.min(srcH, dstH), 1);
            vkCmdResolveImage(cmd, srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, resolve);
        } else {
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().aspectMask(aspectMask).layerCount(1);
            blit.srcOffsets(1).set(srcW, srcH, 1);
            blit.dstSubresource().aspectMask(aspectMask).layerCount(1);
            blit.dstOffsets(1).set(dstW, dstH, 1);
            int filter = isColor ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            vkCmdBlitImage(cmd, srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, filter);
        }

        int finalSrcLayout = isColor ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        int finalDstLayout = (dstFb == null) ? finalSrcLayout : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        transitionImageForCopy(cmd, stack, srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, finalSrcLayout, aspectMask);
        transitionImageForCopy(cmd, stack, dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, finalDstLayout, aspectMask);
    }

    private void transitionImageForCopy(VkCommandBuffer cmd, MemoryStack stack, long image, int oldLayout, int newLayout, int aspectMask) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.subresourceRange().aspectMask(aspectMask).levelCount(1).layerCount(1);
        barrier.srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT);
        barrier.dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
    }
}