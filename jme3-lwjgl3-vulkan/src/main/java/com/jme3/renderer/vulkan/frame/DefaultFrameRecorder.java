package com.jme3.renderer.vulkan.frame;

import com.jme3.math.ColorRGBA;
import com.jme3.renderer.vulkan.VkCommandRecorder;
import com.jme3.renderer.vulkan.queue.DrawQueue;
import com.jme3.renderer.vulkan.state.FrontendStateTracker;
import com.jme3.renderer.vulkan.util.VkBarrierUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 统筹一帧画面的录制生命周期。
 * @author icyboxs
 */
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

            int vpW = stateTracker.getVpW();
            int vpH = stateTracker.getVpH();
            int vpX = stateTracker.getVpX();
            int vpY = stateTracker.getVpY();

            int vx = (vpW > 0 && vpH > 0) ? vpX : 0;
            int vy = (vpW > 0 && vpH > 0) ? vpY : 0;
            int vw = (vpW > 0 && vpH > 0) ? vpW : fbW;
            int vh = (vpW > 0 && vpH > 0) ? vpH : fbH;

            if (vx < 0) { vw += vx; vx = 0; }
            if (vy < 0) { vh += vy; vy = 0; }
            if (vx > fbW) vx = fbW;
            if (vy > fbH) vy = fbH;
            if (vx + vw > fbW) vw = fbW - vx;
            if (vy + vh > fbH) vh = fbH - vy;
            if (vw < 1) vw = 1;
            if (vh < 1) vh = 1;

            float vkVpX = (float) vx;
            float vkVpY = (float) (fbH - vy);
            float vkVpW = (float) vw;
            float vkVpH = (float) (-vh);

            VkViewport.Buffer vp = VkViewport.calloc(1, stack)
                    .x(vkVpX).y(vkVpY).width(vkVpW).height(vkVpH)
                    .minDepth(0f).maxDepth(1f);

            int sx, sy, sw, sh;
            if (stateTracker.isClipEnabled()) {
                sx = stateTracker.getClipX();
                sy = stateTracker.getClipY();
                sw = stateTracker.getClipW();
                sh = stateTracker.getClipH();
            } else {
                sx = vx; sy = vy; sw = vw; sh = vh;
            }

            if (sx < 0) { sw += sx; sx = 0; }
            if (sy < 0) { sh += sy; sy = 0; }
            if (sx > fbW) sx = fbW;
            if (sy > fbH) sy = fbH;
            if (sx + sw > fbW) sw = fbW - sx;
            if (sy + sh > fbH) sh = fbH - sy;
            if (sw < 0) sw = 0;
            if (sh < 0) sh = 0;

            VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
            sc.offset().set(sx, sy);
            sc.extent().set(sw, sh);

            // 图像布局转换
            int oldColor = frame.swapchain.getImageLayout(swapchainIndex);
            VkBarrierUtil.transitionSwapchainImage(cmd, frame, swapchainIndex, oldColor, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            frame.swapchain.setImageLayout(swapchainIndex, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            int oldDepth = frame.swapchain.getDepthLayout();
            VkBarrierUtil.transitionDepthImage(cmd, frame, oldDepth, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            frame.swapchain.setDepthLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

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

            // 排序并开启渲染通道
            drawQueue.precomputeAndSort(frame);
            vkCmdBeginRenderingKHR(cmd, ri);
            vkCmdSetViewport(cmd, 0, vp);
            vkCmdSetScissor(cmd, 0, sc);

            // 执行绘制
            drawExecutor.execute(cmd, stack, frameIndex, frame, drawQueue);

            vkCmdEndRenderingKHR(cmd);

            // 转换到 Present
            VkBarrierUtil.transitionSwapchainImage(cmd, frame, swapchainIndex,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            frame.swapchain.setImageLayout(swapchainIndex, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

        } finally {
            drawQueue.clear(); // 保证一帧结束后队列必定清空
        }
    }
}