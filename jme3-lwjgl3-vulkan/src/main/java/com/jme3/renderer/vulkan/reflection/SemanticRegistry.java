package com.jme3.renderer.vulkan.reflection;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据驱动的着色器语义注册表。
 * 负责将 Shader 中反射出的规范化变量名 (Normalized Name) 以 O(1) 速度映射到引擎的逻辑语义 (ResourceSemantic)。
 */
public final class SemanticRegistry {

    private static final Map<String, ResourceSemantic> DICT = new HashMap<>(64);

    static {
        // ==========================================
        // 核心 UBO 语义契约
        // ==========================================
        register("jmeuniforms", ResourceSemantic.PER_DRAW_UBO);
        register("perdraw",     ResourceSemantic.PER_DRAW_UBO);
        register("alphaparams", ResourceSemantic.ALPHA_PARAMS);
        register("desaturationparams", ResourceSemantic.DESATURATION_PARAMS);

        // ==========================================
        // 基础纹理语义契约
        // ==========================================
        // 自动处理别名：无论叫 colormap 还是 diffusemap，都映射为主颜色图
        register("colormap",   ResourceSemantic.COLOR_MAP);
        register("diffusemap", ResourceSemantic.COLOR_MAP);
        register("lightmap",   ResourceSemantic.LIGHT_MAP);
        
        
        // 扩展槽位别名
        register("extratex",   ResourceSemantic.EXTRA_TEX);
        register("extramap",   ResourceSemantic.EXTRA_TEX);
        register("glowmap",    ResourceSemantic.EXTRA_TEX); 
    }

    /**
     * 注册新的语义映射规则。允许在外部动态扩充，而无需修改底层逻辑。
     */
    public static void register(String normalizedName, ResourceSemantic semantic) {
        if (normalizedName != null && semantic != null) {
            DICT.put(normalizedName, semantic);
        }
    }

    /**
     * 解析语义。优先使用 O(1) 查表匹配，若未命中则走 Vulkan 物理槽位兜底协议。
     */
    public static ResourceSemantic resolveSemantic(String normalizedName, String descriptorType, int set, int binding) {
        // 1. 查表：O(1) 精确匹配
        ResourceSemantic semantic = DICT.get(normalizedName);
        if (semantic != null) {
            return semantic;
        }

        // 2. 兜底协议：处理无法通过名字识别，但物理槽位明确的资源
        if (descriptorType != null) {
            String typeUpper = descriptorType.toUpperCase();
            if (typeUpper.contains("UNIFORM_BUFFER")) {
                // 业界标准约定：Set 0 Binding 0 默认作为主 Draw UBO
                if (set == 0 && binding == 0) {
                    return ResourceSemantic.PER_DRAW_UBO;
                }
                return ResourceSemantic.UNKNOWN_UBO;
            }
            if (typeUpper.contains("COMBINED_IMAGE_SAMPLER")) {
                return ResourceSemantic.UNKNOWN_SAMPLER;
            }
        }

        return ResourceSemantic.UNKNOWN_SAMPLER;
    }
}