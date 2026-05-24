#import "VkCommon/ShaderLib/VKGLSLCompat.glsllib"
#version 450 core

// 1. 接收来自顶点着色器的数据 (Locations 必须严格与 vert 输出对齐)
layout(location = 0) in vec4 inColor;

#ifdef TEXTURE
    layout(location = 1) in vec2 inTexCoord;
    
    // 2. 纹理绑定：使用 binding = 1，避开被 UBO 占用的 binding = 0
    // 变量名 m_Texture 对应 j3md 中的 Texture2D Texture
    layout(set = 0, binding = 1) uniform sampler2D m_Texture;
#endif

// 3. 最终输出到屏幕的颜色通道
layout(location = 0) out vec4 outFragColor;

void main() {
    #ifdef TEXTURE
        // 从纹理采样
        vec4 texVal = texture(m_Texture, inTexCoord);
        outFragColor = texVal * inColor;
    #else
        // 纯色绘制
        outFragColor = inColor;
    #endif
}