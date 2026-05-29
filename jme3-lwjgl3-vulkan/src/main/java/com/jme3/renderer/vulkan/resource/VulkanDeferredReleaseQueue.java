package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.frame.VulkanFrameDriver;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 按 frame slot 延迟执行资源销毁任务。
 * 【修复】：使用 ConcurrentLinkedQueue 保证跨线程投递与消费的安全。
 */
public final class VulkanDeferredReleaseQueue {

    private final ConcurrentLinkedQueue<Runnable>[] buckets;

    @SuppressWarnings("unchecked")
    public VulkanDeferredReleaseQueue() {
        int n = VulkanFrameDriver.MAX_FRAMES_IN_FLIGHT;
        buckets = new ConcurrentLinkedQueue[n];
        for (int i = 0; i < n; i++) {
            buckets[i] = new ConcurrentLinkedQueue<>();
        }
    }

    public void enqueue(int frameIndex, Runnable r) {
        if (r == null) return;
        int idx = normalize(frameIndex);
        buckets[idx].offer(r); // 【修复】：无锁入队
    }

    public void flushForFrame(int frameIndex) {
        int idx = normalize(frameIndex);
        ConcurrentLinkedQueue<Runnable> queue = buckets[idx];
        
        Runnable r;
        // 【修复】：安全出队并执行，不怕边遍历边有新任务进来
        while ((r = queue.poll()) != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public void flushAll() {
        for (ConcurrentLinkedQueue<Runnable> queue : buckets) {
            Runnable r;
            while ((r = queue.poll()) != null) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        }
    }

    private int normalize(int frameIndex) {
        int n = buckets.length;
        int x = frameIndex % n;
        return x < 0 ? x + n : x;
    }
}