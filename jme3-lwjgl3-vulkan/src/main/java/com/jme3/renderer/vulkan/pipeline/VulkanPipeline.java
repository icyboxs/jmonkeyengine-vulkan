package com.jme3.renderer.vulkan.pipeline;

import com.jme3.renderer.vulkan.shader.VulkanShaders;
import com.jme3.renderer.vulkan.context.VkContext;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.LongBuffer;
import java.util.logging.Logger;

import static org.lwjgl.vulkan.VK10.*;

public final class VulkanPipeline {

    private static final Logger LOGGER = Logger.getLogger(VulkanPipeline.class.getName());

    private final VkContext vk;
    private final VulkanShaders shaders;
    private final VkPipelineKey key;

    private long pipelineLayout;
    private long graphicsPipeline;
    private long computePipeline;

    private final String vertGlsl;
    private final String fragGlsl;
    private final String compGlsl;

    private final long[] descriptorSetLayouts;

    public VulkanPipeline(VkContext vk, VulkanShaders shaders, long[] descriptorSetLayouts, VkPipelineKey key, long sharedPipelineLayout, String vertGlsl, String fragGlsl) {
        this.vk = vk;
        this.shaders = shaders;
        this.key = key;
        this.pipelineLayout = sharedPipelineLayout;
        this.vertGlsl = vertGlsl;
        this.fragGlsl = fragGlsl;
        this.compGlsl = null;
        this.descriptorSetLayouts = (descriptorSetLayouts != null && descriptorSetLayouts.length > 0) ? descriptorSetLayouts.clone() : new long[0];
    }

    public VulkanPipeline(VkContext vk, VulkanShaders shaders, long[] descriptorSetLayouts, long sharedPipelineLayout, String compGlsl) {
        this.vk = vk;
        this.shaders = shaders;
        this.key = null;
        this.pipelineLayout = sharedPipelineLayout;
        this.vertGlsl = null;
        this.fragGlsl = null;
        this.compGlsl = compGlsl;
        this.descriptorSetLayouts = (descriptorSetLayouts != null && descriptorSetLayouts.length > 0) ? descriptorSetLayouts.clone() : new long[0];
    }

    public void init() throws IOException {
        if (pipelineLayout == 0L) {
            pipelineLayout = createPipelineLayout();
        }
        if (vertGlsl != null && fragGlsl != null) {
            graphicsPipeline = createGraphicsPipeline();
        }
    }

    public void initCompute() throws IOException {
        if (pipelineLayout == 0L) {
            pipelineLayout = createPipelineLayout();
        }
        if (compGlsl != null) {
            computePipeline = createComputePipeline();
        }
    }

    public long getPipelineLayout() {
        return pipelineLayout;
    }

    public long getGraphicsPipeline() {
        return graphicsPipeline;
    }

    public long getComputePipeline() {
        return computePipeline;
    }

    public void destroy(boolean destroyShared) {
        if (graphicsPipeline != 0L) {
            vkDestroyPipeline(vk.device(), graphicsPipeline, null);
            graphicsPipeline = 0L;
        }
        if (computePipeline != 0L) {
            vkDestroyPipeline(vk.device(), computePipeline, null);
            computePipeline = 0L;
        }
        if (destroyShared && pipelineLayout != 0L) {
            vkDestroyPipelineLayout(vk.device(), pipelineLayout, null);
            pipelineLayout = 0L;
        }
    }

    private long createPipelineLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer setLayoutsBuf = stack.mallocLong(descriptorSetLayouts.length);
            for (long l : descriptorSetLayouts) {
                setLayoutsBuf.put(l);
            }
            setLayoutsBuf.flip();

            VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(setLayoutsBuf);

            LongBuffer p = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vk.device(), ci, null, p) != VK_SUCCESS) {
                throw new RuntimeException("vkCreatePipelineLayout failed");
            }
            return p.get(0);
        }
    }

    private long createComputePipeline() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long compMod = shaders.getOrCreateModuleFromRawGlsl(compGlsl, VK_SHADER_STAGE_COMPUTE_BIT, "pm-comp");

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(1, stack);
            stages.get(0).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(compMod)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pCI = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                    .stage(stages.get(0))
                    .layout(pipelineLayout);

            LongBuffer p = stack.mallocLong(1);
            if (vkCreateComputePipelines(vk.device(), VK_NULL_HANDLE, pCI, null, p) != VK_SUCCESS) {
                throw new RuntimeException("vkCreateComputePipelines failed");
            }
            return p.get(0);
        }
    }

    private long createGraphicsPipeline() throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (vertGlsl == null || fragGlsl == null) {
                throw new IllegalStateException("Pipeline missing GLSL sources");
            }
            if (key == null || key.passKey == null) {
                throw new IllegalStateException("PipelineKey/passKey is null");
            }

            org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR renderingCI = org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR.calloc(stack)
                    .sType(org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                    .colorAttachmentCount(key.passKey.colorCount)
                    .pColorAttachmentFormats(stack.ints(key.passKey.colorFormat))
                    .depthAttachmentFormat(key.passKey.depthFormat)
                    .stencilAttachmentFormat(VK_FORMAT_UNDEFINED);

            long vert = shaders.getOrCreateModuleFromRawGlsl(vertGlsl, VK_SHADER_STAGE_VERTEX_BIT, "vert");
            long frag = shaders.getOrCreateModuleFromRawGlsl(fragGlsl, VK_SHADER_STAGE_FRAGMENT_BIT, "frag");

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();

            int mask = key.vertexLayoutMask;
            int attrCount = 0;
            for (int i = 0; i < VkPipelineKey.VERTEX_TYPES.length; i++) {
                if (((mask >> (i * 4)) & 0xF) > 0) {
                    attrCount++;
                }
            }

            if (attrCount > 0) {
                VkVertexInputBindingDescription.Buffer bind = VkVertexInputBindingDescription.calloc(attrCount, stack);
                VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(attrCount, stack);

                int bufferIndex = 0;
                for (int i = 0; i < VkPipelineKey.VERTEX_TYPES.length; i++) {
                    int components = (mask >> (i * 4)) & 0xF;
                    if (components > 0) {
                        int stride = components * 4;

                        int format;
                        switch (components) {
                            case 1:
                                format = VK_FORMAT_R32_SFLOAT;
                                break;
                            case 2:
                                format = VK_FORMAT_R32G32_SFLOAT;
                                break;
                            case 3:
                                format = VK_FORMAT_R32G32B32_SFLOAT;
                                break;
                            default:
                                format = VK_FORMAT_R32G32B32A32_SFLOAT;
                                break;
                        }

                        bind.get(bufferIndex).binding(bufferIndex).stride(stride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                        attr.get(bufferIndex).location(i).binding(bufferIndex).format(format).offset(0);
                        bufferIndex++;
                    }
                }
                vi.pVertexBindingDescriptions(bind).pVertexAttributeDescriptions(attr);
            }

            VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default().viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default().lineWidth(1.0f).cullMode(key.cullMode).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);

            VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(key.passKey.samples)
                    .alphaToCoverageEnable(key.alphaToCoverage);

            boolean isTransparent = (key.blend != VkPipelineKey.Blend.Off);
            boolean finalDepthTest = isTransparent ? false : key.depthTest;
            boolean finalDepthWrite = isTransparent ? false : key.depthWrite;

            VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(finalDepthTest)
                    .depthWriteEnable(finalDepthWrite)
                    .depthCompareOp(key.depthCompareOp);

            VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            cba.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

            if (key.blend == VkPipelineKey.Blend.Off) {
                cba.get(0).blendEnable(false);
            } else if (key.blend == VkPipelineKey.Blend.Alpha) {
                cba.get(0).blendEnable(true)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD);
            } else if (key.blend == VkPipelineKey.Blend.Additive) {
                cba.get(0).blendEnable(true).srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE)
                        .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE).alphaBlendOp(VK_BLEND_OP_ADD);
            }

            VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(cba);

            VkPipelineDynamicStateCreateInfo dy = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            VkGraphicsPipelineCreateInfo.Buffer pCI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default().pNext(renderingCI.address()).pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia)
                    .pViewportState(vp).pRasterizationState(rs).pMultisampleState(ms).pDepthStencilState(ds).pColorBlendState(cb).pDynamicState(dy)
                    .layout(pipelineLayout).renderPass(VK_NULL_HANDLE).subpass(0);

            LongBuffer pP = stack.mallocLong(1);
            if (vkCreateGraphicsPipelines(vk.device(), VK_NULL_HANDLE, pCI, null, pP) != VK_SUCCESS) {
                throw new RuntimeException("vkCreateGraphicsPipelines failed");
            }
            return pP.get(0);
        }
    }

    public int getDescriptorSetLayoutCount() {
        return descriptorSetLayouts != null ? descriptorSetLayouts.length : 0;
    }

    public long getDescriptorSetLayoutAt(int setIndex) {
        if (descriptorSetLayouts == null || setIndex < 0 || setIndex >= descriptorSetLayouts.length) {
            return 0L;
        }
        return descriptorSetLayouts[setIndex];
    }
}
