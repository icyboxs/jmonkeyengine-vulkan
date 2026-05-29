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
        VkBuffer stagingBuffer = rf.createBuffer(
                sizeBytes,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );

        if (data != null) {
            rf.writeToMemory(stagingBuffer.memory, data, sizeBytes);
        }

        VkBuffer deviceBuffer = rf.createBuffer(
                sizeBytes,
                usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );

        // 利用 Factory 内置的全局 Batch 异步拷贝
        rf.copyBuffer(stagingBuffer, deviceBuffer, sizeBytes);

        // 托管给 Factory 随队列提交时一并释放
        rf.destroyStagingBuffer(stagingBuffer);

        return deviceBuffer;
    }

    // 辅助数据结构：持有转换后数据的包装
    private static class ConvertedData {

        Buffer data;
        int bytes;
        boolean needsFree;
    }

    private ConvertedData convertVbData(VertexBuffer vb, VertexBuffer.Type type, int vertexCount) {
        ConvertedData out = new ConvertedData();
        if (vb == null) {
            if (type == VertexBuffer.Type.Color && vertexCount > 0) {
                out.bytes = vertexCount * 4 * 4;
                ByteBuffer colBB = memAlloc(out.bytes);
                for (int i = 0; i < vertexCount * 4; i++) {
                    colBB.putFloat(1.0f);
                }
                colBB.flip();
                out.data = colBB;
                out.needsFree = true;
            } else if (type == VertexBuffer.Type.TexCoord && vertexCount > 0) {
                out.bytes = vertexCount * 2 * 4;
                ByteBuffer uvBB = memAlloc(out.bytes);
                for (int i = 0; i < vertexCount * 2; i++) {
                    uvBB.putFloat(0.0f);
                }
                uvBB.flip();
                out.data = uvBB;
                out.needsFree = true;
            }
            return out;
        }

        int components = vb.getNumComponents();
        int elements = vb.getNumElements();

        if (vb.getFormat() == VertexBuffer.Format.Float) {
            out.bytes = components * 4 * elements;
            out.data = dupAndClear(vb.getData());
            out.needsFree = false;
        } else {
            out.bytes = elements * components * 4;
            Buffer src = vb.getData();
            ByteBuffer floatData = memAlloc(out.bytes);

            if (src != null) {
                src.rewind();
                boolean norm = vb.isNormalized() || type == VertexBuffer.Type.Color || type == VertexBuffer.Type.BoneWeight;

                if (src instanceof ByteBuffer) {
                    ByteBuffer bb = (ByteBuffer) src;
                    // =========================================================
                    // 【已彻底修复】：删除了画蛇添足的 ABGR 翻转代码。
                    // 依靠 CPU 的小端序特征，从 ByteBuffer 中顺序读取出来的天然就是 [R, G, B, A] 顺序。
                    // 直接归一化并写入，完美解决黑色文字由于 Alpha 错位导致的透明问题！
                    // =========================================================
                    for (int i = 0; i < elements * components; i++) {
                        int val = bb.get() & 0xFF; // Unsigned Byte
                        floatData.putFloat(norm ? (val / 255.0f) : (float) val);
                    }
                } else if (src instanceof ShortBuffer) {
                    ShortBuffer sb = (ShortBuffer) src;
                    for (int i = 0; i < elements * components; i++) {
                        int val = sb.get() & 0xFFFF; // Unsigned Short
                        floatData.putFloat(norm ? (val / 65535.0f) : (float) val);
                    }
                } else if (src instanceof IntBuffer) {
                    IntBuffer ib = (IntBuffer) src;
                    for (int i = 0; i < elements * components; i++) {
                        floatData.putFloat((float) ib.get());
                    }
                } else {
                    memFree(floatData);
                    throw new UnsupportedOperationException("Unsupported format for " + type + ": " + vb.getFormat());
                }
            } else {
                for (int i = 0; i < elements * components; i++) {
                    floatData.putFloat(0.0f);
                }
            }
            floatData.flip();
            out.data = floatData;
            out.needsFree = true;
        }
        return out;
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

        // 【开启批量异步传输】：一次性向 GPU 队列抛过去所有的 Mesh Upload
        rf.beginTransferBatch();

        try {
            for (VertexBuffer.Type type : com.jme3.renderer.vulkan.pipeline.VkPipelineKey.VERTEX_TYPES) {
                VertexBuffer vb = mesh.getBuffer(type);

                ConvertedData cd = convertVbData(vb, type, gpu.vertexCount);
                if (cd.data != null) {
                    VkBuffer vbo = createOptimalBuffer(vb, cd.data, cd.bytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                    gpu.vbos.put(type, vbo);
                    if (cd.needsFree) {
                        memFree((ByteBuffer) cd.data);
                    }
                }
            }

            VertexBuffer idxVb = mesh.getBuffer(VertexBuffer.Type.Index);
            if (idxVb != null) {
                IndexBuffer ib = mesh.getIndexBuffer();
                if (ib != null && ib.getBuffer() != null) {
                    VertexBuffer.Format fmt = ib.getFormat();
                    int bytesPerIndex = (fmt == VertexBuffer.Format.UnsignedShort) ? 2 : 4;
                    int vkIndexType = (fmt == VertexBuffer.Format.UnsignedShort) ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
                    int indexCount = ib.size();
                    int indexBytes = indexCount * bytesPerIndex;

                    Buffer src = ib.getBuffer();
                    if (src instanceof ByteBuffer) {
                        src = ((ByteBuffer) src).duplicate();
                    } else if (src instanceof ShortBuffer) {
                        src = ((ShortBuffer) src).duplicate();
                    } else if (src instanceof IntBuffer) {
                        src = ((IntBuffer) src).duplicate();
                    }

                    if (src != null) {
                        src.clear();
                        gpu.ibo = createOptimalBuffer(idxVb, src, indexBytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
                        gpu.indexCount = indexCount;
                        gpu.vkIndexType = vkIndexType;
                    }
                }
            }
        } finally {
            // 提交全部复制指令，让 GPU 在后台干活，杜绝死锁卡死主线程
            rf.endTransferBatch();
        }

        meshCache.put(mesh, gpu);
        indexMeshVertexBuffers(mesh);
        return gpu;
    }

    /**
     * 【核心 Fast-Path】: 无损原地刷新动态顶点内容
     */
    public boolean updateBufferDataFast(Mesh mesh, VertexBuffer vb) {
        VkMeshGpu gpu = meshCache.get(mesh);
        if (gpu == null) {
            return false;
        }

        boolean isIndex = (vb.getBufferType() == VertexBuffer.Type.Index);
        VkBuffer vbo = isIndex ? gpu.ibo : gpu.vbos.get(vb.getBufferType());

        if (vbo == null) {
            return false;
        }

        int bytes;
        Buffer finalData = null;
        boolean needFree = false;

        if (isIndex) {
            IndexBuffer ib = mesh.getIndexBuffer();
            if (ib == null || ib.getBuffer() == null) {
                return false;
            }
            int bytesPerIndex = (ib.getFormat() == VertexBuffer.Format.UnsignedShort) ? 2 : 4;
            bytes = ib.size() * bytesPerIndex;

            Buffer src = ib.getBuffer();
            if (src instanceof ByteBuffer) {
                finalData = ((ByteBuffer) src).duplicate();
            } else if (src instanceof ShortBuffer) {
                finalData = ((ShortBuffer) src).duplicate();
            } else if (src instanceof IntBuffer) {
                finalData = ((IntBuffer) src).duplicate();
            }
            if (finalData != null) {
                finalData.clear();
            }
        } else {
            ConvertedData cd = convertVbData(vb, vb.getBufferType(), mesh.getVertexCount());
            if (cd.data == null) {
                return false;
            }
            bytes = cd.bytes;
            finalData = cd.data;
            needFree = cd.needsFree;
        }

        if (finalData == null) {
            return false;
        }

        // 【核心修复】：Buffer Orphaning (孤儿化)
        // 绝对不要直接覆写 vbo.memory，这会导致 GPU 读到撕裂数据甚至 Device Lost。
        // 我们将旧 Buffer 投递到延迟销毁队列（等 MAX_FRAMES_IN_FLIGHT 后安全销毁），并开辟新 Buffer
        if (vbo.isHostVisible) {
            deferDestroyBuffer(vbo);

            int usage = isIndex ? VK_BUFFER_USAGE_INDEX_BUFFER_BIT : VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
            VkBuffer newVbo = createOptimalBuffer(vb, finalData, bytes, usage);

            if (isIndex) {
                gpu.ibo = newVbo;
                gpu.indexCount = mesh.getIndexBuffer().size();
            } else {
                gpu.vbos.put(vb.getBufferType(), newVbo);
                if (vb.getBufferType() == VertexBuffer.Type.Position) {
                    gpu.vertexCount = vb.getNumElements();
                }
            }
            if (needFree) {
                memFree((ByteBuffer) finalData);
            }
            return true;
        }

        if (needFree) {
            memFree((ByteBuffer) finalData);
        }
        return false;
    }

    // 提供给上层的智能判定入口
    public void updateOrDestroyByVertexBuffer(VertexBuffer vb) {
        if (vb == null) {
            return;
        }
        java.util.Set<Mesh> meshes = vbToMeshes.get(vb);
        if (meshes == null || meshes.isEmpty()) {
            return;
        }

        java.util.ArrayList<Mesh> affected = new java.util.ArrayList<>(meshes);
        for (Mesh m : affected) {
            // 如果不能原址复写，就暴力销毁触发下次重建（降级）
            if (!updateBufferDataFast(m, vb)) {
                destroy(m);
            }
        }
    }

    // 以前旧的接口，现直接路由到智能更新
    public void destroyByVertexBuffer(VertexBuffer vb) {
        updateOrDestroyByVertexBuffer(vb);
    }

    public void destroy(Mesh mesh) {
        VkMeshGpu gpu = meshCache.remove(mesh);
        if (gpu == null) {
            removeMeshFromReverseIndex(mesh);
            return;
        }

        for (VkBuffer b : gpu.vbos.values()) {
            deferDestroyBuffer(b);
        }
        if (gpu.ibo != null) {
            deferDestroyBuffer(gpu.ibo);
        }

        removeMeshFromReverseIndex(mesh);
    }

    public void destroyAll() {
        for (VkMeshGpu gpu : meshCache.values()) {
            if (gpu == null) {
                continue;
            }
            for (VkBuffer b : gpu.vbos.values()) {
                deferDestroyBuffer(b);
            }
            if (gpu.ibo != null) {
                deferDestroyBuffer(gpu.ibo);
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
            return ((ByteBuffer) src).duplicate().clear();
        }
        if (src instanceof java.nio.FloatBuffer) {
            return ((java.nio.FloatBuffer) src).duplicate().clear();
        }
        if (src instanceof java.nio.IntBuffer) {
            return ((java.nio.IntBuffer) src).duplicate().clear();
        }
        if (src instanceof java.nio.ShortBuffer) {
            return ((java.nio.ShortBuffer) src).duplicate().clear();
        }
        if (src instanceof java.nio.LongBuffer) {
            return ((java.nio.LongBuffer) src).duplicate().clear();
        }
        throw new UnsupportedOperationException("Unsupported Buffer type: " + src.getClass());
    }

    private void indexMeshVertexBuffers(Mesh mesh) {
        if (mesh == null) {
            return;
        }
        removeMeshFromReverseIndex(mesh);
        for (VertexBuffer vb : mesh.getBufferList().getArray()) {
            if (vb == null) {
                continue;
            }
            vbToMeshes.computeIfAbsent(vb, k -> java.util.Collections.newSetFromMap(new IdentityHashMap<>())).add(mesh);
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
        deferredReleaseQueue.enqueue(frameIndexSupplier.getAsInt(), () -> rf.destroyBuffer(b));
    }
}
