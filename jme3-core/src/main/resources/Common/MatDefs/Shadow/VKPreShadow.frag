#import "Common/ShaderLib/VKGLSLCompat.glsllib"

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 outFragColor;

#ifdef DISCARD_ALPHA
layout(set = 2, binding = 0) uniform AlphaParams {
    float m_AlphaDiscardThreshold;
};

    #ifdef COLOR_MAP
    layout(set = 2, binding = 1) uniform sampler2D m_ColorMap;
    #else
    layout(set = 2, binding = 1) uniform sampler2D m_DiffuseMap;
    #endif
#endif

void main() {
#ifdef DISCARD_ALPHA
    #ifdef COLOR_MAP
        if (texture(m_ColorMap, texCoord).a <= m_AlphaDiscardThreshold) {
            discard;
        }
    #else
        if (texture(m_DiffuseMap, texCoord).a <= m_AlphaDiscardThreshold) {
            discard;
        }
    #endif
#endif

    outFragColor = vec4(1.0);
}
