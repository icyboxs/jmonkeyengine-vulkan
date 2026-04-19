package com.jme3.renderer.vulkan.frame;

import com.jme3.renderer.vulkan.context.VkContext;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.vulkan.pipeline.VulkanPipeline;
import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.swapchain.VulkanSwapchain;

/**
 * VulkanFrameInfo：FrameDriver -> Recorder 的只读数据包。 功能：帧信息数据传输对象，封装一帧渲染所需的所有上下文信息
 * 原理：使用不可变设计模式，将相关渲染资源组合成单一数据包 作用：在Vulkan渲染系统的不同组件间传递帧信息，降低模块间耦合
 *
 * 设计目标：降低 VKRenderer 与 VulkanRuntime 的耦合。 架构角色：作为帧驱动层和命令录制层之间的数据契约
 */
public final class VulkanFrameInfo {
    // 所有字段声明为final，确保对象不可变，提供线程安全性

    /**
     * Vulkan核心上下文对象，包含设备、实例、队列等核心资源
     */
    public final VkContext vk;

    /**
     * 交换链管理对象，包含当前帧的交换链图像、图像视图和帧缓冲
     */
    public final VulkanSwapchain swapchain;

    /**
     * 图形管线对象，包含渲染通道、管线布局和图形管线
     */
    public final VulkanPipeline pipeline;

    /**
     * 描述符管理对象，包含描述符集、统一缓冲区和描述符布局
     */
    public final VulkanDescriptors descriptors;

    /**
     * 当前帧的宽度（像素），通常与交换链图像尺寸一致
     */
    public final int width;

    /**
     * 当前帧的高度（像素），通常与交换链图像尺寸一致
     */
    public final int height;

    /**
     * 清除颜色，用于渲染前清空颜色附件（背景色）
     */
    public final ColorRGBA clearColor;

    public final VulkanRuntime runtime;   // 新增

    /**
     * 构造函数 - 初始化所有帧信息字段 功能：创建帧信息数据包 原理：一次性设置所有渲染相关资源引用 作用：确保帧信息对象在创建后完全初始化且不可变
     *
     * 参数说明：
     *
     * @param vk Vulkan核心上下文，提供设备、队列等基础访问
     * @param runtime
     * @param swapchain 交换链对象，提供当前帧的渲染目标
     * @param pipeline 图形管线，定义渲染状态和着色器
     * @param descriptors 描述符集，提供着色器常量数据
     * @param width 渲染区域宽度（像素）
     * @param height 渲染区域高度（像素）
     * @param clearColor 清除颜色，用于渲染通道开始时的清空操作
     */
    public VulkanFrameInfo(VkContext vk,
            VulkanRuntime runtime,
            VulkanSwapchain swapchain,
            VulkanPipeline pipeline,
            VulkanDescriptors descriptors,
            //VulkanFrameDescriptors frameDescriptors,
            int width,
            int height,
            ColorRGBA clearColor) {
        this.vk = vk;
        this.runtime = runtime;
        this.swapchain = swapchain;
        this.pipeline = pipeline;
        this.descriptors = descriptors;
        //this.frameDescriptors = frameDescriptors;
        this.width = width;
        this.height = height;
        this.clearColor = clearColor;
    }

    // 注意：没有setter方法，所有字段都是public final
    // 这种设计选择基于以下考虑：
    // 1. 简单性：作为数据传输对象，避免多余的getter封装
    // 2. 性能：直接字段访问比方法调用更快
    // 3. 不可变性：final字段确保线程安全和数据一致性
    // 4. 明确性：公开字段明确表示这是纯数据容器
}
