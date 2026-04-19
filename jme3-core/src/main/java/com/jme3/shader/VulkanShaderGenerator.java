package com.jme3.shader;

import com.jme3.asset.AssetManager;
import com.jme3.material.ShaderGenerationInfo;
import com.jme3.shader.Shader.ShaderType;
import java.util.List;

/**
 * 专为 Vulkan 后端定制的着色器生成器。 继承自 Glsl300ShaderGenerator，利用其现代 GLSL 语法特性 (in/out)。
 */
public class VulkanShaderGenerator extends Glsl300ShaderGenerator {

    public VulkanShaderGenerator(AssetManager assetManager) {
        super(assetManager);
    }

    /**
     * 1. 宣告我们的语言标签为 "Vulkan"，这样就能和 .j3md 里的文本完美匹配
     */
    @Override
    protected String getLanguageAndVersion(ShaderType type) {
        return "Vulkan";
    }

    /**
     * 2. 注入 Vulkan 专属的全局宏定义 (针对 ShaderNodes)
     */
    @Override
    protected void generateUniforms(StringBuilder source, ShaderGenerationInfo info, ShaderType type) {
        // 注入全局宏，方便你在 .j3md 的着色器源码里写 #ifdef VULKAN
        source.append("\n#define VULKAN 1\n");
        super.generateUniforms(source, info, type);
    }

    /**
     * 在 Vulkan 模式下，强制校验材质是否包含 Vulkan 标签
     */
    @Override
    protected int findShaderIndexFromVersion(ShaderNode shaderNode, ShaderType type) {
        List<String> lang = shaderNode.getDefinition().getShadersLanguage();

        // 强制检查：必须包含 "Vulkan"
        for (int i = 0; i < lang.size(); i++) {
            if ("Vulkan".equalsIgnoreCase(lang.get(i))) {
                return i;
            }
        }

        // ✅ 如果运行到这里，说明 AppSettings 选了 Vulkan，但材质没提供 Vulkan 源码
        // 直接抛出异常，中断渲染，防止渲染器回退到不兼容的 GLSL 分支
        throw new IllegalStateException(
                "CRITICAL ERROR: 后端已设置为 LWJGL_VULKAN，但材质节点 ["
                + shaderNode.getName() + "] 缺失 'Vulkan' 分支！"
        );
    }
}
