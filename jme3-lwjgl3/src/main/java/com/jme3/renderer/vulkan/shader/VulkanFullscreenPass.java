package com.jme3.renderer.vulkan.shader;

import com.jme3.renderer.vulkan.resource.VkTexture;
import com.jme3.renderer.vulkan.shader.VulkanShaders;
import com.jme3.renderer.vulkan.context.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.VK10.*;

public final class VulkanFullscreenPass {

    private final VkContext vk;
    private final VulkanShaders shaders;

    private long setLayout;
    private long pipelineLayout;
    private long pipeline;

    private long pool;
    private long set;

    // cache key: (view,sampler)
    private long lastView = 0L;
    private long lastSampler = 0L;

    // stats hooks (optional)
    public long setWriteCount = 0;

    /**
     * 当前渲染链里 scene pass 使用了负高度 viewport，因此 fullscreen 采样时需要翻转 Y。
     * 如果以后你统一了坐标系约定，可以改成 false。
     */
    private static final boolean FLIP_OFFSCREEN_Y = false;

    public VulkanFullscreenPass(VkContext vk, VulkanShaders shaders) {
        this.vk = vk;
        this.shaders = shaders;
    }

    public void initIfNeeded(int swapchainColorFormat) {
        if (pipeline != 0) {
            return;
        }

        setLayout = createSetLayout();
        pipelineLayout = createPipelineLayout(setLayout);
        pool = createPool();
        set = allocSet(pool, setLayout);

        pipeline = createPipeline(swapchainColorFormat, pipelineLayout);
    }

    public long pipeline() {
        return pipeline;
    }

    public long pipelineLayout() {
        return pipelineLayout;
    }

    public long descriptorSet() {
        return set;
    }

    public void updateDescriptorIfNeeded(VkTexture srcColor, long sampler) {
        long view = (srcColor != null) ? srcColor.view : 0L;
        if (view == 0L || sampler == 0L) {
            return;
        }
        if (view == lastView && sampler == lastSampler) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler)
                    .imageView(view)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(1, stack);
            wr.get(0)
                    .sType$Default()
                    .dstSet(set)
                    .dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(ii);

            vkUpdateDescriptorSets(vk.device(), wr, null);
            setWriteCount++;

            lastView = view;
            lastSampler = sampler;
        }
    }

    public void destroy() {
        if (vk == null || vk.device() == null) {
            return;
        }

        if (pipeline != 0) {
            vkDestroyPipeline(vk.device(), pipeline, null);
            pipeline = 0;
        }
        if (pipelineLayout != 0) {
            vkDestroyPipelineLayout(vk.device(), pipelineLayout, null);
            pipelineLayout = 0;
        }
        if (setLayout != 0) {
            vkDestroyDescriptorSetLayout(vk.device(), setLayout, null);
            setLayout = 0;
        }
        if (pool != 0) {
            vkDestroyDescriptorPool(vk.device(), pool, null);
            pool = 0;
        }

        set = 0;
        lastView = 0L;
        lastSampler = 0L;
    }

    // ---------------- internal ----------------

    private long createSetLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, stack);
            b.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(b);

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreateDescriptorSetLayout(vk.device(), ci, null, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorSetLayout(fs) failed: " + err);
            }
            return p.get(0);
        }
    }

    private long createPipelineLayout(long setLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(setLayout));

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreatePipelineLayout(vk.device(), ci, null, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreatePipelineLayout(fs) failed: " + err);
            }
            return p.get(0);
        }
    }

    private long createPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1);

            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(1)
                    .pPoolSizes(sizes);

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreateDescriptorPool(vk.device(), ci, null, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateDescriptorPool(fs) failed: " + err);
            }
            return p.get(0);
        }
    }

    private long allocSet(long pool, long setLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(setLayout));

            LongBuffer p = stack.mallocLong(1);
            int err = vkAllocateDescriptorSets(vk.device(), ai, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkAllocateDescriptorSets(fs) failed: " + err);
            }
            return p.get(0);
        }
    }

    private long createPipeline(int swapchainColorFormat, long pipelineLayout) {
        // fullscreen triangle vertex shader
        final String vert = ""
                + "#version 450\n"
                + "layout(location=0) out vec2 vUV;\n"
                + "vec2 positions[3] = vec2[](\n"
                + "    vec2(-1.0, -1.0),\n"
                + "    vec2( 3.0, -1.0),\n"
                + "    vec2(-1.0,  3.0)\n"
                + ");\n"
                + "void main(){\n"
                + "  vec2 p = positions[gl_VertexIndex];\n"
                + "  gl_Position = vec4(p, 0.0, 1.0);\n"
                + "  vUV = p * 0.5 + 0.5;\n"
                + "}\n";

        // fullscreen fragment shader
        final String frag = ""
                + "#version 450\n"
                + "layout(set=0, binding=0) uniform sampler2D uTex;\n"
                + "layout(location=0) in vec2 vUV;\n"
                + "layout(location=0) out vec4 outColor;\n"
                + "void main(){\n"
                + (FLIP_OFFSCREEN_Y
                        ? "  vec2 uv = vec2(vUV.x, 1.0 - vUV.y);\n"
                        : "  vec2 uv = vUV;\n")
                + "  outColor = texture(uTex, uv);\n"
                + "}\n";

        long vertMod = shaders.getOrCreateModuleFromRawGlsl(vert, VK_SHADER_STAGE_VERTEX_BIT, "fs-vert");
        long fragMod = shaders.getOrCreateModuleFromRawGlsl(frag, VK_SHADER_STAGE_FRAGMENT_BIT, "fs-frag");

        try (MemoryStack stack = MemoryStack.stackPush()) {

            VkPipelineRenderingCreateInfoKHR renderingCI = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                    .colorAttachmentCount(1)
                    .pColorAttachmentFormats(stack.ints(swapchainColorFormat))
                    .depthAttachmentFormat(VK_FORMAT_UNDEFINED)
                    .stencilAttachmentFormat(VK_FORMAT_UNDEFINED);

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default()
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertMod)
                    .pName(stack.UTF8("main"));
            stages.get(1).sType$Default()
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragMod)
                    .pName(stack.UTF8("main"));

            // fullscreen triangle 不需要 vertex buffer
            VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType$Default();

            VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);

            VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(false)
                    .depthWriteEnable(false)
                    .depthCompareOp(VK_COMPARE_OP_ALWAYS);

            VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            cba.get(0)
                    .colorWriteMask(0xF)
                    .blendEnable(false);

            VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(cba);

            VkPipelineDynamicStateCreateInfo dy = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(
                            VK_DYNAMIC_STATE_VIEWPORT,
                            VK_DYNAMIC_STATE_SCISSOR
                    ));

            VkGraphicsPipelineCreateInfo.Buffer pCI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .pNext(renderingCI.address())
                    .pStages(stages)
                    .pVertexInputState(vi)
                    .pInputAssemblyState(ia)
                    .pViewportState(vp)
                    .pRasterizationState(rs)
                    .pMultisampleState(ms)
                    .pDepthStencilState(ds)
                    .pColorBlendState(cb)
                    .pDynamicState(dy)
                    .layout(pipelineLayout)
                    .renderPass(VK_NULL_HANDLE)
                    .subpass(0);

            LongBuffer p = stack.mallocLong(1);
            int err = vkCreateGraphicsPipelines(vk.device(), VK_NULL_HANDLE, pCI, null, p);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("vkCreateGraphicsPipelines(fs) failed: " + err);
            }
            return p.get(0);
        }
    }
}
