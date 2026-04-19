package com.jme3.renderer.vulkan.cmd;

import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.renderer.vulkan.pipeline.VkVariantKey;
import com.jme3.renderer.vulkan.reflection.ParamBindingPlan;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;

public final class DrawCmd {

    public Mesh mesh;
    public int lod;
    public int count;
    public VertexBuffer[] instanceData;
    public RenderState renderState;

    /** ShaderSource.getSource() 主体源码 */
    public String vertSrc;
    public String fragSrc;

    /** ShaderSource.getDefines()，由前端生成的 define/prologue */
    public String vertDefines;
    public String fragDefines;

    /** 真正送去 Vulkan 编译的最终源码 = defines + source */
    public String finalVertSrc;
    public String finalFragSrc;

    /** hash 必须基于最终编译输入，而不是仅主体源码 */
    public int vertHash;
    public int fragHash;

    public Texture jmeTex0Snapshot;
    public Texture jmeLightSnapshot;
    public MaterialSnapshotKey materialKeySnapshot;
    public MaterialResolvePlan materialResolvePlan;
    public boolean useWhiteTex0;
    public boolean useWhiteLight;

    public Matrix4f wvpSnapshot;
    public ColorRGBA colorSnapshot;

    public VkVariantKey variant;
    public VkPipelineKey pipelineKey;

    public int uboDynamicOffset;
    public MaterialBatchKey materialBatchKey;
    public int objectId;

    public long tex0SamplerSnapshot;
    public long lightSamplerSnapshot;

    public Texture jmeExtraSnapshot;
    public boolean useWhiteExtra;
    public long extraSamplerSnapshot;

    public ParamBindingPlan.BindingSlot[] customImageSlots;
    public Texture[] customImageTextures;
    public int customImageCount;

    public boolean isRenderable() {
        return pipelineKey != null
                && mesh != null
                && finalVertSrc != null
                && finalFragSrc != null;
    }

    @Override
    public String toString() {
        return "DrawCmd{mesh=" + (mesh != null ? mesh.getId() : -1)
                + ", objectId=" + objectId
                + ", pipelineKey=" + (pipelineKey != null ? pipelineKey.hashCode() : 0)
                + '}';
    }
}
