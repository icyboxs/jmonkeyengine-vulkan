package com.jme3.renderer.vulkan.queue;

import com.jme3.renderer.vulkan.cmd.CopyCmd;
import com.jme3.renderer.vulkan.cmd.DrawCmd;
import com.jme3.renderer.vulkan.cmd.MaterialBatchKey;
import com.jme3.renderer.vulkan.frame.VulkanFrameInfo;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.texture.Texture;

import java.util.ArrayList;

public final class DrawQueue {

    private static final int MAX_QUEUED_DRAWS = 200_000;

    private final ArrayList<DrawCmd> opaqueList = new ArrayList<>(8192);
    private final ArrayList<DrawCmd> guiList = new ArrayList<>(2048);
    private final ArrayList<CopyCmd> copyList = new ArrayList<>(32);

    public void enqueueCopy(CopyCmd cmd) {
        if (cmd != null && copyList.size() < 64) {
            copyList.add(cmd);
        }
    }

    public java.util.List<CopyCmd> getCopyCommands() {
        return copyList;
    }

    public boolean isFull() {
        return (opaqueList.size() + guiList.size()) >= MAX_QUEUED_DRAWS;
    }

    public void enqueue(DrawCmd dc) {
        if (dc != null && !isFull()) {
            boolean isOpaque = dc.pipelineKey.depthTest && dc.pipelineKey.depthWrite && dc.pipelineKey.blend == VkPipelineKey.Blend.Off;

            if (isOpaque) {
                dc.submissionIndex = opaqueList.size();
                opaqueList.add(dc);
            } else {
                dc.submissionIndex = guiList.size();
                guiList.add(dc);
            }
        }
    }

    public void clear() {
        // 【核心】：对象回收
        for (int i = 0; i < opaqueList.size(); i++) {
            opaqueList.get(i).recycle();
        }
        for (int i = 0; i < guiList.size(); i++) {
            guiList.get(i).recycle();
        }

        for (int i = 0; i < copyList.size(); i++) {
            copyList.get(i).recycle();
        }
        copyList.clear();
        opaqueList.clear();
        guiList.clear();
    }

    public java.util.List<DrawCmd> getOpaqueCommands() {
        return opaqueList;
    }

    public java.util.List<DrawCmd> getGuiCommands() {
        return guiList;
    }

    public void precomputeAndSort(VulkanFrameInfo frame) {
        precomputeList(frame, opaqueList);
        precomputeList(frame, guiList);

        // 【极速排序】：直接按 64位长整型基数排序
        opaqueList.sort((a, b) -> Long.compare(a.sortKey, b.sortKey));
    }

    private void precomputeList(VulkanFrameInfo frame, ArrayList<DrawCmd> list) {
        final int size = list.size();
        for (int i = 0; i < size; i++) {
            DrawCmd dc = list.get(i);
            if (!dc.isRenderable()) {
                continue;
            }

            long[] views = new long[dc.customImageCount];
            long[] samplers = new long[dc.customImageCount];

            for (int j = 0; j < dc.customImageCount; j++) {
                Texture jmeTex = dc.customImageTextures[j];
                com.jme3.renderer.vulkan.resource.VkTexture vkTex = frame.runtime.getOrCreateVkTexture(jmeTex);
                long sampler = frame.runtime.getOrCreateSampler(jmeTex != null ? jmeTex : frame.runtime.getOffscreenJmeTex());
                if (vkTex == null) {
                    vkTex = frame.runtime.getOrCreateVkTexture(null);
                    if (vkTex != null) {
                        sampler = vkTex.sampler;
                    }
                }
                views[j] = (vkTex != null) ? vkTex.view : 0L;
                samplers[j] = sampler;
            }

            dc.materialBatchKey = new MaterialBatchKey(views, samplers, dc.variant);

            // 构建 64-bit 排序键：高32位(管道Hash) | 低32位(材质Hash)
            long pHash = (long) dc.pipelineKey.hashCode() & 0xFFFFFFFFL;
            long mHash = (long) dc.materialBatchKey.hashCode() & 0xFFFFFFFFL;
            dc.sortKey = (pHash << 32) | mHash;
        }
    }
}
