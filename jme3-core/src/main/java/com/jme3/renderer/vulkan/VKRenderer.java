package com.jme3.renderer.vulkan;

import com.jme3.material.Material;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.Renderer;
import com.jme3.texture.Texture;

/**
 * jME3 Core 层面对于 Vulkan 渲染器的顶层抽象接口。
 * 扩展了标准的 Renderer 接口，提供了 Vulkan 渲染管线所需的额外前端状态追踪方法。
 */
public interface VKRenderer extends Renderer {

    /**
     * 丢弃当前队列中尚未提交给后端的绘制指令
     */
    void discardPendingDraws();

    /**
     * 设置额外的全局/扩展贴图
     */
    void setExtraTexture(Texture tex);

    /**
     * 清理当前绑定的纹理单元状态
     */
    void clearTextureUnits();

    /**
     * 传递视图与投影矩阵，供 Shader UBO 更新使用
     */
    void setViewProjectionMatrices(Matrix4f viewMatrix, Matrix4f projMatrix);

    /**
     * 记录当前正在使用的材质快照
     */
    void setCurrentMaterial(Material material);
}