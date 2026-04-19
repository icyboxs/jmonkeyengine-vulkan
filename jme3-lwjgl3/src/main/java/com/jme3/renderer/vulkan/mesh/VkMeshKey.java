package com.jme3.renderer.vulkan.mesh;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;

import java.nio.Buffer;
import java.util.Objects;
import java.util.logging.Logger;

import static org.lwjgl.vulkan.VK10.*;

public final class VkMeshKey {

    private static final Logger LOGGER = Logger.getLogger(VkMeshKey.class.getName());

    // -------- DIAG 控制（只打印前 N 次）--------
    private static final boolean DIAG = true;
    private static int diagLeft = 30;

    public final int vertexCount;

    public final int posBufId;
    public final int posBytes;

    public final int uvBufId;
    public final int uvBytes;

    public final int idxBufId;     // 0 if no index
    public final int idxBytes;     // 0 if no index
    public final int vkIndexType;  // Vulkan enum value (UINT16 may be 0!)

    private VkMeshKey(int vertexCount,
            int posBufId, int posBytes,
            int uvBufId, int uvBytes,
            int idxBufId, int idxBytes,
            int vkIndexType) {
        this.vertexCount = vertexCount;
        this.posBufId = posBufId;
        this.posBytes = posBytes;
        this.uvBufId = uvBufId;
        this.uvBytes = uvBytes;
        this.idxBufId = idxBufId;
        this.idxBytes = idxBytes;
        this.vkIndexType = vkIndexType;
    }

    public static VkMeshKey from(Mesh mesh) {
        if (mesh == null) {
            throw new IllegalArgumentException("mesh is null");
        }

        int vtxCount = mesh.getVertexCount();

        // -------- Position --------
        VertexBuffer pos = mesh.getBuffer(VertexBuffer.Type.Position);
        if (pos == null) {
            throw new IllegalArgumentException("mesh missing Position");
        }

        // 同一个 VertexBuffer 连续两次 getData() 是否返回不同对象？
        Buffer a = pos.getData();
        Buffer b = pos.getData();
        if (a == null) {
            throw new IllegalArgumentException("Position data is null");
        }

        int posBytes = pos.getNumComponents() * 4 * pos.getNumElements();
        int posId = System.identityHashCode(a);

        // 强制打印前 N 次，确认代码路径生效 + a/b 是否同一对象
        if (DIAG && diagLeft-- > 0) {
            LOGGER.fine("[Diag-VkMeshKey] mesh@" + System.identityHashCode(mesh)
                    + " meshId=" + mesh.getId()
                    + " vtx=" + vtxCount
                    + " pos: a@" + System.identityHashCode(a)
                    + " b@" + (b != null ? System.identityHashCode(b) : 0)
                    + " sameObj=" + (a == b)
                    + " class=" + a.getClass().getName()
                    + " posBytes=" + posBytes);
        }

        // -------- TexCoord --------
        VertexBuffer uv = mesh.getBuffer(VertexBuffer.Type.TexCoord);
        Buffer uvData = (uv != null) ? uv.getData() : null;
        int uvBytes = (uv != null && uvData != null) ? (uv.getNumComponents() * 4 * uv.getNumElements()) : 0;
        int uvId = (uvData != null) ? System.identityHashCode(uvData) : 0;

        // -------- Index (optional) --------
        VertexBuffer idxVb = mesh.getBuffer(VertexBuffer.Type.Index);
        int idxId = 0;
        int idxBytes = 0;
        int vkIndexType = 0; // 注意：UINT16 的枚举值可能就是 0（合法）

        if (idxVb != null) {
            IndexBuffer ib = mesh.getIndexBuffer();
            if (ib == null) {
                throw new IllegalArgumentException("mesh has Index buffer but mesh.getIndexBuffer() is null");
            }

            Buffer raw = ib.getBuffer();
            if (raw == null) {
                throw new IllegalArgumentException("Index buffer backing buffer is null");
            }

            VertexBuffer.Format fmt = ib.getFormat();

            int bytesPerIndex;
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
            idxBytes = indexCount * bytesPerIndex;
            idxId = System.identityHashCode(raw);

            // 合法性检查（不要用 !=0 判断）
            if (vkIndexType != VK_INDEX_TYPE_UINT16 && vkIndexType != VK_INDEX_TYPE_UINT32) {
                throw new IllegalStateException("Invalid vkIndexType=" + vkIndexType + " for fmt=" + fmt);
            }
        }

        return new VkMeshKey(vtxCount, posId, posBytes, uvId, uvBytes, idxId, idxBytes, vkIndexType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VkMeshKey)) {
            return false;
        }
        VkMeshKey that = (VkMeshKey) o;
        return vertexCount == that.vertexCount
                && posBufId == that.posBufId
                && posBytes == that.posBytes
                && uvBufId == that.uvBufId
                && uvBytes == that.uvBytes
                && idxBufId == that.idxBufId
                && idxBytes == that.idxBytes
                && vkIndexType == that.vkIndexType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vertexCount, posBufId, posBytes, uvBufId, uvBytes, idxBufId, idxBytes, vkIndexType);
    }

    @Override
    public String toString() {
        return "VkMeshKey{vtx=" + vertexCount
                + ", pos@" + posBufId + " bytes=" + posBytes
                + ", uv@" + uvBufId + " bytes=" + uvBytes
                + ", idx@" + idxBufId + " bytes=" + idxBytes + " vkIndexType=" + vkIndexType
                + "}";
    }
}
