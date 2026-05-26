package com.jme3.renderer.vulkan.cmd;

import com.jme3.material.MatParam;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.vulkan.VulkanRuntime;
import com.jme3.renderer.vulkan.pipeline.VkPipelineKey;
import com.jme3.renderer.vulkan.pipeline.VkVariantKey;
import com.jme3.renderer.vulkan.reflection.ParamBindingPlan;
import com.jme3.renderer.vulkan.resource.VkUboLayout;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.Shader;
import com.jme3.texture.Texture;
import com.jme3.util.ListMap;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class DrawCmdBuilder {

    private static final Logger LOGGER = Logger.getLogger(DrawCmdBuilder.class.getName());
    private final VulkanRuntime runtime;

    private static final class CachedShaderData {

        final String vertSrc;
        final String fragSrc;
        final String vertDefines;
        final String fragDefines;
        final String finalVertSrc;
        final String finalFragSrc;
        final int vertHash;
        final int fragHash;

        CachedShaderData(String vertSrc, String fragSrc, String vertDefines, String fragDefines,
                String finalVertSrc, String finalFragSrc, int vertHash, int fragHash) {
            this.vertSrc = vertSrc;
            this.fragSrc = fragSrc;
            this.vertDefines = vertDefines;
            this.fragDefines = fragDefines;
            this.finalVertSrc = finalVertSrc;
            this.finalFragSrc = finalFragSrc;
            this.vertHash = vertHash;
            this.fragHash = fragHash;
        }
    }
    private final WeakHashMap<Shader, CachedShaderData> shaderCache = new WeakHashMap<>();
    private static final ConcurrentHashMap<String, String> PARAM_NAME_CACHE = new ConcurrentHashMap<>();

    private static final class ExtractedShaderSources {

        final String vertSource;
        final String fragSource;
        final String vertDefines;
        final String fragDefines;

        private ExtractedShaderSources(String vertSource, String fragSource, String vertDefines, String fragDefines) {
            this.vertSource = vertSource;
            this.fragSource = fragSource;
            this.vertDefines = vertDefines;
            this.fragDefines = fragDefines;
        }
    }

    public DrawCmdBuilder(VulkanRuntime runtime) {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime is null");
        }
        this.runtime = runtime;
    }

    public void deleteShader(Shader shader) {
        if (shader != null) {
            shaderCache.remove(shader);
        }
    }
    
    public DrawCmd build(Mesh mesh, int lod, int count, VertexBuffer[] instanceData, RendererStateSnapshot s) {
        if (s == null) {
            return build(mesh, lod, count, instanceData, null, null, null, null, null, null, null, null, false,
                    0, 0, -1, -1, false, 0, 0, 0, 0, 0f, 1f);
        }
        return build(mesh, lod, count, instanceData, s.shader, s.renderState, s.tex0, s.light, s.extra, s.material, s.wvp, s.color, s.alphaToCoverage,
                s.vpX, s.vpY, s.vpW, s.vpH, s.clipEnabled, s.clipX, s.clipY, s.clipW, s.clipH, s.depthRangeStart, s.depthRangeEnd);
    }

    public DrawCmd build(Mesh mesh, int lod, int count, VertexBuffer[] instanceData,
            Shader currentShader, RenderState currentRenderState, Texture currentTex0, Texture currentLight, Texture currentExtra) {
        return build(mesh, lod, count, instanceData, currentShader, currentRenderState, currentTex0, currentLight, currentExtra, null, null, null, false,
                0, 0, -1, -1, false, 0, 0, 0, 0, 0f, 1f);
    }

    private DrawCmd build(Mesh mesh, int lod, int count, VertexBuffer[] instanceData,
            Shader currentShader, RenderState currentRenderState, Texture currentTex0, Texture currentLight, Texture currentExtra,
            Material currentMaterial, Matrix4f overrideWvp, ColorRGBA overrideColor, boolean alphaToCoverage,
            int vpX, int vpY, int vpW, int vpH, boolean clipEnabled, int clipX, int clipY, int clipW, int clipH,
            float depthRangeStart, float depthRangeEnd) {

        DrawCmd dc = DrawCmd.acquire();
        dc.mesh = mesh;
        dc.lod = lod;
        dc.count = count;
        dc.instanceData = instanceData;
        dc.objectId = (mesh != null) ? System.identityHashCode(mesh) : 0;
        dc.renderState = currentRenderState;

        dc.vpX = vpX;
        dc.vpY = vpY;
        dc.vpW = vpW;
        dc.vpH = vpH;
        dc.clipEnabled = clipEnabled;
        dc.clipX = clipX;
        dc.clipY = clipY;
        dc.clipW = clipW;
        dc.clipH = clipH;
        dc.depthRangeStart = depthRangeStart;
        dc.depthRangeEnd = depthRangeEnd;

        if (currentShader != null) {
            CachedShaderData csd = shaderCache.get(currentShader);
            if (csd == null) {
                csd = buildShaderData(currentShader);
                shaderCache.put(currentShader, csd);
            }
            dc.vertSrc = csd.vertSrc;
            dc.fragSrc = csd.fragSrc;
            dc.vertDefines = csd.vertDefines;
            dc.fragDefines = csd.fragDefines;
            dc.finalVertSrc = csd.finalVertSrc;
            dc.finalFragSrc = csd.finalFragSrc;
            dc.vertHash = csd.vertHash;
            dc.fragHash = csd.fragHash;
        } else {
            dc.vertHash = 0;
            dc.fragHash = 0;
        }

        if (overrideWvp != null) {
            dc.wvpSnapshot.set(overrideWvp);
        } else {
            Matrix4f wvp = getMat4Uniform(currentShader, "g_WorldViewProjectionMatrix");
            if (wvp != null) {
                dc.wvpSnapshot.set(wvp);
            } else {
                dc.wvpSnapshot.loadIdentity();
            }
        }

        if (overrideColor != null) {
            dc.colorSnapshot.set(overrideColor);
        } else {
            dc.colorSnapshot.set(resolveColorSnapshot(currentShader));
        }

        dc.jmeTex0Snapshot = currentTex0;
        dc.jmeLightSnapshot = currentLight;
        dc.materialKeySnapshot = new MaterialSnapshotKey(dc.jmeTex0Snapshot, dc.jmeLightSnapshot);
        dc.materialResolvePlan = buildMaterialResolvePlan(dc.jmeTex0Snapshot, dc.jmeLightSnapshot);
        dc.useWhiteTex0 = dc.materialResolvePlan.fallbackWhiteTex0;
        dc.useWhiteLight = dc.materialResolvePlan.fallbackWhiteLight;
        dc.tex0SamplerSnapshot = runtime.chooseSamplerForTex0(dc.useWhiteTex0 ? null : dc.materialResolvePlan.tex0);
        dc.lightSamplerSnapshot = runtime.chooseSamplerForLight(dc.useWhiteLight ? null : dc.materialResolvePlan.light);

        Texture extraTex = resolveTextureByParamName(currentMaterial, "extratex");
        if (!isUsableTex2D(extraTex) && isUsableTex2D(currentExtra)) {
            extraTex = currentExtra;
        }

        dc.jmeExtraSnapshot = extraTex;
        dc.useWhiteExtra = !isUsableTex2D(extraTex);
        dc.extraSamplerSnapshot = runtime.getOrCreateSampler(dc.useWhiteExtra ? null : extraTex);

        boolean hasColor = hasMaterialColor(currentShader);
        boolean hasColorMap = (dc.materialResolvePlan.tex0 != null && !dc.useWhiteTex0);
        boolean hasLightMap = (dc.materialResolvePlan.light != null && !dc.useWhiteLight);
        dc.variant = new VkVariantKey(hasColorMap, hasColor, hasLightMap);

        if (dc.finalVertSrc != null && dc.finalFragSrc != null) {

            // =========================================================================
            // 【核心修改】：通过 Mesh 动态计算顶点数据结构掩码
            // 将每种属性的“分量数量(1~4)”压缩进一个整数中，每 4 位代表一个 Location
            // =========================================================================
            int vertexMask = 0;
            if (mesh != null) {
                for (int i = 0; i < VkPipelineKey.VERTEX_TYPES.length; i++) {
                    VertexBuffer.Type type = VkPipelineKey.VERTEX_TYPES[i];
                    VertexBuffer vb = mesh.getBuffer(type);
                    if (vb != null) {
                        vertexMask |= (vb.getNumComponents() & 0xF) << (i * 4);
                    } else if (type == VertexBuffer.Type.Color) {
                        // 【兜底保护】预见后续会补充 Color，声明它存在以匹配默认 Shader
                        vertexMask |= (4 & 0xF) << (i * 4);
                    } else if (type == VertexBuffer.Type.TexCoord) {
                        // 【兜底保护】预见后续会补充 TexCoord
                        vertexMask |= (2 & 0xF) << (i * 4);
                    }
                }
            }

            dc.pipelineKey = VkPipelineKey.fromHashes(
                    dc.vertHash, dc.fragHash, dc.renderState, dc.variant, runtime.getDefaultPassKey(), vertexMask, alphaToCoverage
            );
        }

        if (dc.pipelineKey != null) {
            VkUboLayout drawLayout = runtime.getPipelineUboLayout(dc.pipelineKey);
            if (drawLayout != null && drawLayout.sliceSize > 0 && drawLayout.fields != null) {
                // 【修改】：直接使用预分配的 dc.uboData 写入
                for (com.jme3.renderer.vulkan.resource.VkUboLayout.UboField f : drawLayout.fields) {
                    int idx = f.offset / 4;
                    if (idx < 0 || idx >= dc.uboData.length) {;
                        continue;
                    }

                    if ("g_WorldViewProjectionMatrix".equals(f.name) && dc.wvpSnapshot != null) {
                        Matrix4f m = dc.wvpSnapshot;
                        if (idx + 15 < dc.uboData.length) {
                            dc.uboData[idx] = m.m00;
                            dc.uboData[idx + 1] = m.m10;
                            dc.uboData[idx + 2] = m.m20;
                            dc.uboData[idx + 3] = m.m30;
                            dc.uboData[idx + 4] = m.m01;
                            dc.uboData[idx + 5] = m.m11;
                            dc.uboData[idx + 6] = m.m21;
                            dc.uboData[idx + 7] = m.m31;
                            dc.uboData[idx + 8] = m.m02;
                            dc.uboData[idx + 9] = m.m12;
                            dc.uboData[idx + 10] = m.m22;
                            dc.uboData[idx + 11] = m.m32;
                            dc.uboData[idx + 12] = m.m03;
                            dc.uboData[idx + 13] = m.m13;
                            dc.uboData[idx + 14] = m.m23;
                            dc.uboData[idx + 15] = m.m33;
                        }
                        continue;
                    }
                    if ("m_Color".equals(f.name) && dc.colorSnapshot != null) {
                        if (idx + 3 < dc.uboData.length) {
                            dc.uboData[idx] = dc.colorSnapshot.r;
                            dc.uboData[idx + 1] = dc.colorSnapshot.g;
                            dc.uboData[idx + 2] = dc.colorSnapshot.b;
                            dc.uboData[idx + 3] = dc.colorSnapshot.a;
                        }
                        continue;
                    }

                    if ("g_Resolution".equals(f.name) || "g_Time".equals(f.name) || "g_Mouse".equals(f.name)) {
                        continue;
                    }

                    Object val = null;
                    com.jme3.shader.Uniform u = currentShader != null ? currentShader.getUniform(f.name) : null;
                    if (u != null && u.getValue() != null) {
                        val = u.getValue();
                    } else if (f.name.startsWith("m_") && currentMaterial != null) {
                        MatParam mp = currentMaterial.getParam(f.name.substring(2));
                        if (mp != null) {
                            val = mp.getValue();
                        }
                    } else if (currentMaterial != null) {
                        MatParam mp = currentMaterial.getParam(f.name);
                        if (mp != null) {
                            val = mp.getValue();
                        }
                    }

                    if (val != null) {
                        writeValToFloatArray(val, dc.uboData, idx);
                    }
                }
            }

            ParamBindingPlan pbp = runtime.getPipelineParamBindingPlan(dc.pipelineKey);
            if (pbp != null && pbp.samplerByName != null && !pbp.samplerByName.isEmpty()) {
                dc.customImageCount = 0;

                for (Map.Entry<String, ParamBindingPlan.BindingSlot> e : pbp.samplerByName.entrySet()) {
                    if (dc.customImageCount >= 16) {
                        break; // 防溢出
                    }
                    String normName = e.getKey();
                    ParamBindingPlan.BindingSlot slot = e.getValue();
                    Texture tex = resolveTextureByParamName(currentMaterial, normName);

                    if (!isUsableTex2D(tex)) {
                        if ("colormap".equals(normName) || "diffusemap".equals(normName) || "basecolormap".equals(normName) || "texture".equals(normName)) {
                            tex = currentTex0;
                        } else if ("lightmap".equals(normName)) {
                            tex = currentLight;
                        } else if ("extratex".equals(normName) || "extramap".equals(normName)) {
                            tex = currentExtra;
                        } else {
                            ParamBindingPlan.BindingSlot firstTexSlot = pbp.getTextureSlot(slot.set);
                            if (firstTexSlot != null && firstTexSlot.binding == slot.binding) {
                                if (slot.set == 0) {
                                    tex = currentTex0;
                                } else if (slot.set == 1) {
                                    tex = currentExtra;
                                }
                            }
                        }
                    }

                    dc.customImageSlots[dc.customImageCount] = slot;
                    dc.customImageTextures[dc.customImageCount] = tex;
                    dc.customImageCount++;
                }
            }
        }
        return dc;
    }

    private CachedShaderData buildShaderData(Shader shader) {
        ExtractedShaderSources extracted = extractShaderSources(shader);
        if (extracted == null) {
            return new CachedShaderData(null, null, null, null, null, null, 0, 0);
        }

        String fVert = composeFinalShaderSource(extracted.vertDefines, extracted.vertSource);
        String fFrag = composeFinalShaderSource(extracted.fragDefines, extracted.fragSource);
        int vHash = (fVert != null) ? fVert.hashCode() : 0;
        int fHash = (fFrag != null) ? fFrag.hashCode() : 0;

        return new CachedShaderData(extracted.vertSource, extracted.fragSource,
                extracted.vertDefines, extracted.fragDefines, fVert, fFrag, vHash, fHash);
    }

    private static ExtractedShaderSources extractShaderSources(Shader shader) {
        if (shader == null || shader.getSources() == null) {
            return null;
        }
        String vert = null, frag = null, vertDefines = null, fragDefines = null;
        for (Shader.ShaderSource ss : shader.getSources()) {
            if (ss == null) {
                continue;
            }
            if (ss.getType() == Shader.ShaderType.Vertex) {
                vert = ss.getSource();
                vertDefines = ss.getDefines();
            } else if (ss.getType() == Shader.ShaderType.Fragment) {
                frag = ss.getSource();
                fragDefines = ss.getDefines();
            }
        }
        if (vert == null || frag == null) {
            return null;
        }
        return new ExtractedShaderSources(vert, frag, vertDefines, fragDefines);
    }

    private static String composeFinalShaderSource(String defines, String source) {
        if (source == null) {
            return null;
        }
        String d = normalizeShaderChunk(defines);
        String s = normalizeShaderChunk(source);
        if (d.isEmpty()) {
            return s;
        }
        if (s.isEmpty()) {
            return d;
        }

        int versionLineEnd = findLeadingVersionDirectiveLineEnd(s);
        if (versionLineEnd >= 0) {
            String firstPart = s.substring(0, versionLineEnd);
            String restPart = s.substring(versionLineEnd);
            StringBuilder out = new StringBuilder(s.length() + d.length() + 8);
            out.append(firstPart);
            if (!firstPart.endsWith("\n")) {
                out.append('\n');
            }
            out.append(d);
            if (!d.endsWith("\n")) {
                out.append('\n');
            }
            out.append(restPart);
            return out.toString();
        } else {
            StringBuilder out = new StringBuilder(s.length() + d.length() + 8);
            out.append(d);
            if (!d.endsWith("\n")) {
                out.append('\n');
            }
            out.append(s);
            return out.toString();
        }
    }

    private static String normalizeShaderChunk(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String out = s.replace("\r\n", "\n").replace('\r', '\n');
        if (!out.isEmpty() && out.charAt(0) == '\ufeff') {
            out = out.substring(1);
        }
        return out;
    }

    private static int findLeadingVersionDirectiveLineEnd(String source) {
        if (source == null || source.isEmpty()) {
            return -1;
        }
        int len = source.length();
        int i = 0;
        while (i < len) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n') {
                i++;
                continue;
            }
            break;
        }
        if (i >= len) {
            return -1;
        }
        if (!source.startsWith("#version", i)) {
            return -1;
        }
        int lineEnd = source.indexOf('\n', i);
        if (lineEnd < 0) {
            return len;
        }
        return lineEnd + 1;
    }

    private static MaterialResolvePlan buildMaterialResolvePlan(Texture tex0, Texture light) {
        return new MaterialResolvePlan(tex0, light, !isUsableTex2D(tex0), !isUsableTex2D(light));
    }

    private static boolean isUsableTex2D(Texture t) {
        if (t == null) {
            return false;
        }
        com.jme3.texture.Image img = t.getImage();
        return img != null && img.getWidth() > 0 && img.getHeight() > 0;
    }

    private static ColorRGBA resolveColorSnapshot(Shader shader) {
        if (shader == null) {
            return ColorRGBA.White.clone();
        }
        com.jme3.shader.Uniform uColor = shader.getUniform("m_Color");
        if (uColor != null && uColor.getValue() instanceof ColorRGBA) {
            return ((ColorRGBA) uColor.getValue()).clone();
        }
        return ColorRGBA.White.clone();
    }

    private static boolean hasMaterialColor(Shader shader) {
        if (shader == null) {
            return false;
        }
        com.jme3.shader.Uniform uColor = shader.getUniform("m_Color");
        return uColor != null && (uColor.getValue() instanceof ColorRGBA);
    }

    private static Matrix4f getMat4Uniform(Shader shader, String name) {
        if (shader == null) {
            return null;
        }
        com.jme3.shader.Uniform u = shader.getUniform(name);
        if (u == null) {
            return null;
        }
        if (u.getValue() instanceof Matrix4f) {
            return (Matrix4f) u.getValue();
        }
        return null;
    }

    private static Texture resolveTextureByParamName(Material mat, String normWanted) {
        if (mat == null || normWanted == null) {
            return null;
        }

        String cachedJmeName = PARAM_NAME_CACHE.get(normWanted);
        if (cachedJmeName != null) {
            MatParam p = mat.getParam(cachedJmeName);
            if (p != null) {
                Texture t = textureFromMatParam(p);
                if (isUsableTex2D(t)) {
                    return t;
                }
            }
        }

        ListMap<String, MatParam> paramsMap = mat.getParamsMap();
        int size = paramsMap.size();
        for (int i = 0; i < size; i++) {
            MatParam mp = paramsMap.getValue(i);
            if (mp == null) {
                continue;
            }
            if (mp.getVarType() != null && !mp.getVarType().isTextureType()) {
                continue;
            }

            String paramName = mp.getName();
            String n = ParamBindingPlan.normalizeParamName(paramName);

            if (normWanted.equals(n)) {
                PARAM_NAME_CACHE.put(normWanted, paramName);
                Texture t = textureFromMatParam(mp);
                if (isUsableTex2D(t)) {
                    return t;
                }
            }
        }
        return null;
    }

    private static Texture textureFromMatParam(MatParam p) {
        if (p == null || p.getValue() == null) {
            return null;
        }
        if (p instanceof MatParamTexture) {
            return ((MatParamTexture) p).getTextureValue();
        }
        Object v = p.getValue();
        if (v instanceof Texture) {
            return (Texture) v;
        }
        return null;
    }

    private static void writeValToFloatArray(Object val, float[] data, int offset) {
        if (val instanceof Float) {
            if (offset < data.length) {
                data[offset] = (Float) val;
            }
        } else if (val instanceof Integer) {
            if (offset < data.length) {
                data[offset] = Float.intBitsToFloat((Integer) val);
            }
        } else if (val instanceof Boolean) {
            if (offset < data.length) {
                data[offset] = Float.intBitsToFloat(((Boolean) val) ? 1 : 0);
            }
        } else if (val instanceof com.jme3.math.Vector2f) {
            com.jme3.math.Vector2f v = (com.jme3.math.Vector2f) val;
            if (offset + 1 < data.length) {
                data[offset] = v.x;
                data[offset + 1] = v.y;
            }
        } else if (val instanceof com.jme3.math.Vector3f) {
            com.jme3.math.Vector3f v = (com.jme3.math.Vector3f) val;
            if (offset + 2 < data.length) {
                data[offset] = v.x;
                data[offset + 1] = v.y;
                data[offset + 2] = v.z;
            }
        } else if (val instanceof com.jme3.math.Vector4f) {
            com.jme3.math.Vector4f v = (com.jme3.math.Vector4f) val;
            if (offset + 3 < data.length) {
                data[offset] = v.x;
                data[offset + 1] = v.y;
                data[offset + 2] = v.z;
                data[offset + 3] = v.w;
            }
        } else if (val instanceof ColorRGBA) {
            ColorRGBA c = (ColorRGBA) val;
            if (offset + 3 < data.length) {
                data[offset] = c.r;
                data[offset + 1] = c.g;
                data[offset + 2] = c.b;
                data[offset + 3] = c.a;
            }
        } else if (val instanceof Matrix4f) {
            Matrix4f m = (Matrix4f) val;
            if (offset + 15 < data.length) {
                data[offset] = m.m00;
                data[offset + 1] = m.m10;
                data[offset + 2] = m.m20;
                data[offset + 3] = m.m30;
                data[offset + 4] = m.m01;
                data[offset + 5] = m.m11;
                data[offset + 6] = m.m21;
                data[offset + 7] = m.m31;
                data[offset + 8] = m.m02;
                data[offset + 9] = m.m12;
                data[offset + 10] = m.m22;
                data[offset + 11] = m.m32;
                data[offset + 12] = m.m03;
                data[offset + 13] = m.m13;
                data[offset + 14] = m.m23;
                data[offset + 15] = m.m33;
            }
        } else if (val instanceof com.jme3.math.Matrix3f) {
            com.jme3.math.Matrix3f m = (com.jme3.math.Matrix3f) val;
            if (offset + 11 < data.length) {
                data[offset] = m.get(0, 0);
                data[offset + 1] = m.get(1, 0);
                data[offset + 2] = m.get(2, 0);
                data[offset + 4] = m.get(0, 1);
                data[offset + 5] = m.get(1, 1);
                data[offset + 6] = m.get(2, 1);
                data[offset + 8] = m.get(0, 2);
                data[offset + 9] = m.get(1, 2);
                data[offset + 10] = m.get(2, 2);
            }
        } else if (val instanceof java.nio.FloatBuffer) {
            java.nio.FloatBuffer fb = (java.nio.FloatBuffer) val;
            int pos = fb.position();
            int len = Math.min(fb.remaining(), data.length - offset);
            for (int i = 0; i < len; i++) {
                data[offset + i] = fb.get(pos + i);
            }
        } else if (val instanceof java.nio.IntBuffer) {
            java.nio.IntBuffer ib = (java.nio.IntBuffer) val;
            int pos = ib.position();
            int len = Math.min(ib.remaining(), data.length - offset);
            for (int i = 0; i < len; i++) {
                data[offset + i] = Float.intBitsToFloat(ib.get(pos + i));
            }
        }
    }
}
