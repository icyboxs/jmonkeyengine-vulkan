package com.jme3.renderer.vulkan;

import com.jme3.renderer.vulkan.frame.VulkanFrameInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * VkCommandRecorder：命令录制抽象。
 * 功能：定义命令录制的接口
 * 原理：分离命令录制和提交逻辑
 * 作用：
 * Renderer(前端)实现它，用于把上层绘图状态翻译为 Vulkan 命令。
 * FrameDriver(后端)负责 acquire/submit/present，同步与帧循环调度。
 * @author icyboxs
 */
public interface VkCommandRecorder {

    /**
     * 录制一帧到指定 command buffer。
     * 功能：将渲染状态转换为Vulkan命令
     * 原理：在提供的命令缓冲区中录制完整的渲染命令序列
     *
     * @param cmd            要写入命令的 VkCommandBuffer（已 reset，可 begin 录制）
     * @param swapchainIndex 当前 swapchain image index（对应 framebuffer index）
     * @param frameIndex
     * @param frame          当前帧只读上下文（renderPass/framebuffer/尺寸/clearColor/资源等）
     */
    void recordFrame(VkCommandBuffer cmd, int swapchainIndex, int frameIndex, VulkanFrameInfo frame);
}