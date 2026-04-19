package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.frame.VulkanFrameDriver;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 frame slot 延迟执行资源销毁任务。
 * 策略：当前帧 push 到当前桶；每帧开始 flush “下一圈将被复用的桶”。
 */
public final class VulkanDeferredReleaseQueue {

    private final List<Runnable>[] buckets;

    @SuppressWarnings("unchecked")
    public VulkanDeferredReleaseQueue() {
        int n = VulkanFrameDriver.MAX_FRAMES_IN_FLIGHT;
        buckets = (List<Runnable>[]) new List<?>[n];
        for (int i = 0; i < n; i++) {
            buckets[i] = new ArrayList<>();
        }
    }

    public void enqueue(int frameIndex, Runnable r) {
        if (r == null) return;
        int idx = normalize(frameIndex);
        buckets[idx].add(r);
    }

    /**
     * 在 frameIndex 帧开始时调用，回收“即将被本帧复用”的桶。
     * 前提：FrameDriver 已经等待过该 frame slot 的 fence。
     */
    public void flushForFrame(int frameIndex) {
        int idx = normalize(frameIndex);
        List<Runnable> list = buckets[idx];
        if (list.isEmpty()) return;

        for (int i = 0; i < list.size(); i++) {
            try {
                list.get(i).run();
            } catch (Throwable ignored) {
            }
        }
        list.clear();
    }

    public void flushAll() {
        for (List<Runnable> list : buckets) {
            for (Runnable r : list) {
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
            list.clear();
        }
    }

    private int normalize(int frameIndex) {
        int n = buckets.length;
        int x = frameIndex % n;
        return x < 0 ? x + n : x;
    }
}
