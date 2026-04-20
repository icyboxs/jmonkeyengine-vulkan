package com.jme3.renderer.vulkan.resource;

import com.jme3.renderer.vulkan.mesh.VkMeshGpu;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanMeshManager {

    private final VulkanDeferredReleaseQueue deferredReleaseQueue;
    private final IntSupplier frameIndexSupplier;

    private final VkResourceFactory rf;
    private final Map<Mesh, VkMeshGpu> meshCache = new IdentityHashMap<>();

    // 反向索引：VertexBuffer -> 使用它的 Mesh 集合
    private final Map<VertexBuffer, java.util.Set<Mesh>> vbToMeshes = new IdentityHashMap<>();

    public VulkanMeshManager(VkResourceFactory rf,
            VulkanDeferredReleaseQueue deferredReleaseQueue,
            IntSupplier frameIndexSupplier) {
        if (rf == null) {
            throw new IllegalArgumentException("rf is null");
        }
        this.rf = rf;
        this.deferredReleaseQueue = deferredReleaseQueue;
        this.frameIndexSupplier = frameIndexSupplier;
    }

    /**
     * 自动分配最优 Buffer：
     * - Dynamic/Stream -> HOST_VISIBLE_BIT (避免每帧拷贝开销)
     * - Static -> DEVICE_LOCAL_BIT (经由 Staging Buffer，最大化绘制性能)
     */
    private VkBuffer createOptimalBuffer(VertexBuffer vb, Buffer data, int sizeBytes, int vkUsage) {
        boolean isDynamic = false;
        if (vb != null) {
            VertexBuffer.Usage usage = vb.getUsage();
            if (usage == VertexBuffer.Usage.Dynamic || usage == VertexBuffer.Usage.Stream) {
                isDynamic = true;
            }
        }

        if (isDynamic) {
            return createHostVisibleBuffer(data, sizeBytes, vkUsage);
        } else {
            return createDeviceLocalBuffer(data, sizeBytes, vkUsage);
        }
    }

    private VkBuffer createHostVisibleBuffer(Buffer data, int sizeBytes, int usage) {
        VkBuffer buffer = rf.createBuffer(
                sizeBytes,
                usage,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        if (data != null) {
            rf.writeToMemory(buffer.memory, data, sizeBytes);
        }
        return buffer;
    }

    private VkBuffer createDeviceLocalBuffer(Buffer data, int sizeBytes, int usage) {
        // 1. 创建位于系统内存中的暂存缓冲
        VkBuffer stagingBuffer = rf.createBuffer(
                sizeBytes,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        
        // 2. 将数据写入暂存缓冲
        if (data != null) {
            rf.writeToMemory(stagingBuffer.memory, data, sizeBytes);
        }

        // 3. 创建位于 GPU 高速显存中的目标缓冲
        VkBuffer deviceBuffer = rf.createBuffer(
                sizeBytes,
                usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );

        // 4. 将暂存缓冲的数据拷贝到高速显存
        rf.copyBuffer(stagingBuffer, deviceBuffer, sizeBytes);

        // 5. 立即清理暂存缓冲
        rf.destroyBuffer(stagingBuffer);

        return deviceBuffer;
    }

    public VkMeshGpu getOrCreate(Mesh mesh) {
        if (mesh == null) {
            throw new IllegalArgumentException("mesh is null");
        }

        VkMeshGpu cached = meshCache.get(mesh);
        if (cached != null) {
            return cached;
        }

        VkMeshGpu gpu = new VkMeshGpu();
        gpu.vertexCount = mesh.getVertexCount();

        // Position
        VertexBuffer pos = mesh.getBuffer(VertexBuffer.Type.Position);
        if (pos == null) {
            throw new UnsupportedOperationException("Mesh missing Position buffer");
        }
        if (pos.getFormat() != VertexBuffer.Format.Float) {
            throw new UnsupportedOperationException("Only Float Position supported: " + pos.getFormat());
        }
        if (pos.getNumComponents() != 3) {
            throw new UnsupportedOperationException("Only vec3 Position supported, components=" + pos.getNumComponents());
        }

        int posBytes = pos.getNumComponents() * 4 * pos.getNumElements();
        Buffer posDup = dupAndClear(pos.getData());
        VkBuffer posVbo = createOptimalBuffer(pos, posDup, posBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        gpu.vbos.put(VertexBuffer.Type.Position, posVbo);

        // TexCoord
        VertexBuffer uv = mesh.getBuffer(VertexBuffer.Type.TexCoord);
        if (uv == null) {
            int uvBytes = gpu.vertexCount * 2 * 4;
            ByteBuffer uvBB = memAlloc(uvBytes);
            // 默认无 UV 数据被视为 Static
            VkBuffer uvVbo = createOptimalBuffer(null, uvBB, uvBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            memFree(uvBB);
            gpu.vbos.put(VertexBuffer.Type.TexCoord, uvVbo);
        } else {
            if (uv.getFormat() != VertexBuffer.Format.Float) {
                throw new UnsupportedOperationException("Only Float TexCoord supported: " + uv.getFormat());
            }
            if (uv.getNumComponents() != 2) {
                throw new UnsupportedOperationException("Only vec2 TexCoord supported, components=" + uv.getNumComponents());
            }

            int uvBytes = uv.getNumComponents() * 4 * uv.getNumElements();
            Buffer uvDup = dupAndClear(uv.getData());
            VkBuffer uvVbo = createOptimalBuffer(uv, uvDup, uvBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            gpu.vbos.put(VertexBuffer.Type.TexCoord, uvVbo);
        }

        // Index(optional)
        VertexBuffer idxVb = mesh.getBuffer(VertexBuffer.Type.Index);
        if (idxVb != null) {
            IndexBuffer ib = mesh.getIndexBuffer();
            if (ib == null) {
                throw new UnsupportedOperationException("Mesh has Index VB but getIndexBuffer() is null");
            }

            Buffer raw = ib.getBuffer();
            if (raw == null) {
                throw new UnsupportedOperationException("Mesh index buffer is virtual (null backing buffer)");
            }

            VertexBuffer.Format fmt = ib.getFormat();
            int bytesPerIndex;
            int vkIndexType;

            if (fmt == VertexBuffer.Format.UnsignedShort) {
                bytesPerIndex = 2;
                vkIndexType = VK_INDEX_TYPE_UINT16;
            } else if (fmt == VertexBuffer.Format.UnsignedInt) {
                bytesPerIndex = 4;
                vkIndexType = VK_INDEX_TYPE_UINT32;
            } else if (fmt == VertexBuffer.Format.UnsignedByte) {
                throw new UnsupportedOperationException("UnsignedByte index needs VK_EXT_index_type_uint8");
            } else {
                throw new UnsupportedOperationException("Unsupported index format: " + fmt);
            }

            int indexCount = ib.size();
            int indexBytes = indexCount * bytesPerIndex;

            Buffer src = raw;
            if (src instanceof ByteBuffer) {
                ByteBuffer bb = ((ByteBuffer) src).duplicate();
                bb.clear();
                src = bb;
            } else if (src instanceof ShortBuffer) {
                ShortBuffer sb = ((ShortBuffer) src).duplicate();
                sb.clear();
                src = sb;
            } else if (src instanceof IntBuffer) {
                IntBuffer ibb = ((IntBuffer) src).duplicate();
                ibb.clear();
                src = ibb;
            } else {
                throw new UnsupportedOperationException("Unsupported index buffer backing type: " + src.getClass());
            }

            VkBuffer ibo = createOptimalBuffer(idxVb, src, indexBytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);

            gpu.ibo = ibo;
            gpu.indexCount = indexCount;
            gpu.vkIndexType = vkIndexType;
        }

        meshCache.put(mesh, gpu);
        indexMeshVertexBuffers(mesh);
        return gpu;
    }

    public void destroy(Mesh mesh) {
        VkMeshGpu gpu = meshCache.remove(mesh);
        if (gpu == null) {
            removeMeshFromReverseIndex(mesh);
            return;
        }

        for (VkBuffer b : gpu.vbos.values()) {
            deferDestroyBuffer(b); // 必须走延迟销毁
        }
        if (gpu.ibo != null) {
            deferDestroyBuffer(gpu.ibo); // 必须走延迟销毁
        }

        removeMeshFromReverseIndex(mesh);
    }

    public void destroyAll() {
        for (VkMeshGpu gpu : meshCache.values()) {
            if (gpu == null) {
                continue;
            }
            for (VkBuffer b : gpu.vbos.values()) {
                deferDestroyBuffer(b); // 必须走延迟销毁
            }
            if (gpu.ibo != null) {
                deferDestroyBuffer(gpu.ibo); // 必须走延迟销毁
            }
        }
        meshCache.clear();
        vbToMeshes.clear();
    }

    private static Buffer dupAndClear(Buffer src) {
        if (src == null) {
            return null;
        }
        if (src instanceof ByteBuffer) {
            ByteBuffer b = ((ByteBuffer) src).duplicate();
            b.clear();
            return b;
        }
        if (src instanceof java.nio.FloatBuffer) {
            java.nio.FloatBuffer b = ((java.nio.FloatBuffer) src).duplicate();
            b.clear();
            return b;
        }
        if (src instanceof java.nio.IntBuffer) {
            java.nio.IntBuffer b = ((java.nio.IntBuffer) src).duplicate();
            b.clear();
            return b;
        }
        if (src instanceof java.nio.ShortBuffer) {
            java.nio.ShortBuffer b = ((java.nio.ShortBuffer) src).duplicate();
            b.clear();
            return b;
        }
        if (src instanceof java.nio.LongBuffer) {
            java.nio.LongBuffer b = ((java.nio.LongBuffer) src).duplicate();
            b.clear();
            return b;
        }
        throw new UnsupportedOperationException("Unsupported Buffer type: " + src.getClass());
    }

    private void indexMeshVertexBuffers(Mesh mesh) {
        if (mesh == null) {
            return;
        }

        // 先移除旧索引，避免重复登记
        removeMeshFromReverseIndex(mesh);

        for (VertexBuffer vb : mesh.getBufferList().getArray()) {
            if (vb == null) {
                continue;
            }
            vbToMeshes.computeIfAbsent(vb, k -> java.util.Collections.newSetFromMap(new IdentityHashMap<>()))
                    .add(mesh);
        }
    }

    public void destroyByVertexBuffer(VertexBuffer vb) {
        if (vb == null) {
            return;
        }

        java.util.Set<Mesh> meshes = vbToMeshes.remove(vb);
        if (meshes == null || meshes.isEmpty()) {
            return;
        }

        // 拷贝一份，避免 destroy(mesh) 时改动集合导致并发修改
        java.util.ArrayList<Mesh> affected = new java.util.ArrayList<>(meshes);
        for (Mesh m : affected) {
            destroy(m);
        }
    }

    private void removeMeshFromReverseIndex(Mesh mesh) {
        if (mesh == null) {
            return;
        }

        for (java.util.Iterator<Map.Entry<VertexBuffer, java.util.Set<Mesh>>> it = vbToMeshes.entrySet().iterator(); it.hasNext();) {
            Map.Entry<VertexBuffer, java.util.Set<Mesh>> e = it.next();
            java.util.Set<Mesh> set = e.getValue();
            if (set != null) {
                set.remove(mesh);
                if (set.isEmpty()) {
                    it.remove();
                }
            } else {
                it.remove();
            }
        }
    }

    private void deferDestroyBuffer(VkBuffer b) {
        if (b == null) {
            return;
        }

        if (deferredReleaseQueue == null || frameIndexSupplier == null) {
            rf.destroyBuffer(b);
            return;
        }

        int fi = frameIndexSupplier.getAsInt();
        deferredReleaseQueue.enqueue(fi, () -> rf.destroyBuffer(b));
    }

}