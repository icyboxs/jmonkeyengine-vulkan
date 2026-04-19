package com.jme3.renderer.vulkan.queue;

import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.cmd.MaterialBatchKey;
import com.jme3.renderer.vulkan.cmd.MaterialResolvePlan;
import com.jme3.renderer.vulkan.frame.VulkanFrameInfo;
import com.jme3.renderer.vulkan.pipeline.PassKey;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.renderer.vulkan.pipeline.VkShaderKey;
import com.jme3.renderer.vulkan.pipeline.VkVariantKey;
import com.jme3.renderer.vulkan.resource.VkTexture;
import com.jme3.texture.Texture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 负责收集、缓存并排序一帧内的绘制命令 (DrawCmd)。
 */
public final class DrawQueue {

    // 最小化/阻塞时防止 drawList 无限增长导致 OOM
    private static final int MAX_QUEUED_DRAWS = 200_000;

    private final ArrayList<DrawCmd> drawList = new ArrayList<>();

    // 【核心优化】：使用静态单例的具名比较器，消灭 Lambda Metafactory 开销
    private static final BatchComparator BATCH_COMPARATOR = new BatchComparator();

    public boolean isFull() {
        return drawList.size() >= MAX_QUEUED_DRAWS;
    }

    public void enqueue(DrawCmd dc) {
        if (dc != null && !isFull()) {
            drawList.add(dc);
        }
    }

    public void clear() {
        drawList.clear();
    }

    public List<DrawCmd> getCommands() {
        return drawList;
    }

    /**
     * 预计算 MaterialBatchKey 并按照 Pipeline -> Material -> Variant 排序
     */
    public void precomputeAndSort(VulkanFrameInfo frame) {
        final int size = drawList.size();
        for (int i = 0; i < size; i++) {
            DrawCmd dc = drawList.get(i);
            
            if (!dc.isRenderable()) {
                continue;
            }

            MaterialResolvePlan mrp = dc.materialResolvePlan;
            if (mrp == null) {
                continue;
            }

            Texture jmeTex0 = dc.useWhiteTex0 ? null : mrp.tex0;
            Texture jmeLight = dc.useWhiteLight ? null : mrp.light;

            VkTexture vkTex0 = frame.runtime.getOrCreateVkTexture(jmeTex0);
            VkTexture vkLight = frame.runtime.getOrCreateVkTexture(jmeLight);

            long samp0 = frame.runtime.getOrCreateSampler(jmeTex0 != null ? jmeTex0 : frame.runtime.getOffscreenJmeTex());
            long sampL = frame.runtime.getOrCreateSampler(jmeLight);

            if (jmeTex0 == null) samp0 = frame.runtime.getOrCreateVkTexture(null).sampler;
            if (jmeLight == null) sampL = frame.runtime.getOrCreateVkTexture(null).sampler;

            dc.materialBatchKey = MaterialBatchKey.of(vkTex0, samp0, vkLight, sampL, dc.variant);
        }

        // 2) 稳定排序，传入静态单例 Comparator
        drawList.sort(BATCH_COMPARATOR);
    }


    private static final class BatchComparator implements Comparator<DrawCmd> {
        
        @Override
        public int compare(DrawCmd a, DrawCmd b) {
            int c = comparePipelineKeyStable(a.pipelineKey, b.pipelineKey);
            if (c != 0) return c;

            MaterialBatchKey ka = a.materialBatchKey;
            MaterialBatchKey kb = b.materialBatchKey;
            if (ka == kb) return 0;
            if (ka == null) return 1;
            if (kb == null) return -1;

            if (ka.tex0View != kb.tex0View) {
                return ka.tex0View < kb.tex0View ? -1 : 1;
            }
            if (ka.tex0Sampler != kb.tex0Sampler) {
                return ka.tex0Sampler < kb.tex0Sampler ? -1 : 1;
            }
            if (ka.lightView != kb.lightView) {
                return ka.lightView < kb.lightView ? -1 : 1;
            }
            if (ka.lightSampler != kb.lightSampler) {
                return ka.lightSampler < kb.lightSampler ? -1 : 1;
            }

            return compareVariantStable(ka.variant, kb.variant);
        }

        private int comparePipelineKeyStable(VkPipelineKey a, VkPipelineKey b) {
            if (a == b) return 0;
            if (a == null) return 1;
            if (b == null) return -1;

            int c = comparePassKeyStable(a.passKey, b.passKey);
            if (c != 0) return c;

            if (a.cullMode != b.cullMode) return a.cullMode < b.cullMode ? -1 : 1;
            if (a.depthTest != b.depthTest) return a.depthTest ? 1 : -1;
            if (a.depthWrite != b.depthWrite) return a.depthWrite ? 1 : -1;
            if (a.depthCompareOp != b.depthCompareOp) return a.depthCompareOp < b.depthCompareOp ? -1 : 1;

            int ab = (a.blend != null) ? a.blend.ordinal() : -1;
            int bb = (b.blend != null) ? b.blend.ordinal() : -1;
            if (ab != bb) return ab < bb ? -1 : 1;

            VkShaderKey sa = a.shaderKey;
            VkShaderKey sb = b.shaderKey;
            if (sa == sb) return 0;
            if (sa == null) return 1;
            if (sb == null) return -1;

            if (sa.vertHash != sb.vertHash) return sa.vertHash < sb.vertHash ? -1 : 1;
            if (sa.fragHash != sb.fragHash) return sa.fragHash < sb.fragHash ? -1 : 1;

            return compareVariantStable(sa.variant, sb.variant);
        }

        private int comparePassKeyStable(PassKey a, PassKey b) {
            if (a == b) return 0;
            if (a == null) return 1;
            if (b == null) return -1;

            if (a.colorCount != b.colorCount) return a.colorCount < b.colorCount ? -1 : 1;
            if (a.colorFormat != b.colorFormat) return a.colorFormat < b.colorFormat ? -1 : 1;
            if (a.depthFormat != b.depthFormat) return a.depthFormat < b.depthFormat ? -1 : 1;
            if (a.samples != b.samples) return a.samples < b.samples ? -1 : 1;

            return 0;
        }

        private int compareVariantStable(VkVariantKey a, VkVariantKey b) {
            if (a == b) return 0;
            if (a == null) return 1;
            if (b == null) return -1;

            if (a.hasColorMap != b.hasColorMap) return a.hasColorMap ? 1 : -1;
            if (a.hasColor != b.hasColor) return a.hasColor ? 1 : -1;
            if (a.hasLightMap != b.hasLightMap) return a.hasLightMap ? 1 : -1;

            return 0;
        }
    }
}