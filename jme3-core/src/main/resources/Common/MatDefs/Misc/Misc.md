Project Path: Misc

Source Tree:

```txt
Misc
├── Billboard.j3md
├── ColoredTextured.frag
├── ColoredTextured.j3md
├── ColoredTextured.vert
├── DashedLine.j3md
├── Particle.frag
├── Particle.j3md
├── Particle.vert
├── ShowNormals.frag
├── ShowNormals.j3md
├── ShowNormals.vert
├── Sky.frag
├── Sky.j3md
├── Sky.vert
├── SkyNonCube.j3md
├── SoftParticle.frag
├── SoftParticle.vert
├── Unshaded.frag
├── Unshaded.j3md
├── Unshaded.vert
├── UnshadedNodes.j3md
├── fakeLighting.j3md
└── reflect.j3md

```

`Billboard.j3md`:

```j3md
MaterialDef Billboard {
    MaterialParameters {
        Float SpriteHeight : 10
        Texture2D Texture
    }

    Technique {
        WorldParameters {
            WorldViewMatrix
            ProjectionMatrix
            WorldMatrix
            CameraDirection
            ViewPort
            CameraPosition
        }

        VertexShaderNodes {
            ShaderNode TexCoord {
                Definition: AttributeToVarying: Common/MatDefs/ShaderNodes/Basic/AttributeToVarying.j3sn
                InputMappings {
                    vec2Variable = Attr.inTexCoord
                    vec4Variable = Attr.inColor
                }
                OutputMappings {
                }
            }
            ShaderNode FixedScale {
                Definition: FixedScale: Common/MatDefs/ShaderNodes/Common/FixedScale.j3sn
                InputMappings {
                    projectionMatrix = WorldParam.ProjectionMatrix
                    worldMatrix = WorldParam.WorldMatrix
                    cameraDir = WorldParam.CameraDirection
                    viewport = WorldParam.ViewPort
                    modelPosition = Attr.inPosition
                    cameraPos = WorldParam.CameraPosition
                    spriteHeight = MatParam.SpriteHeight
                }
                OutputMappings {
                }
            }
            ShaderNode Billboard {
                Definition: Billboard: Common/MatDefs/ShaderNodes/Common/Billboard.j3sn
                InputMappings {
                    worldViewMatrix = WorldParam.WorldViewMatrix
                    projectionMatrix = WorldParam.ProjectionMatrix
                    modelPosition = Attr.inPosition
                    scale = FixedScale.scale
                }
                OutputMappings {
                    Global.position = projPosition
                }
            }
        }

        FragmentShaderNodes {
            ShaderNode TextureFetch {
                Definition: TextureFetch: Common/MatDefs/ShaderNodes/Basic/TextureFetch.j3sn
                InputMappings {
                    textureMap = MatParam.Texture
                    texCoord = TexCoord.vec2Variable
                }
                OutputMappings {
                }
            }
            ShaderNode ColorMult {
                Definition: ColorMult: Common/MatDefs/ShaderNodes/Basic/ColorMult.j3sn
                InputMappings {
                    color1 = TextureFetch.outColor
                    color2 = TexCoord.vec4Variable
                }
                OutputMappings {
                    Global.color = outColor
                }
            }
        }

    }
}

```

`ColoredTextured.frag`:

```frag
varying vec2 texCoord;

uniform sampler2D m_ColorMap;
uniform vec4 m_Color;

void main(){
    vec4 texColor = texture2D(m_ColorMap, texCoord);
    gl_FragColor = vec4(mix(m_Color.rgb, texColor.rgb, texColor.a), 1.0);
}
```

`ColoredTextured.j3md`:

```j3md
Exception This material definition is deprecated. Please use Unshaded.j3md instead.
MaterialDef Colored Textured {

    MaterialParameters {
        Int BoundDrawBuffer
        Texture2D ColorMap
        Color Color (Color)
    }

    Technique {
        VertexShader   GLSL300 GLSL150 GLSL100:   Common/MatDefs/Misc/ColoredTextured.vert
        FragmentShader GLSL300 GLSL150 GLSL100: Common/MatDefs/Misc/ColoredTextured.frag

        WorldParameters {
            WorldViewProjectionMatrix
        }
        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
        }
    }

 
}

```

`ColoredTextured.vert`:

```vert
#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;

attribute vec3 inPosition;
attribute vec2 inTexCoord;

varying vec2 texCoord;

void main(){
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
    texCoord = inTexCoord;
}
```

`DashedLine.j3md`:

```j3md
MaterialDef DashedLine {
    MaterialParameters {
    }

    Technique {
        WorldParameters {
            WorldViewProjectionMatrix
            Resolution
        }

        VertexShaderNodes {
            ShaderNode TransformPosition {
                Definition: TransformPosition: Common/MatDefs/ShaderNodes/Basic/TransformPosition.j3sn
                InputMappings {
                    transformsMatrix = WorldParam.WorldViewProjectionMatrix
                    inputPosition = Attr.inNormal
                }
                OutputMappings {
                }
            }
            ShaderNode PerspectiveDivide {
                Definition: PerspectiveDivide: Common/MatDefs/ShaderNodes/Misc/PerspectiveDivide.j3sn
                InputMappings {
                    inVec = TransformPosition.outPosition
                }
                OutputMappings {
                }
            }
            ShaderNode CommonVert {
                Definition: CommonVert: Common/MatDefs/ShaderNodes/Common/CommonVert.j3sn
                InputMappings {
                    worldViewProjectionMatrix = WorldParam.WorldViewProjectionMatrix
                    modelPosition = Attr.inPosition
                    vertColor = Attr.inColor
                    texCoord1 = Attr.inTexCoord
                }
                OutputMappings {
                    Global.position = projPosition
                }
            }
        }

        FragmentShaderNodes {
            ShaderNode Dashed {
                Definition: Dashed: Common/MatDefs/ShaderNodes/Misc/Dashed.j3sn
                InputMappings {
                    texCoord = CommonVert.texCoord1
                    inColor = CommonVert.vertColor
                    resolution = WorldParam.Resolution
                    startPos = PerspectiveDivide.outVec
                }
                OutputMappings {
                    Global.color = outColor
                }
            }
        }

    }
}

```

`Particle.frag`:

```frag
#import "Common/ShaderLib/GLSLCompat.glsllib"
#ifdef POINT_SPRITE
#  if !defined(GL_ES) && __VERSION__ < 120
#    error Point sprite is not supported by the video hardware!
#  endif
#endif

#ifdef USE_TEXTURE
uniform sampler2D m_Texture;
varying vec4 texCoord;
#endif

varying vec4 color;

void main(){
    if (color.a <= 0.01)
        discard;

    #ifdef USE_TEXTURE
        #ifdef POINT_SPRITE
            vec2 uv = mix(texCoord.xy, texCoord.zw, gl_PointCoord.xy);
        #else
            vec2 uv = texCoord.xy;
        #endif
        gl_FragColor = texture2D(m_Texture, uv) * color;
    #else
        gl_FragColor = color;
    #endif

    #ifdef PRE_SHADOW
        if (gl_FragColor.r <= 0.1 && 
            gl_FragColor.g <= 0.1 &&
            gl_FragColor.b <= 0.1) {
            discard;
        }
    #endif
}
```

`Particle.j3md`:

```j3md
MaterialDef Point Sprite {

    MaterialParameters {
        Int BoundDrawBuffer
        Texture2D Texture
        Float Quadratic
        Boolean PointSprite
        
        //only used for soft particles
        Texture2D DepthTexture
        Float Softness
        Int NumSamplesDepth

        // Texture of the glowing parts of the material
        Texture2D GlowMap
        // The glow color of the object
        Color GlowColor
    }

    Technique {

        // The GLSL100 technique is used in two cases:
        // - When the driver doesn't support GLSL 1.2
        // - When running on OpenGL ES 2.0
        // Point sprite should be used if running on ES2, but crash
        // if on desktop (because its not supported by HW)

        VertexShader   GLSL300 GLSL150 GLSL120 GLSL100: Common/MatDefs/Misc/Particle.vert
        FragmentShader GLSL300 GLSL150 GLSL120 GLSL100: Common/MatDefs/Misc/Particle.frag

        WorldParameters {
            WorldViewProjectionMatrix
            WorldViewMatrix
            WorldMatrix
            CameraPosition
        }

        RenderState {
            Blend AlphaAdditive
            DepthWrite Off
            PointSprite On
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            USE_TEXTURE : Texture
            POINT_SPRITE : PointSprite
        }
    }

    Technique PreShadow {

        VertexShader   GLSL300 GLSL150 GLSL100:   Common/MatDefs/Shadow/PreShadow.vert
        FragmentShader GLSL300 GLSL150 GLSL100: Common/MatDefs/Shadow/PreShadow.frag

        WorldParameters {
            WorldViewProjectionMatrix
            WorldViewMatrix
            ViewProjectionMatrix
            ViewMatrix
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            COLOR_MAP : Texture
        }

        ForcedRenderState {
            FaceCull Off
            DepthTest On
            DepthWrite On
            PolyOffset 5 3
            ColorWrite Off
        }

    }

    Technique SoftParticles{

        VertexShader   GLSL300 GLSL150 GLSL100: Common/MatDefs/Misc/SoftParticle.vert
        FragmentShader GLSL300 GLSL150 GLSL100: Common/MatDefs/Misc/SoftParticle.frag

        WorldParameters {
            WorldViewProjectionMatrix
            WorldViewMatrix
            WorldMatrix
            CameraPosition
        }

        RenderState {
            Blend AlphaAdditive
            DepthWrite Off
            PointSprite On            
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            USE_TEXTURE : Texture
            POINT_SPRITE : PointSprite
            RESOLVE_DEPTH_MS : NumSamplesDepth
        }
    }

   Technique Glow {

        VertexShader   GLSL300 GLSL150 GLSL100:   Common/MatDefs/Misc/Unshaded.vert
        FragmentShader GLSL300 GLSL150 GLSL100:   Common/MatDefs/Light/Glow.frag

        WorldParameters {
            WorldViewProjectionMatrix
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            NEED_TEXCOORD1
            HAS_GLOWMAP : GlowMap
            HAS_GLOWCOLOR : GlowColor
        }

        RenderState {
            PointSprite On
            Blend AlphaAdditive
            DepthWrite Off
        }
    }
}
```

`Particle.vert`:

```vert
#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform mat4 g_WorldViewProjectionMatrix;

attribute vec3 inPosition;
attribute vec4 inColor;
attribute vec4 inTexCoord;

varying vec4 color;

#ifdef USE_TEXTURE
varying vec4 texCoord;
#endif

#ifdef POINT_SPRITE
uniform mat4 g_WorldViewMatrix;
uniform mat4 g_WorldMatrix;
uniform vec3 g_CameraPosition;
uniform float m_Quadratic;
const float SIZE_MULTIPLIER = 4.0;
attribute float inSize;
#endif

void main(){
    vec4 pos = vec4(inPosition, 1.0);

    gl_Position = g_WorldViewProjectionMatrix * pos;
    color = inColor;

    #ifdef USE_TEXTURE
        texCoord = inTexCoord;
    #endif

    #ifdef POINT_SPRITE
        vec4 worldPos = g_WorldMatrix * pos;
        float d = distance(g_CameraPosition.xyz, worldPos.xyz);
        float size = (inSize * SIZE_MULTIPLIER * m_Quadratic) / d;
        gl_PointSize = max(1.0, size);

        //vec4 worldViewPos = g_WorldViewMatrix * pos;
        //gl_PointSize = (inSize * SIZE_MULTIPLIER * m_Quadratic)*100.0 / worldViewPos.z;

        color.a *= min(size, 1.0);
    #endif
}

```

`ShowNormals.frag`:

```frag
#import "Common/ShaderLib/GLSLCompat.glsllib"
varying vec3 normal;

void main(){
   gl_FragColor = vec4((normal * vec3(0.5)) + vec3(0.5), 1.0);
}
```

`ShowNormals.j3md`:

```j3md
MaterialDef Debug Normals {
    MaterialParameters {
        Int BoundDrawBuffer
        // For instancing
        Boolean UseInstancing
    }

    Technique {
        VertexShader   GLSL300 GLSL150 GLSL100:   Common/MatDefs/Misc/ShowNormals.vert
        FragmentShader GLSL300 GLSL150 GLSL100: Common/MatDefs/Misc/ShowNormals.frag

        WorldParameters {
            WorldViewProjectionMatrix
            ViewProjectionMatrix
            ViewMatrix
            ProjectionMatrix
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            INSTANCING : UseInstancing
        }
    }
}
```

`ShowNormals.vert`:

```vert
#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"

attribute vec3 inPosition;
attribute vec3 inNormal;

varying vec3 normal;

void main(){
    gl_Position = TransformWorldViewProjection(vec4(inPosition,1.0));
    normal = inNormal;
}

```

`Sky.frag`:

```frag
#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Optics.glsllib"

uniform ENVMAP m_Texture;

varying vec3 direction;

void main() {
    vec3 dir = normalize(direction);
    gl_FragColor = Optics_GetEnvColor(m_Texture, dir);
}


```

`Sky.j3md`:

```j3md
MaterialDef Sky Plane {
    MaterialParameters {
        Int BoundDrawBuffer
        TextureCubeMap Texture
        Boolean SphereMap
        Boolean EquirectMap
        Vector3 NormalScale
    }
    Technique {
        VertexShader    GLSL300 GLSL150 GLSL100 :   Common/MatDefs/Misc/Sky.vert
        FragmentShader  GLSL300 GLSL150 GLSL100 :   Common/MatDefs/Misc/Sky.frag

        WorldParameters {
            ViewMatrix
            ProjectionMatrix
            WorldMatrixInverse
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            SPHERE_MAP : SphereMap
            EQUIRECT_MAP : EquirectMap
        }

        RenderState {
            DepthWrite Off
            DepthFunc Equal
        }
    }
}
```

`Sky.vert`:

```vert
#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform mat4 g_ViewMatrix;
uniform mat4 g_ProjectionMatrix;
uniform mat4 g_WorldMatrixInverse;

uniform vec3 m_NormalScale;

attribute vec3 inPosition;
attribute vec3 inNormal;

varying vec3 direction;

void main(){
    // set w coordinate to 0
    vec4 pos = vec4(inPosition, 0.0);

    // compute rotation only for view matrix
    pos = g_ViewMatrix * pos;

    // now find projection
    pos.w = 1.0;
    gl_Position = g_ProjectionMatrix * pos;

    vec4 normal = vec4(inNormal * m_NormalScale, 0.0);
    direction = (g_WorldMatrixInverse * normal).xyz;
}

```

`SkyNonCube.j3md`:

```j3md
MaterialDef Sky Plane {
    MaterialParameters {
        Int BoundDrawBuffer
        Texture2D Texture
        Boolean SphereMap
        Boolean EquirectMap
        Vector3 NormalScale
    }
    Technique {
        VertexShader   GLSL300 GLSL150 GLSL100:   Common/MatDefs/Misc/Sky.vert
        FragmentShader GLSL300 GLSL150 GLSL100:   Common/MatDefs/Misc/Sky.frag

        WorldParameters {
            ViewMatrix
            ProjectionMatrix
            WorldMatrixInverse
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            SPHERE_MAP : SphereMap
            EQUIRECT_MAP : EquirectMap
        }

        RenderState {
            DepthWrite Off
            DepthFunc Equal
        }
    }
}
```

`SoftParticle.frag`:

```frag
#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"

uniform DEPTHTEXTURE m_DepthTexture;
uniform float m_Softness; // Power used in the contrast function
varying vec2 vPos; // Position of the pixel
varying vec2 projPos;// z and w values in projection space

#ifdef USE_TEXTURE
uniform sampler2D m_Texture;
varying vec4 texCoord;
#endif

varying vec4 color;

float Contrast(float d){
    float val = clamp( 2.0*( (d > 0.5) ? 1.0-d : d ), 0.0, 1.0);
    float a = 0.5 * pow(val, m_Softness);
    return (d > 0.5) ? 1.0 - a : a;
}

float stdDiff(float d){   
    return clamp((d)*m_Softness,0.0,1.0);
}


void main(){
    if (color.a <= 0.01)
        discard;

    vec4 c = vec4(1.0,1.0,1.0,1.0);//color;
    #ifdef USE_TEXTURE
        #ifdef POINT_SPRITE
            vec2 uv = mix(texCoord.xy, texCoord.zw, gl_PointCoord.xy);
        #else
            vec2 uv = texCoord.xy;
        #endif
        c = texture2D(m_Texture, uv) * color;
    #endif


    float depthv = fetchTextureSample(m_DepthTexture, vPos, 0).x * 2.0 - 1.0; // Scene depth
    depthv *= projPos.y;
    float particleDepth = projPos.x;

    float zdiff = depthv - particleDepth;
    if(zdiff <= 0.0){
        discard;
    }
    // Computes alpha based on the particles distance to the rest of the scene
    c.a = c.a * stdDiff(zdiff);// Contrast(zdiff);
    gl_FragColor = c;
}
```

`SoftParticle.vert`:

```vert
#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform mat4 g_WorldViewProjectionMatrix;

attribute vec3 inPosition;
attribute vec4 inColor;
attribute vec4 inTexCoord;

varying vec4 color;
// z and w values in projection space
varying vec2 projPos;
varying vec2 vPos; // Position of the pixel in clip space



#ifdef USE_TEXTURE
varying vec4 texCoord;
#endif

#ifdef POINT_SPRITE
uniform mat4 g_WorldViewMatrix;
uniform mat4 g_WorldMatrix;
uniform vec3 g_CameraPosition;
uniform float m_Quadratic;
const float SIZE_MULTIPLIER = 4.0;
attribute float inSize;
#endif

void main(){
    vec4 pos = vec4(inPosition, 1.0);

    gl_Position = g_WorldViewProjectionMatrix * pos;
    color = inColor;

    projPos = gl_Position.zw;
   // projPos.x = 0.5 * (projPos.x) + 0.5;

    // Transforms the vPosition data to the range [0,1]
    vPos = (gl_Position.xy / gl_Position.w + 1.0) / 2.0;

    #ifdef USE_TEXTURE
        texCoord = inTexCoord;
    #endif

    #ifdef POINT_SPRITE
        vec4 worldPos = g_WorldMatrix * pos;
        float d = distance(g_CameraPosition.xyz, worldPos.xyz);
        gl_PointSize = max(1.0, (inSize * SIZE_MULTIPLIER * m_Quadratic) / d);

        //vec4 worldViewPos = g_WorldViewMatrix * pos;
        //gl_PointSize = (inSize * SIZE_MULTIPLIER * m_Quadratic)*100.0 / worldViewPos.z;

        color.a *= min(gl_PointSize, 1.0);
    #endif
}
```

`Unshaded.frag`:

```frag
#import "Common/ShaderLib/GLSLCompat.glsllib"

#if defined(HAS_GLOWMAP) || defined(HAS_COLORMAP) || (defined(HAS_LIGHTMAP) && !defined(SEPARATE_TEXCOORD))
    #define NEED_TEXCOORD1
#endif

#if defined(DISCARD_ALPHA)
    uniform float m_AlphaDiscardThreshold;
#endif

uniform vec4 m_Color;
uniform sampler2D m_ColorMap;
uniform sampler2D m_LightMap;

#ifdef DESATURATION
    uniform float m_DesaturationValue;
#endif

varying vec2 texCoord1;
varying vec2 texCoord2;

varying vec4 vertColor;

void main(){
    vec4 color = vec4(1.0);

    #ifdef HAS_COLORMAP
        color *= texture2D(m_ColorMap, texCoord1);     
    #endif

    #ifdef HAS_VERTEXCOLOR
        color *= vertColor;
    #endif

    #ifdef HAS_COLOR
        color *= m_Color;
    #endif

    #ifdef HAS_LIGHTMAP
        #ifdef SEPARATE_TEXCOORD
            color.rgb *= texture2D(m_LightMap, texCoord2).rgb;
        #else
            color.rgb *= texture2D(m_LightMap, texCoord1).rgb;
        #endif
    #endif

    #if defined(DISCARD_ALPHA)
        if(color.a < m_AlphaDiscardThreshold){
           discard;
        }
    #endif
    
    #ifdef DESATURATION
        vec3 gray = vec3(dot(vec3(0.2126,0.7152,0.0722), color.rgb));
        color.rgb = vec3(mix(color.rgb, gray, m_DesaturationValue));       
    #endif

    gl_FragColor = color;
}

```

`Unshaded.j3md`:

```j3md
MaterialDef Unshaded {

    MaterialParameters {
        Int BoundDrawBuffer
        Texture2D ColorMap
        Texture2D LightMap
        Color Color (Color)
        Boolean VertexColor (UseVertexColor)
        Float PointSize : 1.0
        Boolean SeparateTexCoord

        // Texture of the glowing parts of the material
        Texture2D GlowMap
        // The glow color of the object
        Color GlowColor

        // For instancing
        Boolean UseInstancing

        // For hardware skinning
        Int NumberOfBones
        Matrix4Array BoneMatrices

        // For Morph animation
        FloatArray MorphWeights
        Int NumberOfMorphTargets
        Int NumberOfTargetsBuffers

        // Alpha threshold for fragment discarding
        Float AlphaDiscardThreshold (AlphaTestFallOff)

        //Shadows
        Int FilterMode
        Boolean HardwareShadows

        Texture2D ShadowMap0
        Texture2D ShadowMap1
        Texture2D ShadowMap2
        Texture2D ShadowMap3
        //pointLights
        Texture2D ShadowMap4
        Texture2D ShadowMap5
        
        Float ShadowIntensity
        Vector4 Splits
        Vector2 FadeInfo

        Matrix4 LightViewProjectionMatrix0
        Matrix4 LightViewProjectionMatrix1
        Matrix4 LightViewProjectionMatrix2
        Matrix4 LightViewProjectionMatrix3
        //pointLight
        Matrix4 LightViewProjectionMatrix4
        Matrix4 LightViewProjectionMatrix5
        Vector3 LightPos
        Vector3 LightDir

        Float PCFEdge

        Float ShadowMapSize

        Boolean BackfaceShadows: true
        
        // 1.0 indicates 100% desaturation
        Float DesaturationValue
    }

    Technique {
        VertexShader   GLSL310 GLSL300 GLSL150 GLSL100 :   Common/MatDefs/Misc/Unshaded.vert
        FragmentShader GLSL310 GLSL300 GLSL150 GLSL100 : Common/MatDefs/Misc/Unshaded.frag

        WorldParameters {
            WorldViewProjectionMatrix
            ViewProjectionMatrix
            ViewMatrix
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            INSTANCING : UseInstancing
            SEPARATE_TEXCOORD : SeparateTexCoord
            HAS_COLORMAP : ColorMap
            HAS_LIGHTMAP : LightMap
            HAS_VERTEXCOLOR : VertexColor
            HAS_POINTSIZE : PointSize
            HAS_COLOR : Color
            NUM_BONES : NumberOfBones
            DISCARD_ALPHA : AlphaDiscardThreshold
            NUM_MORPH_TARGETS: NumberOfMorphTargets
            NUM_TARGETS_BUFFERS: NumberOfTargetsBuffers            
            DESATURATION : DesaturationValue
        }
    }

    Technique PreNormalPass {

        VertexShader   GLSL310 GLSL300 GLSL150 GLSL100:   Common/MatDefs/SSAO/normal.vert
        FragmentShader GLSL310 GLSL300 GLSL150 GLSL100:   Common/MatDefs/SSAO/normal.frag

        WorldParameters {
            WorldViewProjectionMatrix
            WorldViewMatrix
            NormalMatrix
            ViewProjectionMatrix
            ViewMatrix
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            COLORMAP_ALPHA : ColorMap
            NUM_BONES : NumberOfBones
            INSTANCING : UseInstancing
            NUM_MORPH_TARGETS: NumberOfMorphTargets
            NUM_TARGETS_BUFFERS: NumberOfTargetsBuffers
        }
   }

    Technique PreShadow {

        VertexShader   GLSL310 GLSL300 GLSL150 GLSL100:   Common/MatDefs/Shadow/PreShadow.vert
        FragmentShader GLSL310 GLSL300 GLSL150 GLSL100:   Common/MatDefs/Shadow/PreShadow.frag

        WorldParameters {
            WorldViewProjectionMatrix
            WorldViewMatrix
            ViewProjectionMatrix
            ViewMatrix
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            COLOR_MAP : ColorMap
            DISCARD_ALPHA : AlphaDiscardThreshold
            NUM_BONES : NumberOfBones
            INSTANCING : UseInstancing
            NUM_MORPH_TARGETS: NumberOfMorphTargets
            NUM_TARGETS_BUFFERS: NumberOfTargetsBuffers
        }

        ForcedRenderState {
            FaceCull Off
            DepthTest On
            DepthWrite On
            PolyOffset 5 3
            ColorWrite Off
        }

    }


    Technique PostShadow {
        VertexShader    GLSL310 GLSL300 GLSL150 GLSL100:   Common/MatDefs/Shadow/PostShadow.vert
        FragmentShader  GLSL310 GLSL300 GLSL150 GLSL100:   Common/MatDefs/Shadow/PostShadow.frag

        WorldParameters {
            WorldViewProjectionMatrix
            WorldMatrix
            ViewProjectionMatrix
            ViewMatrix
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            HARDWARE_SHADOWS : HardwareShadows
            FILTER_MODE : FilterMode
            PCFEDGE : PCFEdge
            DISCARD_ALPHA : AlphaDiscardThreshold           
            COLOR_MAP : ColorMap
            SHADOWMAP_SIZE : ShadowMapSize
            FADE : FadeInfo
            PSSM : Splits
            POINTLIGHT : LightViewProjectionMatrix5
            NUM_BONES : NumberOfBones
            INSTANCING : UseInstancing
            BACKFACE_SHADOWS: BackfaceShadows
            NUM_MORPH_TARGETS: NumberOfMorphTargets
            NUM_TARGETS_BUFFERS: NumberOfTargetsBuffers
        }

        ForcedRenderState {
            Blend Modulate
            DepthWrite Off                 
            PolyOffset -0.1 0
        }
    }

    Technique Glow {

        VertexShader   GLSL310 GLSL300 GLSL150 GLSL100:   Common/MatDefs/Misc/Unshaded.vert
        FragmentShader GLSL310 GLSL300 GLSL150 GLSL100:   Common/MatDefs/Light/Glow.frag

        WorldParameters {
            WorldViewProjectionMatrix
            ViewProjectionMatrix
            ViewMatrix
        }

        Defines {
            BOUND_DRAW_BUFFER: BoundDrawBuffer
            NEED_TEXCOORD1
            HAS_GLOWMAP : GlowMap
            HAS_GLOWCOLOR : GlowColor
            NUM_BONES : NumberOfBones
            INSTANCING : UseInstancing
            HAS_POINTSIZE : PointSize
            NUM_MORPH_TARGETS: NumberOfMorphTargets
            NUM_TARGETS_BUFFERS: NumberOfTargetsBuffers
        }
    }
}

```

`Unshaded.vert`:

```vert
#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/Skinning.glsllib"
#import "Common/ShaderLib/Instancing.glsllib"
#import "Common/ShaderLib/MorphAnim.glsllib"

attribute vec3 inPosition;

#if defined(HAS_COLORMAP) || (defined(HAS_LIGHTMAP) && !defined(SEPARATE_TEXCOORD))
    #define NEED_TEXCOORD1
#endif

attribute vec2 inTexCoord;
attribute vec2 inTexCoord2;
attribute vec4 inColor;

varying vec2 texCoord1;
varying vec2 texCoord2;

varying vec4 vertColor;
#ifdef HAS_POINTSIZE
    uniform float m_PointSize;
#endif

void main(){
    #ifdef NEED_TEXCOORD1
        texCoord1 = inTexCoord;
    #endif

    #ifdef SEPARATE_TEXCOORD
        texCoord2 = inTexCoord2;
    #endif

    #ifdef HAS_VERTEXCOLOR
        vertColor = inColor;
    #endif

    #ifdef HAS_POINTSIZE
        gl_PointSize = m_PointSize;
    #endif

    vec4 modelSpacePos = vec4(inPosition, 1.0);

    #ifdef NUM_MORPH_TARGETS
        Morph_Compute(modelSpacePos);
    #endif

    #ifdef NUM_BONES
        Skinning_Compute(modelSpacePos);
    #endif

    gl_Position = TransformWorldViewProjection(modelSpacePos);
}
```

`UnshadedNodes.j3md`:

```j3md
MaterialDef UnshadedNodes {
    MaterialParameters {
        Texture2D ColorMap
        Texture2D LightMap
        Color Color (Color) 
        Boolean VertexColor (UseVertexColor) 
        Boolean SeparateTexCoord
        Float AlphaDiscardThreshold (AlphaTestFallOff) 
        Int NumberOfBones
        Matrix4Array BoneMatrices
    }
    Technique {
        WorldParameters {
            WorldViewProjectionMatrix
            WorldViewMatrix
        }
        VertexShaderNodes {
            ShaderNode GpuSkinning {
                Definition : BasicGPUSkinning : Common/MatDefs/ShaderNodes/HardwareSkinning/HardwareSkinning.j3sn
                Condition : NumberOfBones
                InputMappings {
                    modelPosition = Global.position
                    boneMatrices = MatParam.BoneMatrices
                    boneWeight = Attr.inHWBoneWeight
                    boneIndex = Attr.inHWBoneIndex
                }
                OutputMappings {
                    Global.position = modModelPosition
                }
            }
            ShaderNode UnshadedVert {
                Definition : CommonVert : Common/MatDefs/ShaderNodes/Common/CommonVert.j3sn
                InputMappings {
                    worldViewProjectionMatrix = WorldParam.WorldViewProjectionMatrix
                    modelPosition = Global.position.xyz
                    texCoord1 = Attr.inTexCoord : ColorMap || (LightMap && !SeparateTexCoord)
                    texCoord2 = Attr.inTexCoord2 : SeparateTexCoord
                    vertColor = Attr.inColor : VertexColor
                }
                OutputMappings {
                    Global.position = projPosition
                }
            }
        }
        FragmentShaderNodes {
            ShaderNode MatColorMult {
                Definition : ColorMult : Common/MatDefs/ShaderNodes/Basic/ColorMult.j3sn
                InputMappings {
                    color1 = MatParam.Color
                    color2 = Global.outColor
                }
                OutputMappings {
                    Global.outColor = outColor
                }
                Condition : Color
            }
            ShaderNode VertColorMult {
                Definition : ColorMult : Common/MatDefs/ShaderNodes/Basic/ColorMult.j3sn
                InputMappings {
                    color1 = UnshadedVert.vertColor
                    color2 = Global.outColor
                }
                OutputMappings {
                    Global.outColor = outColor
                }
                Condition : VertexColor
            }
            ShaderNode ColorMapTF {
                Definition : TextureFetch : Common/MatDefs/ShaderNodes/Basic/TextureFetch.j3sn
                InputMappings {
                    texCoord = UnshadedVert.texCoord1
                    textureMap = MatParam.ColorMap
                }
                OutputMappings {
                }
                Condition : ColorMap
            }
            ShaderNode ColorMapMult {
                Definition : ColorMult : Common/MatDefs/ShaderNodes/Basic/ColorMult.j3sn
                InputMappings {
                    color1 = ColorMapTF.outColor
                    color2 = Global.outColor
                }
                OutputMappings {
                    Global.outColor = outColor
                }
                Condition : ColorMap
            }
            ShaderNode AlphaDiscardThreshold {
                Definition : AlphaDiscard : Common/MatDefs/ShaderNodes/Basic/AlphaDiscard.j3sn
                Condition : AlphaDiscardThreshold
                InputMappings {
                    alpha = Global.outColor.a
                    threshold = MatParam.AlphaDiscardThreshold
                }
            }
            ShaderNode LightMapTF {
                Definition : TextureFetch : Common/MatDefs/ShaderNodes/Basic/TextureFetch.j3sn
                InputMappings {
                    textureMap = MatParam.LightMap
                    texCoord = UnshadedVert.texCoord2 : SeparateTexCoord
                    texCoord = UnshadedVert.texCoord1 : !SeparateTexCoord
                }
                OutputMappings {
                }
                Condition : LightMap
            }
            ShaderNode LightMapMult {
                Definition : ColorMult : Common/MatDefs/ShaderNodes/Basic/ColorMult.j3sn
                OutputMappings {
                    Global.outColor = outColor
                }
                InputMappings {
                    color1 = LightMapTF.outColor
                    color2 = Global.outColor
                }
                Condition : LightMap
            }
        }
    }
}
```

`fakeLighting.j3md`:

```j3md
MaterialDef FakeLighting {
    MaterialParameters {
        Vector4 Color
    }

    Technique {
       WorldParameters {
           WorldViewProjectionMatrix
           NormalMatrix
       }

       VertexShaderNodes {
           ShaderNode Mat3Vec3Mult {
               Definition: Mat3Vec3Mult: Common/MatDefs/ShaderNodes/Basic/Mat3Vec3Mult.j3sn
               InputMappings {
                   matrix3 = WorldParam.NormalMatrix
                   vector3 = Attr.inNormal
               }
               OutputMappings {
               }
           }
           ShaderNode CommonVert {
               Definition: CommonVert: Common/MatDefs/ShaderNodes/Common/CommonVert.j3sn
               InputMappings {
                   worldViewProjectionMatrix = WorldParam.WorldViewProjectionMatrix
                   modelPosition = Attr.inPosition
               }
               OutputMappings {
                   Global.position = projPosition
               }
           }
       }


        FragmentShaderNodes {
            ShaderNode FakeLighting {
                Definition: FakeLighting: Common/MatDefs/ShaderNodes/Misc/fakeLighting.j3sn
                InputMappings {
                    inColor = MatParam.Color
                    normal = Mat3Vec3Mult.outVector3.xyz
                }
                OutputMappings {
                    Global.color = outColor
                }
            }
        }

    }
}

```

`reflect.j3md`:

```j3md
MaterialDef Simple {
    MaterialParameters {
        TextureCubeMap CubeMap
    }
    Technique {
        WorldParameters {
            WorldViewProjectionMatrix
            WorldMatrix
            CameraPosition
        }
        VertexShaderNodes {
            ShaderNode Reflect {
                Definition : Reflect : Common/MatDefs/ShaderNodes/Environment/reflect.j3sn
                InputMappings {
                    normal = Attr.inNormal
                    position = Global.position.xyz
                    worldMatrix = WorldParam.WorldMatrix
                    camPosition = WorldParam.CameraPosition
                }
            }
            ShaderNode CommonVert {
                Definition : CommonVert : Common/MatDefs/ShaderNodes/Common/CommonVert.j3sn
                InputMappings {
                    worldViewProjectionMatrix = WorldParam.WorldViewProjectionMatrix
                    modelPosition = Global.position.xyz
                }
                OutputMappings {
                    Global.position = projPosition
                }
            }
        }
        FragmentShaderNodes {
            ShaderNode EnvMapping {
                Definition : EnvMapping : Common/MatDefs/ShaderNodes/Environment/envMapping.j3sn
                InputMappings {
                    refVec = Reflect.refVec
                    cubeMap = MatParam.CubeMap
                }
                OutputMappings {
                    Global.color = color
                }
            }
        }
    }
}
```