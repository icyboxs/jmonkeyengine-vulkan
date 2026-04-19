package com.jme3.renderer.vulkan.runtime;

/**
 * Vulkan runtime statistics.
 */
public final class VulkanRuntimeStats {

    public long reflectionHit;
    public long reflectionMiss;
    public long frameReflectionHit;
    public long frameReflectionMiss;
    public long layoutHit;
    public long layoutMiss;
    public long frameLayoutHit;
    public long frameLayoutMiss;

    public long materialHit;
    public long materialMiss;
    public long materialSetAlloc;
    public long materialSetWrite;
    public long materialPoolGrow;

    public long frameMaterialHit;
    public long frameMaterialMiss;
    public long frameMaterialSetWrite;

    public void beginFrame() {
        frameMaterialHit = 0;
        frameMaterialMiss = 0;
        frameMaterialSetWrite = 0;
        frameReflectionHit = 0;
        frameReflectionMiss = 0;
        frameLayoutHit = 0;
        frameLayoutMiss = 0;
    }
}
