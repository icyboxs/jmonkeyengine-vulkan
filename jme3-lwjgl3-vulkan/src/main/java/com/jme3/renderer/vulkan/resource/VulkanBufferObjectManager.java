package com.jme3.renderer.vulkan.resource;

import com.jme3.shader.bufferobject.BufferObject;
import java.nio.Buffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import static org.lwjgl.util.vma.Vma.vmaFlushAllocation;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 自动映射 JME3 BufferObject 到底层 VMA 显存块。 完全摒弃缓慢的 Staging Buffer，使用 HOST_VISIBLE
 * 内存实现 CPU 零延迟直写 GPU。
 */
public final class VulkanBufferObjectManager {

    private final VkResourceFactory rf;
    private final VulkanDeferredReleaseQueue deferredReleaseQueue;
    private final IntSupplier frameIndexSupplier;
    private final Map<BufferObject, VkBuffer> boCache = new IdentityHashMap<>();

    public VulkanBufferObjectManager(VkResourceFactory rf, VulkanDeferredReleaseQueue deferredReleaseQueue, IntSupplier frameIndexSupplier) {
        this.rf = rf;
        this.deferredReleaseQueue = deferredReleaseQueue;
        this.frameIndexSupplier = frameIndexSupplier;
    }

    private int getBytes(Buffer data) {
        if (data == null) {
            return 0;
        }
        int limit = data.capacity();
        if (data instanceof java.nio.FloatBuffer || data instanceof java.nio.IntBuffer) {
            return limit * 4;
        }
        if (data instanceof java.nio.ShortBuffer) {
            return limit * 2;
        }
        if (data instanceof java.nio.DoubleBuffer || data instanceof java.nio.LongBuffer) {
            return limit * 8;
        }
        return limit;
    }

    public VkBuffer getOrCreate(BufferObject bo) {
        if (bo == null) {
            return null;
        }
        VkBuffer buf = boCache.get(bo);
        if (buf != null) {
            return buf;
        }

        Buffer data = bo.getData();
        int size = getBytes(data);
        if (size <= 0) {
            size = 256;
        }

        // 【性能飞跃】：直接向 VMA 索要主机可见且一致的 Storage 内存！
        buf = rf.createBuffer(size,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        boCache.put(bo, buf);
        updateData(bo, buf);
        return buf;
    }

    public void updateData(BufferObject bo) {
        VkBuffer buf = boCache.get(bo);
        if (buf == null) {
            getOrCreate(bo);
        } else {
            updateData(bo, buf);
        }
    }

    private void updateData(BufferObject bo, VkBuffer vkBuf) {
        Buffer data = bo.getData();
        int size = getBytes(data);
        if (size <= 0) {
            return;
        }

        if (size > vkBuf.capacity) {
            destroy(bo);
            getOrCreate(bo);
            return;
        }

        if (vkBuf.mappedPointer != 0L) {
            long dst = vkBuf.mappedPointer;
            if (data instanceof java.nio.ByteBuffer) {
                java.nio.ByteBuffer bb = (java.nio.ByteBuffer) data;
                org.lwjgl.system.MemoryUtil.memCopy(org.lwjgl.system.MemoryUtil.memAddress(bb) + bb.position(), dst, size);
            } else if (data instanceof java.nio.FloatBuffer) {
                java.nio.FloatBuffer fb = (java.nio.FloatBuffer) data;
                org.lwjgl.system.MemoryUtil.memCopy(org.lwjgl.system.MemoryUtil.memAddress(fb) + ((long) fb.position() * 4L), dst, size);
            } else if (data instanceof java.nio.IntBuffer) {
                java.nio.IntBuffer ib = (java.nio.IntBuffer) data;
                org.lwjgl.system.MemoryUtil.memCopy(org.lwjgl.system.MemoryUtil.memAddress(ib) + ((long) ib.position() * 4L), dst, size);
            }

            // 【修正】：通过工厂方法安全调用，解决 private 访问报错
            rf.flushAllocation(vkBuf.memory, 0, size);

        } else {
            rf.writeToMemory(vkBuf.memory, data, size);
        }

        bo.clearUpdateNeeded();
    }

    public void destroy(BufferObject bo) {
        VkBuffer buf = boCache.remove(bo);
        if (buf != null) {
            if (deferredReleaseQueue != null && frameIndexSupplier != null) {
                deferredReleaseQueue.enqueue(frameIndexSupplier.getAsInt(), () -> rf.destroyBuffer(buf));
            } else {
                rf.destroyBuffer(buf);
            }
        }
    }

    public void destroyAll() {
        for (VkBuffer b : boCache.values()) {
            rf.destroyBuffer(b);
        }
        boCache.clear();
    }
}
