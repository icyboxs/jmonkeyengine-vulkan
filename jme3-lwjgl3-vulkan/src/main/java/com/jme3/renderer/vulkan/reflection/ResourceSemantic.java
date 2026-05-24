package com.jme3.renderer.vulkan.reflection;

public enum ResourceSemantic {
    PER_DRAW_UBO,
    GLOBAL_UBO,

    // 【新增】：统一的全动态采样器标识（接管一切贴图）
    SAMPLED_IMAGE,
    
    COLOR_MAP,
    LIGHT_MAP,
    EXTRA_TEX,

    ALPHA_PARAMS,
    DESATURATION_PARAMS,

    UNKNOWN_UBO,
    UNKNOWN_SAMPLER
}
