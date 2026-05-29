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

public final class DrawCmdBuilder {

    private final VulkanRuntime runtime;

    private static final class CachedShaderData {

        final String vertSrc, fragSrc, compSrc;
        final String vertDefines, fragDefines, compDefines;
        final String finalVertSrc, finalFragSrc, finalCompSrc;
        final int vertHash, fragHash, compHash;

        CachedShaderData(String vertSrc, String fragSrc, String compSrc, String vertDefines, String fragDefines, String compDefines,
                String finalVertSrc, String finalFragSrc, String finalCompSrc, int vertHash, int fragHash, int compHash) {
            this.vertSrc = vertSrc;
            this.fragSrc = fragSrc;
            this.compSrc = compSrc;
            this.vertDefines = vertDefines;
            this.fragDefines = fragDefines;
            this.compDefines = compDefines;
            this.finalVertSrc = finalVertSrc;
            this.finalFragSrc = finalFragSrc;
            this.finalCompSrc = finalCompSrc;
            this.vertHash = vertHash;
            this.fragHash = fragHash;
            this.compHash = compHash;
        }
    }
    private final WeakHashMap<Shader, CachedShaderData> shaderCache = new WeakHashMap<>();
    private static final ConcurrentHashMap<String, String> PARAM_NAME_CACHE = new ConcurrentHashMap<>();

    private static final class ExtractedShaderSources {

        final String vertSource, fragSource, compSource;
        final String vertDefines, fragDefines, compDefines;

        private ExtractedShaderSources(String vertSource, String fragSource, String compSource, String vertDefines, String fragDefines, String compDefines) {
            this.vertSource = vertSource;
            this.fragSource = fragSource;
            this.compSource = compSource;
            this.vertDefines = vertDefines;
            this.fragDefines = fragDefines;
            this.compDefines = compDefines;
        }
    }

    public DrawCmdBuilder(VulkanRuntime runtime) {
        this.runtime = runtime;
    }

    public void deleteShader(Shader shader) {
        if (shader != null) {
            shaderCache.remove(shader);
        }
    }

    public ComputeCmd buildCompute(int groupX, int groupY, int groupZ, RendererStateSnapshot s) {
        if (s == null || s.shader == null) {
            return null;
        }
        ComputeCmd cmd = ComputeCmd.acquire();
        cmd.groupX = groupX;
        cmd.groupY = groupY;
        cmd.groupZ = groupZ;
        cmd.shader = s.shader;
        System.arraycopy(s.ssbos, 0, cmd.ssbos, 0, 16);
        System.arraycopy(s.images, 0, cmd.images, 0, 16);

        CachedShaderData csd = shaderCache.get(s.shader);
        if (csd == null) {
            csd = buildShaderData(s.shader);
            shaderCache.put(s.shader, csd);
        }

        if (csd.finalCompSrc == null) {
            cmd.recycle();
            return null;
        }

        cmd.compSrc = csd.compSrc;
        cmd.compDefines = csd.compDefines;
        cmd.finalCompSrc = csd.finalCompSrc;
        cmd.compHash = csd.compHash;
        cmd.pipelineKey = new com.jme3.renderer.vulkan.pipeline.VkComputePipelineKey(cmd.compHash);

        // Pre-warm the pipeline to generate UBO layout before use
        runtime.getOrCreateComputePipeline(cmd.pipelineKey, cmd.finalCompSrc);

        VkUboLayout compLayout = runtime.getPipelineUboLayoutForCompute(cmd.pipelineKey);
        if (compLayout != null && compLayout.sliceSize > 0 && compLayout.fields != null) {
            for (VkUboLayout.UboField f : compLayout.fields) {
                int idx = f.offset / 4;
                if (idx < 0 || idx >= cmd.uboData.length) {
                    continue;
                }
                Object val = null;
                com.jme3.shader.Uniform u = s.shader.getUniform(f.name);
                if (u != null && u.getValue() != null) {
                    val = u.getValue();
                }
                if (val != null) {
                    writeValToFloatArray(val, cmd.uboData, idx);
                }
            }
        }
        return cmd;
    }

    public DrawCmd build(Mesh mesh, int lod, int count, VertexBuffer[] instanceData, RendererStateSnapshot s) {
        DrawCmd dc = DrawCmd.acquire();
        dc.mesh = mesh;
        dc.lod = lod;
        dc.count = count;
        dc.instanceData = instanceData;
        dc.objectId = (mesh != null) ? System.identityHashCode(mesh) : 0;
        dc.renderState = s.renderState;

        dc.vpX = s.vpX;
        dc.vpY = s.vpY;
        dc.vpW = s.vpW;
        dc.vpH = s.vpH;
        dc.clipEnabled = s.clipEnabled;
        dc.clipX = s.clipX;
        dc.clipY = s.clipY;
        dc.clipW = s.clipW;
        dc.clipH = s.clipH;
        dc.depthRangeStart = s.depthRangeStart;
        dc.depthRangeEnd = s.depthRangeEnd;

        System.arraycopy(s.ssbos, 0, dc.ssbos, 0, 16);
        System.arraycopy(s.images, 0, dc.images, 0, 16);

        if (s.shader != null) {
            CachedShaderData csd = shaderCache.get(s.shader);
            if (csd == null) {
                csd = buildShaderData(s.shader);
                shaderCache.put(s.shader, csd);
            }
            dc.vertSrc = csd.vertSrc;
            dc.fragSrc = csd.fragSrc;
            dc.vertDefines = csd.vertDefines;
            dc.fragDefines = csd.fragDefines;
            dc.finalVertSrc = csd.finalVertSrc;
            dc.finalFragSrc = csd.finalFragSrc;
            dc.vertHash = csd.vertHash;
            dc.fragHash = csd.fragHash;
        }

        if (s.wvp != null) {
            dc.wvpSnapshot.set(s.wvp);
        } else {
            Matrix4f wvp = getMat4Uniform(s.shader, "g_WorldViewProjectionMatrix");
            if (wvp != null) {
                dc.wvpSnapshot.set(wvp);
            } else {
                dc.wvpSnapshot.loadIdentity();
            }
        }

        if (s.color != null) {
            dc.colorSnapshot.set(s.color);
        } else {
            dc.colorSnapshot.set(resolveColorSnapshot(s.shader));
        }

        dc.jmeTex0Snapshot = s.tex0;
        dc.jmeLightSnapshot = s.light;
        dc.materialKeySnapshot = new MaterialSnapshotKey(dc.jmeTex0Snapshot, dc.jmeLightSnapshot);
        dc.materialResolvePlan = new MaterialResolvePlan(dc.jmeTex0Snapshot, dc.jmeLightSnapshot, !isUsableTex2D(dc.jmeTex0Snapshot), !isUsableTex2D(dc.jmeLightSnapshot));
        dc.useWhiteTex0 = dc.materialResolvePlan.fallbackWhiteTex0;
        dc.useWhiteLight = dc.materialResolvePlan.fallbackWhiteLight;
        dc.tex0SamplerSnapshot = runtime.chooseSamplerForTex0(dc.useWhiteTex0 ? null : dc.materialResolvePlan.tex0);
        dc.lightSamplerSnapshot = runtime.chooseSamplerForLight(dc.useWhiteLight ? null : dc.materialResolvePlan.light);

        Texture extraTex = resolveTextureByParamName(s.material, "extratex");
        if (!isUsableTex2D(extraTex) && isUsableTex2D(s.extra)) {
            extraTex = s.extra;
        }

        dc.jmeExtraSnapshot = extraTex;
        dc.useWhiteExtra = !isUsableTex2D(extraTex);
        dc.extraSamplerSnapshot = runtime.getOrCreateSampler(dc.useWhiteExtra ? null : extraTex);

        boolean hasColor = hasMaterialColor(s.shader);
        boolean hasColorMap = (dc.materialResolvePlan.tex0 != null && !dc.useWhiteTex0);
        boolean hasLightMap = (dc.materialResolvePlan.light != null && !dc.useWhiteLight);
        dc.variant = new VkVariantKey(hasColorMap, hasColor, hasLightMap);

        if (dc.finalVertSrc != null && dc.finalFragSrc != null) {
            int vertexMask = 0;
            if (mesh != null) {
                for (int i = 0; i < VkPipelineKey.VERTEX_TYPES.length; i++) {
                    VertexBuffer.Type type = VkPipelineKey.VERTEX_TYPES[i];
                    VertexBuffer vb = mesh.getBuffer(type);
                    if (vb != null) {
                        vertexMask |= (vb.getNumComponents() & 0xF) << (i * 4);
                    } else if (type == VertexBuffer.Type.Color) {
                        vertexMask |= (4 & 0xF) << (i * 4);
                    } else if (type == VertexBuffer.Type.TexCoord) {
                        vertexMask |= (2 & 0xF) << (i * 4);
                    }
                }
            }
            dc.pipelineKey = VkPipelineKey.fromHashes(dc.vertHash, dc.fragHash, dc.renderState, dc.variant, runtime.getDefaultPassKey(), vertexMask, s.alphaToCoverage);
        }

        if (dc.pipelineKey != null) {
            VkUboLayout drawLayout = runtime.getPipelineUboLayout(dc.pipelineKey);
            if (drawLayout != null && drawLayout.sliceSize > 0 && drawLayout.fields != null) {
                for (VkUboLayout.UboField f : drawLayout.fields) {
                    int idx = f.offset / 4;
                    if (idx < 0 || idx >= dc.uboData.length) {
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
                    com.jme3.shader.Uniform u = s.shader != null ? s.shader.getUniform(f.name) : null;
                    if (u != null && u.getValue() != null) {
                        val = u.getValue();
                    } else if (f.name.startsWith("m_") && s.material != null) {
                        MatParam mp = s.material.getParam(f.name.substring(2));
                        if (mp != null) {
                            val = mp.getValue();
                        }
                    } else if (s.material != null) {
                        MatParam mp = s.material.getParam(f.name);
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
                        break;
                    }
                    String normName = e.getKey();
                    ParamBindingPlan.BindingSlot slot = e.getValue();
                    Texture tex = resolveTextureByParamName(s.material, normName);
                    if (!isUsableTex2D(tex)) {
                        if ("colormap".equals(normName) || "diffusemap".equals(normName) || "basecolormap".equals(normName) || "texture".equals(normName)) {
                            tex = s.tex0;
                        } else if ("lightmap".equals(normName)) {
                            tex = s.light;
                        } else if ("extratex".equals(normName) || "extramap".equals(normName)) {
                            tex = s.extra;
                        } else {
                            ParamBindingPlan.BindingSlot firstTexSlot = pbp.getTextureSlot(slot.set);
                            if (firstTexSlot != null && firstTexSlot.binding == slot.binding) {
                                if (slot.set == 0) {
                                    tex = s.tex0;
                                } else if (slot.set == 1) {
                                    tex = s.extra;
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
            return new CachedShaderData(null, null, null, null, null, null, null, null, null, 0, 0, 0);
        }

        String fVert = composeFinalShaderSource(extracted.vertDefines, extracted.vertSource);
        String fFrag = composeFinalShaderSource(extracted.fragDefines, extracted.fragSource);
        String fComp = composeFinalShaderSource(extracted.compDefines, extracted.compSource);
        return new CachedShaderData(extracted.vertSource, extracted.fragSource, extracted.compSource,
                extracted.vertDefines, extracted.fragDefines, extracted.compDefines,
                fVert, fFrag, fComp,
                (fVert != null) ? fVert.hashCode() : 0, (fFrag != null) ? fFrag.hashCode() : 0, (fComp != null) ? fComp.hashCode() : 0);
    }

    private static ExtractedShaderSources extractShaderSources(Shader shader) {
        if (shader == null || shader.getSources() == null) {
            return null;
        }
        String vert = null, frag = null, comp = null, vertDefines = null, fragDefines = null, compDefines = null;

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
            } else if (ss.getType() == Shader.ShaderType.Compute) {
                comp = ss.getSource();
                compDefines = ss.getDefines();
            }
        }

        if (vert == null && frag == null && comp == null) {
            return null;
        }
        return new ExtractedShaderSources(vert, frag, comp, vertDefines, fragDefines, compDefines);
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
            return d + (d.endsWith("\n") ? "" : "\n") + s;
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
        return lineEnd < 0 ? len : lineEnd + 1;
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
        if (u != null && u.getValue() instanceof Matrix4f) {
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
        for (int i = 0; i < paramsMap.size(); i++) {
            MatParam mp = paramsMap.getValue(i);
            if (mp == null || (mp.getVarType() != null && !mp.getVarType().isTextureType())) {
                continue;
            }
            String n = ParamBindingPlan.normalizeParamName(mp.getName());
            if (normWanted.equals(n)) {
                PARAM_NAME_CACHE.put(normWanted, mp.getName());
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
        if (p.getValue() instanceof Texture) {
            return (Texture) p.getValue();
        }
        return null;
    }

    public static void writeValToFloatArray(Object val, float[] data, int offset) {
        if (val instanceof Float && offset < data.length) {
            data[offset] = (Float) val;
        } else if (val instanceof Integer && offset < data.length) {
            data[offset] = Float.intBitsToFloat((Integer) val);
        } else if (val instanceof Boolean && offset < data.length) {
            data[offset] = Float.intBitsToFloat(((Boolean) val) ? 1 : 0);
        } else if (val instanceof com.jme3.math.Vector2f && offset + 1 < data.length) {
            com.jme3.math.Vector2f v = (com.jme3.math.Vector2f) val;
            data[offset] = v.x;
            data[offset + 1] = v.y;
        } else if (val instanceof com.jme3.math.Vector3f && offset + 2 < data.length) {
            com.jme3.math.Vector3f v = (com.jme3.math.Vector3f) val;
            data[offset] = v.x;
            data[offset + 1] = v.y;
            data[offset + 2] = v.z;
        } else if (val instanceof com.jme3.math.Vector4f && offset + 3 < data.length) {
            com.jme3.math.Vector4f v = (com.jme3.math.Vector4f) val;
            data[offset] = v.x;
            data[offset + 1] = v.y;
            data[offset + 2] = v.z;
            data[offset + 3] = v.w;
        } else if (val instanceof ColorRGBA && offset + 3 < data.length) {
            ColorRGBA c = (ColorRGBA) val;
            data[offset] = c.r;
            data[offset + 1] = c.g;
            data[offset + 2] = c.b;
            data[offset + 3] = c.a;
        } else if (val instanceof Matrix4f && offset + 15 < data.length) {
            Matrix4f m = (Matrix4f) val;
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
        } else if (val instanceof com.jme3.math.Matrix3f && offset + 11 < data.length) {
            com.jme3.math.Matrix3f m = (com.jme3.math.Matrix3f) val;
            data[offset] = m.get(0, 0);
            data[offset + 1] = m.get(1, 0);
            data[offset + 2] = m.get(2, 0);
            data[offset + 4] = m.get(0, 1);
            data[offset + 5] = m.get(1, 1);
            data[offset + 6] = m.get(2, 1);
            data[offset + 8] = m.get(0, 2);
            data[offset + 9] = m.get(1, 2);
            data[offset + 10] = m.get(2, 2);
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
