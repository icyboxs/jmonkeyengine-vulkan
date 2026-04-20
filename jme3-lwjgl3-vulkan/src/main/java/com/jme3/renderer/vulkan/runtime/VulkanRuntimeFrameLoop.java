package com.jme3.renderer.vulkan.runtime;

import com.jme3.math.ColorRGBA;
import com.jme3.renderer.vulkan.VKRenderer;
import com.jme3.renderer.vulkan.VkCommandRecorder;
import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.frame.VulkanFrameDriver;
import com.jme3.renderer.vulkan.frame.VulkanFrameInfo;
import com.jme3.renderer.vulkan.swapchain.VulkanSwapchain;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VulkanRuntimeFrameLoop {

    private final VulkanRuntime owner;
    private final VulkanRuntimeState s;
    private final VulkanRuntimeLifecycle lifecycle;
    private final AtomicBoolean cleaningUp;
    private final Logger log;

    public VulkanRuntimeFrameLoop(VulkanRuntime owner,
            VulkanRuntimeState s,
            VulkanRuntimeLifecycle lifecycle,
            AtomicBoolean cleaningUp,
            Logger log) {
        this.owner = owner;
        this.s = s;
        this.lifecycle = lifecycle;
        this.cleaningUp = cleaningUp;
        this.log = log;
    }

    public void renderFrame(float tpf, VkCommandRecorder recorder, ColorRGBA clearColor) {
        if (s.window == null) {
            return;
        }
        if (cleaningUp.get()) {
            log.fine("[VulkanRuntime] renderFrame skipped: cleaning up");
            return;
        }
        if (recorder == null) {
            throw new IllegalArgumentException("recorder must not be null");
        }

        if (s.pipelineManager == null) {
            throw new IllegalStateException("pipelineManager not initialized");
        }
        if (s.renderTargetManager == null) {
            throw new IllegalStateException("renderTargetManager not initialized");
        }
        if (s.frameDriver == null) {
            throw new IllegalStateException("frameDriver not initialized");
        }
        if (s.commands == null) {
            throw new IllegalStateException("commands not initialized");
        }
        if (s.descriptors == null) {
            throw new IllegalStateException("descriptors not initialized");
        }

        if (s.reshapeRequested.getAndSet(false)) {
            lifecycle.recreateSwapchain();
        }

        long now = System.nanoTime();
        if (s.lastTimeNs != 0L) {
            s.angle += (now - s.lastTimeNs) / 1E9f;
        }
        s.lastTimeNs = now;

        int fbW = s.window.fbWidth();
        int fbH = s.window.fbHeight();
        if (fbW <= 0 || fbH <= 0) {
            if (recorder instanceof com.jme3.renderer.vulkan.VKRenderer) {
                ((VKRenderer) recorder).discardPendingDraws();
            }
            return;
        }

        VulkanSwapchain swapchain = s.renderTargetManager.getSwapchain();
        if (swapchain == null) {
            throw new IllegalStateException("swapchain not initialized");
        }

        VulkanFrameInfo frameInfo = new VulkanFrameInfo(
                s.vk,
                owner,
                swapchain,
                s.pipelineManager.getBasePipeline(),
                s.descriptors,
                fbW,
                fbH,
                clearColor
        );

        VulkanFrameDriver.FrameResult r = s.frameDriver.renderOneFrame(s.commands, recorder, frameInfo);

        if (r == VulkanFrameDriver.FrameResult.NEEDS_SWAPCHAIN_RECREATE) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("[VulkanRuntime] frame result: NEEDS_SWAPCHAIN_RECREATE");
            }
            s.reshapeRequested.set(true);
        }
    }
}
