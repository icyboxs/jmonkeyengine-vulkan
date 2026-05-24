package com.jme3.renderer.vulkan;

import com.jme3.material.Material;
import com.jme3.renderer.Renderer;
import com.jme3.texture.Texture;

/**
 * jME3 Core 层面对于 Vulkan 渲染器的顶层抽象接口。
 * 扩展了标准的 Renderer 接口，提供了 Vulkan 渲染管线所需的额外前端状态追踪与调度方法。
 * * 注意：此接口属于 jme3-core，绝对不能包含任何 LWJGL 或底层 Vulkan API (如 VkCommandBuffer) 的引用。
 */
public interface VKRenderer extends Renderer {

    /**
     * 丢弃当前队列中尚未提交给后端的绘制指令。
     * 当发生不可恢复的状态改变（例如窗口大小改变导致 Swapchain 重建）时，前端调用此方法。
     */
    void discardPendingDraws();

    /**
     * 设置额外的全局/扩展贴图。
     * 允许前端灵活地注入特定的全局纹理（例如环境光探针、全局阴影贴图等）。
     *
     * @param tex 需要全局绑定的纹理
     */
    void setExtraTexture(Texture tex);

    /**
     * 清理当前绑定的纹理单元状态。
     * 防止旧纹理污染新一帧的渲染。
     */
    void clearTextureUnits();

    /**
     * 提交额外的全局 Uniform 数据（例如视锥体参数、时间流逝等）。
     * 用于对接 Vulkan 的 UBO 或 Push Constants。
     * * @param name 参数名
     * @param value 参数值
     */
    void setGlobalUniform(String name, Object value);
    
    /**
     * 强制刷新/同步前端记录的流水线状态到后端。
     * Vulkan 需要显式构建 Pipeline，此方法用于在绘制前锁定状态。
     * * @param mat 当前渲染所使用的材质
     */
    void flushPipeline(Material mat);
}