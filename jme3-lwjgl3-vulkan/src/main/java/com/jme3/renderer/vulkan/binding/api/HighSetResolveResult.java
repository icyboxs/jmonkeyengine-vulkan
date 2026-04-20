package com.jme3.renderer.vulkan.binding.api;

/**
 * HIGH provider 解析结果：
 * - set: descriptor set handle
 * - cacheHit: 本次是否命中缓存
 * @author icyboxs
 */
public final class HighSetResolveResult {
    public final long set;
    public final boolean cacheHit;

    public HighSetResolveResult(long set, boolean cacheHit) {
        this.set = set;
        this.cacheHit = cacheHit;
    }

    public static HighSetResolveResult miss(long set) {
        return new HighSetResolveResult(set, false);
    }

    public static HighSetResolveResult hit(long set) {
        return new HighSetResolveResult(set, true);
    }
}
