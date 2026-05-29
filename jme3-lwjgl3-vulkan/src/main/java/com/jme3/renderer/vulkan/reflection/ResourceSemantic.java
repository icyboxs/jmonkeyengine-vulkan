package com.jme3.renderer.vulkan.reflection;

public enum ResourceSemantic {
    PER_DRAW_UBO,
    GLOBAL_UBO,

    SAMPLED_IMAGE,
    
    // 【新增】
    STORAGE_BUFFER,
    STORAGE_IMAGE,
    
    COLOR_MAP,
    LIGHT_MAP,
    EXTRA_TEX,

    ALPHA_PARAMS,
    DESATURATION_PARAMS,

    UNKNOWN_UBO,
    UNKNOWN_SAMPLER
}