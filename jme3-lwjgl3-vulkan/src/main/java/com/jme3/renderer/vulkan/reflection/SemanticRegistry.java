package com.jme3.renderer.vulkan.reflection;

import java.util.HashMap;
import java.util.Map;

public final class SemanticRegistry {

    private static final Map<String, ResourceSemantic> DICT = new HashMap<>(64);

    static {
        register("jmeuniforms", ResourceSemantic.PER_DRAW_UBO);
        register("perdraw",     ResourceSemantic.PER_DRAW_UBO);
        register("alphaparams", ResourceSemantic.ALPHA_PARAMS);
        register("desaturationparams", ResourceSemantic.DESATURATION_PARAMS);
    }

    public static void register(String normalizedName, ResourceSemantic semantic) {
        if (normalizedName != null && semantic != null) {
            DICT.put(normalizedName, semantic);
        }
    }

    public static ResourceSemantic resolveSemantic(String normalizedName, String descriptorType, int set, int binding) {
        ResourceSemantic semantic = DICT.get(normalizedName);
        if (semantic != null) {
            return semantic;
        }

        if (descriptorType != null) {
            String typeUpper = descriptorType.toUpperCase();
            if (typeUpper.contains("UNIFORM_BUFFER")) {
                if (set == 0 && binding == 0) return ResourceSemantic.PER_DRAW_UBO;
                return ResourceSemantic.UNKNOWN_UBO;
            }
            // 【核心修改】：只要是采样器，全部一视同仁
            if (typeUpper.contains("COMBINED_IMAGE_SAMPLER")) {
                return ResourceSemantic.SAMPLED_IMAGE;
            }
        }
        return ResourceSemantic.UNKNOWN_SAMPLER;
    }
}