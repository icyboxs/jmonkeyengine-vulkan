#import "Common/ShaderLib/VKGLSLCompat.glsllib"

// ---- inputs from vertex shader ----
#if defined(NEED_TEXCOORD1)
layout(location = 0) in vec2 texCoord1;
#else
layout(location = 0) in vec2 texCoord;
#endif

// ---- output ----
layout(location = 0) out vec4 outFragColor;

// ---- material params (non-opaque uniforms must be in UBO) ----
layout(set = 2, binding = 0) uniform GlowParams {
#ifdef HAS_GLOWCOLOR
    vec4 m_GlowColor;
#endif
};

// ---- sampler ----
#ifdef HAS_GLOWMAP
layout(set = 2, binding = 1) uniform sampler2D m_GlowMap;
#endif

void main() {
#ifdef HAS_GLOWMAP
    #ifdef HAS_GLOWCOLOR
        vec4 color = m_GlowColor;
    #else
        vec4 color = vec4(1.0);
    #endif

    #if defined(NEED_TEXCOORD1)
        outFragColor = texture(m_GlowMap, texCoord1) * color;
    #else
        outFragColor = texture(m_GlowMap, texCoord) * color;
    #endif
#else
    #ifdef HAS_GLOWCOLOR
        outFragColor = m_GlowColor;
    #else
        outFragColor = vec4(0.0);
    #endif
#endif
}
