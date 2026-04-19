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

    // =========================================================================
    // 【核心优化 1】基于 Shader 实例的源码与 Hash 前置缓存
    // 避免在渲染热路径（Hot Path）中每帧进行 String 拼接和 hashCode() 计算。
    // 使用 WeakHashMap 确保当 Shader 被 JME 卸载时，缓存能自动释放防止内存泄漏。
    // =========================================================================
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

    // =========================================================================
    // 【核心优化 2】贴图参数名映射的运行时缓存
    // 建立 Vulkan 标准化名称 (如 "colormap") 到 JME 原生名称 (如 "ColorMap") 的 O(1) 映射。
    // 彻底消灭 ParamBindingPlan.normalizeParamName 在每帧的 String 截取和转小写开销。
    // =========================================================================
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

    public DrawCmd build(Mesh mesh, int lod, int count, VertexBuffer[] instanceData, RendererStateSnapshot s) {
        if (s == null) {
            return build(mesh, lod, count, instanceData, null, null, null, null, null, null, null, null);
        }
        return build(mesh, lod, count, instanceData, s.shader, s.renderState, s.tex0, s.light, s.extra, s.material, s.wvp, s.color);
    }

    public DrawCmd build(Mesh mesh, int lod, int count, VertexBuffer[] instanceData,
            Shader currentShader, RenderState currentRenderState, Texture currentTex0, Texture currentLight, Texture currentExtra) {
        return build(mesh, lod, count, instanceData, currentShader, currentRenderState, currentTex0, currentLight, currentExtra, null, null, null);
    }

    private DrawCmd build(Mesh mesh, int lod, int count, VertexBuffer[] instanceData,
            Shader currentShader, RenderState currentRenderState, Texture currentTex0, Texture currentLight, Texture currentExtra,
            Material currentMaterial, Matrix4f overrideWvp, ColorRGBA overrideColor) {

        DrawCmd dc = new DrawCmd();

        dc.mesh = mesh;
        dc.lod = lod;
        dc.count = count;
        dc.instanceData = instanceData;
        dc.objectId = (mesh != null) ? System.identityHashCode(mesh) : 0;
        dc.renderState = currentRenderState;

        // 【应用优化 1】：极速获取 Shader Hash 与最终代码，0 字符串分配
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

        dc.wvpSnapshot = new Matrix4f();
        if (overrideWvp != null) {
            dc.wvpSnapshot.set(overrideWvp);
        } else {
            // 根据 JME 约定，从 Shader 的 Uniform 中提取
            Matrix4f wvp = getMat4Uniform(currentShader, "g_WorldViewProjectionMatrix");
            if (wvp != null) {
                dc.wvpSnapshot.set(wvp);
            } else {
                dc.wvpSnapshot.loadIdentity();
            }
        }

        dc.colorSnapshot = new ColorRGBA();
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

        // 提取 ExtraTex，常量字符串字面量查询
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
            dc.pipelineKey = VkPipelineKey.fromHashes(
                    dc.vertHash, dc.fragHash, dc.renderState, dc.variant, runtime.getDefaultPassKey()
            );
        }

        if (dc.pipelineKey != null) {
            ParamBindingPlan pbp = runtime.getPipelineParamBindingPlan(dc.pipelineKey);
            if (pbp != null && pbp.samplerByName != null && !pbp.samplerByName.isEmpty()) {
                int size = pbp.samplerByName.size();
                dc.customImageSlots = new ParamBindingPlan.BindingSlot[size];
                dc.customImageTextures = new Texture[size];
                dc.customImageCount = 0;

                for (Map.Entry<String, ParamBindingPlan.BindingSlot> e : pbp.samplerByName.entrySet()) {
                    String normName = e.getKey(); // 已在解析期规范化，直接复用
                    ParamBindingPlan.BindingSlot slot = e.getValue();
                    Texture tex = resolveTextureByParamName(currentMaterial, normName);
                    if (!isUsableTex2D(tex) && "extratex".equals(normName) && isUsableTex2D(extraTex)) {
                        tex = extraTex;
                    }
                    if (tex != null) {
                        dc.customImageSlots[dc.customImageCount] = slot;
                        dc.customImageTextures[dc.customImageCount] = tex;
                        dc.customImageCount++;
                    }
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

        return new CachedShaderData(
                extracted.vertSource, extracted.fragSource, 
                extracted.vertDefines, extracted.fragDefines,
                fVert, fFrag, vHash, fHash
        );
    }

    private static ExtractedShaderSources extractShaderSources(Shader shader) {
        if (shader == null || shader.getSources() == null) return null;
        String vert = null, frag = null, vertDefines = null, fragDefines = null;
        for (Shader.ShaderSource ss : shader.getSources()) {
            if (ss == null) continue;
            if (ss.getType() == Shader.ShaderType.Vertex) {
                vert = ss.getSource();
                vertDefines = ss.getDefines();
            } else if (ss.getType() == Shader.ShaderType.Fragment) {
                frag = ss.getSource();
                fragDefines = ss.getDefines();
            }
        }
        if (vert == null || frag == null) return null;
        return new ExtractedShaderSources(vert, frag, vertDefines, fragDefines);
    }

    private static String composeFinalShaderSource(String defines, String source) {
        if (source == null) return null;
        String d = normalizeShaderChunk(defines);
        String s = normalizeShaderChunk(source);
        if (d.isEmpty()) return s;
        if (s.isEmpty()) return d;

        int versionLineEnd = findLeadingVersionDirectiveLineEnd(s);
        if (versionLineEnd >= 0) {
            String firstPart = s.substring(0, versionLineEnd);
            String restPart = s.substring(versionLineEnd);
            StringBuilder out = new StringBuilder(s.length() + d.length() + 8);
            out.append(firstPart);
            if (!firstPart.endsWith("\n")) out.append('\n');
            out.append(d);
            if (!d.endsWith("\n")) out.append('\n');
            out.append(restPart);
            return out.toString();
        } else {
            StringBuilder out = new StringBuilder(s.length() + d.length() + 8);
            out.append(d);
            if (!d.endsWith("\n")) out.append('\n');
            out.append(s);
            return out.toString();
        }
    }

    private static String normalizeShaderChunk(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s.replace("\r\n", "\n").replace('\r', '\n');
        if (!out.isEmpty() && out.charAt(0) == '\ufeff') out = out.substring(1);
        return out;
    }

    private static int findLeadingVersionDirectiveLineEnd(String source) {
        if (source == null || source.isEmpty()) return -1;
        int len = source.length();
        int i = 0;
        while (i < len) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n') { i++; continue; }
            break;
        }
        if (i >= len) return -1;
        if (!source.startsWith("#version", i)) return -1;
        int lineEnd = source.indexOf('\n', i);
        if (lineEnd < 0) return len;
        return lineEnd + 1;
    }

    private static MaterialResolvePlan buildMaterialResolvePlan(Texture tex0, Texture light) {
        return new MaterialResolvePlan(tex0, light, !isUsableTex2D(tex0), !isUsableTex2D(light));
    }

    private static boolean isUsableTex2D(Texture t) {
        if (t == null) return false;
        com.jme3.texture.Image img = t.getImage();
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return false;
        return img.getData(0) != null;
    }

    private static ColorRGBA resolveColorSnapshot(Shader shader) {
        if (shader == null) return ColorRGBA.White.clone();
        com.jme3.shader.Uniform uColor = shader.getUniform("m_Color");
        if (uColor != null && uColor.getValue() instanceof ColorRGBA) return ((ColorRGBA) uColor.getValue()).clone();
        return ColorRGBA.White.clone();
    }

    private static boolean hasMaterialColor(Shader shader) {
        if (shader == null) return false;
        com.jme3.shader.Uniform uColor = shader.getUniform("m_Color");
        return uColor != null && (uColor.getValue() instanceof ColorRGBA);
    }

    private static Matrix4f getMat4Uniform(Shader shader, String name) {
        if (shader == null) return null;
        com.jme3.shader.Uniform u = shader.getUniform(name);
        if (u == null) return null;
        if (u.getValue() instanceof Matrix4f) return (Matrix4f) u.getValue();
        return null;
    }

    /**
     * 【应用优化 2】：基于 JME ListMap 和 VarType 的极速参数提取，配合 ConcurrentHashMap
     */
    private static Texture resolveTextureByParamName(Material mat, String normWanted) {
        if (mat == null || normWanted == null) return null;

        // 1. 极速路径：缓存命中，直接以 O(1) 提取真实的 JME 参数名
        String cachedJmeName = PARAM_NAME_CACHE.get(normWanted);
        if (cachedJmeName != null) {
            MatParam p = mat.getParam(cachedJmeName);
            if (p != null) {
                Texture t = textureFromMatParam(p);
                if (isUsableTex2D(t)) return t;
            }
            return null;
        }

        // 2. 首次退化路径：基于 JME 的底层 ListMap 做 0 迭代器分配的数组扫描
        ListMap<String, MatParam> paramsMap = mat.getParamsMap();
        int size = paramsMap.size();
        for (int i = 0; i < size; i++) {
            MatParam mp = paramsMap.getValue(i);
            if (mp == null) continue;
            
            // 快速前置剔除：不是纹理类型的参数直接跳过，根本不用提取和判断 Name
            if (mp.getVarType() != null && !mp.getVarType().isTextureType()) {
                continue;
            }

            String paramName = mp.getName();
            String n = ParamBindingPlan.normalizeParamName(paramName); // 仅在未命中时执行 1 次
            
            if (normWanted.equals(n)) {
                // 找到对应关系！记录到静态表，从此这个规范名的匹配变为 O(1)
                PARAM_NAME_CACHE.put(normWanted, paramName);
                Texture t = textureFromMatParam(mp);
                if (isUsableTex2D(t)) return t;
            }
        }
        return null;
    }

    /**
     * 适配 JME3 的 MatParamTexture 结构
     */
    private static Texture textureFromMatParam(MatParam p) {
        if (p == null || p.getValue() == null) return null;
        if (p instanceof MatParamTexture) {
            return ((MatParamTexture) p).getTextureValue();
        }
        Object v = p.getValue();
        if (v instanceof Texture) {
            return (Texture) v;
        }
        return null;
    }
}