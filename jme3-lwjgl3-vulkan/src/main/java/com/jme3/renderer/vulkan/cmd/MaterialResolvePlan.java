package com.jme3.renderer.vulkan.cmd;

import com.jme3.texture.Texture;

/**
 * MaterialResolvePlan：
 * DrawCmd 在前端提交阶段生成的“材质解析意图”。
 *
 * 它不持有 GPU 句柄，只表达：
 * - 本次 draw 想使用哪些 jME Texture
 * - 哪些槽位需要 fallback white
 *
 * 这样 recordFrame 阶段就不必重新推断“是否空贴图 / 是否该回退白纹理”。
 */
public final class MaterialResolvePlan {

    public final Texture tex0;
    public final Texture light;

    public final boolean fallbackWhiteTex0;
    public final boolean fallbackWhiteLight;

    public MaterialResolvePlan(Texture tex0,
                               Texture light,
                               boolean fallbackWhiteTex0,
                               boolean fallbackWhiteLight) {
        this.tex0 = tex0;
        this.light = light;
        this.fallbackWhiteTex0 = fallbackWhiteTex0;
        this.fallbackWhiteLight = fallbackWhiteLight;
    }

    public boolean hasRealTex0() {
        return tex0 != null && !fallbackWhiteTex0;
    }

    public boolean hasRealLight() {
        return light != null && !fallbackWhiteLight;
    }

    @Override
    public String toString() {
        return "MaterialResolvePlan{tex0="
                + (tex0 != null ? tex0.getName() : "null")
                + ", light="
                + (light != null ? light.getName() : "null")
                + ", fallbackWhiteTex0=" + fallbackWhiteTex0
                + ", fallbackWhiteLight=" + fallbackWhiteLight
                + '}';
    }
}
