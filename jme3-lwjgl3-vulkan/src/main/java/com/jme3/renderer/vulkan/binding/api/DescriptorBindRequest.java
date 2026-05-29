package com.jme3.renderer.vulkan.binding.api;

import com.jme3.renderer.vulkan.cmd.ComputeCmd;
import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.pipeline.VulkanPipeline;

/**
 * 单次 draw 的 descriptor 绑定请求。 * 性能优化说明： 引入了 acquire/recycle 模式以实现对象池， 避免在
 * VKRenderer 的渲染循环中每帧进行数千次内存分配。
 *
 * @author icyboxs
 */
public final class DescriptorBindRequest {

    // --- 原始核心字段 ---
    public int pipelineHash;
    public int materialHash;
    public int extraTexId;
    public int objectId;

    public int frameIndex;
    public long perDrawUboHandle;
    public int perDrawUboRange;

    public VulkanPipeline pipeline;
    public DrawCmd drawCmd;

    public ComputeCmd computeCmd;

    public long materialSet0;
    public int dynamicOffset;

    public long pipelineLayout;
    public int pipelineSetCount;

    // --- 对象池实现 ---
    private static final DescriptorBindRequest[] POOL = new DescriptorBindRequest[64];
    private static int poolPtr = -1;

    /**
     * 默认构造函数（仅供 POOL 内部使用）
     */
    public DescriptorBindRequest() {
    }

    /**
     * 从池中获取一个实例，如果池为空则创建新实例。 替代原有的 DescriptorBindRequest.of() 静态工厂。
     *
     * @return
     */
    public static DescriptorBindRequest acquire() {
        synchronized (POOL) {
            if (poolPtr < 0) {
                return new DescriptorBindRequest();
            }
            DescriptorBindRequest req = POOL[poolPtr];
            POOL[poolPtr--] = null;
            return req;
        }
    }

    /**
     * 回收对象到池中，重置引用字段。 必须在 VKRenderer.recordFrame 的循环末尾显式调用。
     */
    public void recycle() {
        this.pipeline = null;
        this.drawCmd = null;
        this.materialSet0 = 0L;
        this.perDrawUboHandle = 0L;
        this.computeCmd = null;
        synchronized (POOL) {
            if (poolPtr < POOL.length - 1) {
                POOL[++poolPtr] = this;
            }
        }
    }

    /**
     * 初始化实例数据。 对应原 recordFrame 中的 setup 逻辑。
     */
    public void setup(int frameIndex, VulkanPipeline pipeline, DrawCmd drawCmd, long materialSet0, int dynamicOffset) {
        this.frameIndex = frameIndex;
        this.pipeline = pipeline;
        this.drawCmd = drawCmd;
        this.materialSet0 = materialSet0;
        this.dynamicOffset = dynamicOffset;

        // 缓存频繁访问的冗余信息，减少 getter 调用
        if (pipeline != null) {
            this.pipelineLayout = pipeline.getPipelineLayout();
            this.pipelineSetCount = pipeline.getDescriptorSetLayoutCount();
        } else {
            this.pipelineLayout = 0L;
            this.pipelineSetCount = 0;
        }

        if (drawCmd != null) {
            this.pipelineHash = (drawCmd.pipelineKey != null) ? drawCmd.pipelineKey.hashCode() : 0;
            this.materialHash = (drawCmd.materialBatchKey != null) ? drawCmd.materialBatchKey.hashCode() : 0;
            this.objectId = drawCmd.objectId;
            this.extraTexId = (drawCmd.jmeExtraSnapshot != null) ? System.identityHashCode(drawCmd.jmeExtraSnapshot) : 0;
        } else {
            this.pipelineHash = 0;
            this.materialHash = 0;
            this.objectId = 0;
            this.extraTexId = 0;
        }

        // 默认 Range
        this.perDrawUboRange = 256;
    }

    public void setupCompute(int frameIndex, VulkanPipeline pipeline, com.jme3.renderer.vulkan.cmd.ComputeCmd cc, int dynamicOffset) {
        this.frameIndex = frameIndex;
        this.pipeline = pipeline;
        this.computeCmd = cc;
        this.drawCmd = null;
        this.materialSet0 = 0L;
        this.dynamicOffset = dynamicOffset;

        if (pipeline != null) {
            this.pipelineLayout = pipeline.getPipelineLayout();
            this.pipelineSetCount = pipeline.getDescriptorSetLayoutCount();
        } else {
            this.pipelineLayout = 0L;
            this.pipelineSetCount = 0;
        }

        this.pipelineHash = (cc != null) ? cc.compHash : 0;
        this.materialHash = 0;
        this.objectId = 0;
        this.extraTexId = 0;
        this.perDrawUboRange = 256;
    }
}
