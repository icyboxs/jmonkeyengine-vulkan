package com.jme3.renderer.vulkan.frame;

import com.jme3.renderer.vulkan.binding.api.DescriptorBindRequest;
import com.jme3.renderer.vulkan.binding.api.DescriptorBindResult;
import com.jme3.renderer.vulkan.binding.api.DescriptorSetBinder;
import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.mesh.VkMeshGpu;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.renderer.vulkan.pipeline.VulkanPipeline;
import com.jme3.renderer.vulkan.resource.VkBuffer;
import com.jme3.renderer.vulkan.resource.VkUboLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

public final class DrawExecutor {

    private final DescriptorSetBinder descriptorSetBinder;
    private long[] lastBoundSets = new long[0];

    public DrawExecutor(DescriptorSetBinder descriptorSetBinder) {
        this.descriptorSetBinder = descriptorSetBinder;
    }

    public void beginFrame(int frameIndex) {
        if (descriptorSetBinder != null) descriptorSetBinder.beginFrame(frameIndex);
    }

    public void cleanup() {
        if (descriptorSetBinder != null) descriptorSetBinder.cleanup();
        lastBoundSets = new long[0];
    }

public void executeList(VkCommandBuffer cmd, MemoryStack rootStack, int frameIndex, VulkanFrameInfo frame, java.util.List<DrawCmd> commandList) {
        VulkanPipeline lastPipeline = null;
        VkPipelineKey lastPipelineKey = null;
        float timeSeconds = (float) (System.nanoTime() * 1e-9);
        
        int fbW = frame.width;
        int fbH = frame.height;
        
        // 【新增】：追踪当前指令的视口与裁剪状态，避免每 draw 重复下发冗余的 Vulkan API
        int lastVpX = Integer.MIN_VALUE, lastVpY = Integer.MIN_VALUE, lastVpW = Integer.MIN_VALUE, lastVpH = Integer.MIN_VALUE;
        float lastDepthStart = Float.NaN, lastDepthEnd = Float.NaN;
        int lastScX = Integer.MIN_VALUE, lastScY = Integer.MIN_VALUE, lastScW = Integer.MIN_VALUE, lastScH = Integer.MIN_VALUE;

        for (DrawCmd dc : commandList) {
            if (!dc.isRenderable()) continue;

            try (MemoryStack frameStack = rootStack.push()) {
                
                // ==========================================
                // 动态执行 Viewport 和 DepthRange 变更
                // ==========================================
                if (dc.vpX != lastVpX || dc.vpY != lastVpY || dc.vpW != lastVpW || dc.vpH != lastVpH || dc.depthRangeStart != lastDepthStart || dc.depthRangeEnd != lastDepthEnd) {
                    int vx = (dc.vpW > 0 && dc.vpH > 0) ? dc.vpX : 0;
                    int vy = (dc.vpW > 0 && dc.vpH > 0) ? dc.vpY : 0;
                    int vw = (dc.vpW > 0 && dc.vpH > 0) ? dc.vpW : fbW;
                    int vh = (dc.vpW > 0 && dc.vpH > 0) ? dc.vpH : fbH;

                    if (vx < 0) { vw += vx; vx = 0; }
                    if (vy < 0) { vh += vy; vy = 0; }
                    if (vx > fbW) vx = fbW;
                    if (vy > fbH) vy = fbH;
                    if (vx + vw > fbW) vw = fbW - vx;
                    if (vy + vh > fbH) vh = fbH - vy;
                    if (vw < 1) vw = 1;
                    if (vh < 1) vh = 1;

                    // 翻转 Y 轴，兼容 OpenGL 坐标系
                    float vkVpX = (float) vx;
                    float vkVpY = (float) (fbH - vy);
                    float vkVpW = (float) vw;
                    float vkVpH = (float) (-vh);

                    VkViewport.Buffer vp = VkViewport.calloc(1, frameStack)
                            .x(vkVpX).y(vkVpY).width(vkVpW).height(vkVpH)
                            .minDepth(dc.depthRangeStart).maxDepth(dc.depthRangeEnd);
                    
                    vkCmdSetViewport(cmd, 0, vp);

                    lastVpX = dc.vpX; lastVpY = dc.vpY; lastVpW = dc.vpW; lastVpH = dc.vpH;
                    lastDepthStart = dc.depthRangeStart; lastDepthEnd = dc.depthRangeEnd;
                }

                // ==========================================
                // 动态执行 Scissor (裁剪区) 变更
                // ==========================================
                int sx, sy, sw, sh;
                if (dc.clipEnabled) {
                    sx = dc.clipX;
                    sw = dc.clipW;
                    sh = dc.clipH;
                    sy = fbH - dc.clipY - sh; // OpenGL 原点翻转
                } else {
                    int vx = (dc.vpW > 0 && dc.vpH > 0) ? dc.vpX : 0;
                    int vy = (dc.vpW > 0 && dc.vpH > 0) ? dc.vpY : 0;
                    int vw = (dc.vpW > 0 && dc.vpH > 0) ? dc.vpW : fbW;
                    int vh = (dc.vpW > 0 && dc.vpH > 0) ? dc.vpH : fbH;
                    sx = vx; sy = vy; sw = vw; sh = vh;
                }

                if (sx < 0) { sw += sx; sx = 0; }
                if (sy < 0) { sh += sy; sy = 0; }
                if (sx > fbW) sx = fbW;
                if (sy > fbH) sy = fbH;
                if (sx + sw > fbW) sw = fbW - sx;
                if (sy + sh > fbH) sh = fbH - sy;
                if (sw < 0) sw = 0;
                if (sh < 0) sh = 0;

                if (sx != lastScX || sy != lastScY || sw != lastScW || sh != lastScH) {
                    VkRect2D.Buffer sc = VkRect2D.calloc(1, frameStack);
                    sc.offset().set(sx, sy);
                    sc.extent().set(sw, sh);
                    
                    vkCmdSetScissor(cmd, 0, sc);

                    lastScX = sx; lastScY = sy; lastScW = sw; lastScH = sh;
                }

                if (lastPipelineKey == null || !lastPipelineKey.equals(dc.pipelineKey)) {
                    VulkanPipeline pipeline = frame.runtime.getOrCreatePipeline(dc.pipelineKey, dc.finalVertSrc, dc.finalFragSrc);
                    if (pipeline == null || pipeline.getGraphicsPipeline() == 0L) continue;

                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getGraphicsPipeline());
                    lastPipeline = pipeline;
                    lastPipelineKey = dc.pipelineKey;
                    resetLastBoundSetsForPipeline(pipeline.getDescriptorSetLayoutCount());
                }

                VkUboLayout drawLayout = frame.runtime.getPipelineUboLayout(dc.pipelineKey);
                int dynOff;
                try {
                    dynOff = frame.descriptors.allocPerDraw(frameIndex);
                    dc.uboDynamicOffset = dynOff;
                } catch (IllegalStateException e) {
                    break;
                }

                frame.descriptors.writePerDraw(frameIndex, dynOff, dc.uboData, frame.width, frame.height, timeSeconds, drawLayout);

                int pcSize = frame.runtime.getPipelinePushConstantSize(dc.pipelineKey);
                if (pcSize > 0) {
                    ByteBuffer pcBuffer = frameStack.malloc(pcSize);
                    pcBuffer.putFloat(0, timeSeconds);
                    if (pcSize >= 8) pcBuffer.putFloat(4, (float) frame.width);
                    if (pcSize >= 12) pcBuffer.putFloat(8, (float) frame.height);
                    if (pcSize >= 16) pcBuffer.putFloat(12, 1.0f);
                    
                    vkCmdPushConstants(cmd, lastPipeline.getPipelineLayout(), VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pcBuffer);
                }

                DescriptorBindRequest bindReq = DescriptorBindRequest.acquire();
                try {
                    bindReq.setup(frameIndex, lastPipeline, dc, 0L, dc.uboDynamicOffset);
                    bindReq.perDrawUboHandle = frame.descriptors.getUbo().handle;
                    bindReq.perDrawUboRange = drawLayout.sliceSize;

                    DescriptorBindResult bindRes = descriptorSetBinder.bindForDraw(bindReq);

                    if (bindRes.setIndices == null || bindRes.setIndices.length == 0) {
                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, lastPipeline.getPipelineLayout(), bindRes.firstSet,
                                frameStack.longs(bindRes.sets), frameStack.ints(bindRes.dynamicOffsets));
                    } else {
                        bindSparseDescriptorSets(cmd, frameStack, lastPipeline.getPipelineLayout(), bindRes, lastPipeline.getDescriptorSetLayoutCount());
                    }

                    drawMesh(cmd, frameStack, frame, dc);
                } finally {
                    bindReq.recycle();
                }
            }
        }
    }

    private boolean drawMesh(VkCommandBuffer cmd, MemoryStack stack, VulkanFrameInfo frame, DrawCmd dc) {
        VkMeshGpu gpu = frame.runtime.getOrCreateMeshGpu(dc.mesh);
        if (gpu == null) return false;

        int mask = dc.pipelineKey.vertexLayoutMask;
        int attrCount = 0;
        for (int i = 0; i < VkPipelineKey.VERTEX_TYPES.length; i++) {
            if (((mask >> (i * 4)) & 0xF) > 0) attrCount++;
        }

        if (attrCount > 0) {
            LongBuffer handles = stack.mallocLong(attrCount);
            LongBuffer offsets = stack.mallocLong(attrCount);
            int bufferIndex = 0;
            for (int i = 0; i < VkPipelineKey.VERTEX_TYPES.length; i++) {
                if (((mask >> (i * 4)) & 0xF) > 0) {
                    VkBuffer buf = gpu.vbos.get(VkPipelineKey.VERTEX_TYPES[i]);
                    if (buf == null) return false; 
                    handles.put(bufferIndex, buf.handle);
                    offsets.put(bufferIndex, 0L);
                    bufferIndex++;
                }
            }
            vkCmdBindVertexBuffers(cmd, 0, handles, offsets);
        }

        if (gpu.ibo != null) {
            if (gpu.indexCount <= 0) return false;
            vkCmdBindIndexBuffer(cmd, gpu.ibo.handle, 0, gpu.vkIndexType);
            vkCmdDrawIndexed(cmd, gpu.indexCount, 1, 0, 0, 0);
        } else {
            if (gpu.vertexCount <= 0) return false;
            vkCmdDraw(cmd, gpu.vertexCount, 1, 0, 0);
        }
        return true;
    }

    private void resetLastBoundSetsForPipeline(int setCount) {
        if (setCount < 0) setCount = 0;
        lastBoundSets = new long[setCount];
        Arrays.fill(lastBoundSets, 0L);
    }

    private void bindSparseDescriptorSets(VkCommandBuffer cmd, MemoryStack stack, long pipelineLayout, DescriptorBindResult bindRes, int pipelineSetCount) {
        if (bindRes == null || bindRes.sets == null || bindRes.sets.length == 0) return;
        int[] setIndices = bindRes.setIndices;
        long[] sets = bindRes.sets;
        int[] dynOffsets = (bindRes.dynamicOffsets != null) ? bindRes.dynamicOffsets : new int[0];

        if (setIndices == null || setIndices.length == 0) {
            LongBuffer pSets = stack.mallocLong(sets.length);
            for (int i = 0; i < sets.length; i++) pSets.put(i, sets[i]);
            pSets.flip();

            IntBuffer pDyn = null;
            if (dynOffsets.length > 0) {
                pDyn = stack.mallocInt(dynOffsets.length);
                for (int v : dynOffsets) pDyn.put(v);
                pDyn.flip();
            }
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, bindRes.firstSet, pSets, pDyn);
            return;
        }

        int[] dynCountBySet = buildDynamicCountBySet(bindRes, pipelineSetCount);
        int dynCursor = 0, i = 0;

        while (i < setIndices.length) {
            int winStart = i;
            int firstSet = setIndices[i];
            int j = i + 1;
            while (j < setIndices.length && setIndices[j] == setIndices[j - 1] + 1) j++;

            int windowCount = j - winStart;
            int dynCountWindow = 0;
            for (int k = winStart; k < j; k++) dynCountWindow += dynCountBySet[setIndices[k]];

            LongBuffer pSets = stack.mallocLong(windowCount);
            for (int k = 0; k < windowCount; k++) pSets.put(sets[winStart + k]);
            pSets.flip();

            IntBuffer pDyn = null;
            if (dynCountWindow > 0) {
                pDyn = stack.mallocInt(dynCountWindow);
                for (int d = 0; d < dynCountWindow; d++) pDyn.put(dynOffsets[dynCursor + d]);
                pDyn.flip();
            }

            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, firstSet, pSets, pDyn);
            dynCursor += dynCountWindow;
            i = j;
        }
    }

    private static int[] buildDynamicCountBySet(DescriptorBindResult bindRes, int pipelineSetCount) {
        int[] dynCountBySet = new int[pipelineSetCount];
        int[] dynSets = bindRes.dynamicOffsetSetIndices;
        if (dynSets == null || dynSets.length == 0) return dynCountBySet;
        for (int s : dynSets) if (s >= 0 && s < pipelineSetCount) dynCountBySet[s]++;
        return dynCountBySet;
    }
}