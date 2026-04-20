package com.jme3.renderer.vulkan.cmd;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.shader.Shader;
import com.jme3.texture.Texture;

/**
 * RendererStateSnapshot
 *
 * 作用： - 在“前端提交 draw”时，把当前渲染状态做一次快照 - 供 DrawCmdBuilder 构建 DrawCmd 使用 -
 * 后端(recordFrame)只消费 DrawCmd，不直接依赖前端 Material/Geometry 语义
 *
 * 设计原则： - 这是一个轻量数据容器（DTO） - 允许字段为 null（由 DrawCmdBuilder 做默认值/回退）
 */
public final class RendererStateSnapshot {

    /**
     * 当前 shader（用于提取源码/uniform）
     */
    public Shader shader;

    /**
     * 当前渲染状态（深度、混合、剔除等）
     */
    public RenderState renderState;

    /**
     * set0 常见贴图：ColorMap
     */
    public Texture tex0;

    /**
     * set0 常见贴图：LightMap
     */
    public Texture light;

    /**
     * set1 扩展贴图：ExtraTex/ExtraMap
     */
    public Texture extra;

    /**
     * 可选：WVP 快照（若上游已拿到可直接塞，避免重复读 uniform）
     */
    public Matrix4f wvp;

    /**
     * 可选：颜色快照（若上游已拿到可直接塞）
     */
    public ColorRGBA color;
    public Material material;

    public RendererStateSnapshot() {
    }

    public static RendererStateSnapshot of(Shader shader,
            RenderState renderState,
            Texture tex0,
            Texture light,
            Texture extra,
            Material material) {
        RendererStateSnapshot s = new RendererStateSnapshot();
        s.shader = shader;
        s.renderState = renderState;
        s.tex0 = tex0;
        s.light = light;
        s.extra = extra;
        s.material = material;
        return s;
    }

    @Override
    public String toString() {
        return "RendererStateSnapshot{"
                + "shader=" + (shader != null ? shader.hashCode() : 0)
                + ", renderState=" + (renderState != null)
                + ", tex0=" + texName(tex0)
                + ", light=" + texName(light)
                + ", extra=" + texName(extra)
                + ", wvp=" + (wvp != null)
                + ", color=" + (color != null)
                + '}';
    }

    private static String texName(Texture t) {
        if (t == null) {
            return "null";
        }
        String n = t.getName();
        return (n != null) ? n : ("@" + System.identityHashCode(t));
    }


}
