// 负责定义并验证 Minecraft 26.2 MMD RenderPipeline 变体。
package com.shiroha.mmdskin.client.gpu;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.shiroha.mmdskin.bridge.render.NativeRenderDataPort;
import com.shiroha.mmdskin.client.draw.PipelineVariant;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 26.2 的 MMD RenderPipeline 变体集合。
 *
 * <p><b>深度比较方向</b>：与 26.3 一样用 reversed-Z（近平面 = 1.0、远平面 = 0.0），
 * 深度测试必须用 {@code GREATER_THAN_OR_EQUAL}——vanilla 的所有深度管线一律如此。
 *
 * <p><b>与 26.3 的差别</b>（本文件是 26.2 专用版本）：
 * <ul>
 *   <li>26.2 的 GPU 渲染 API 在 {@code com.mojang.blaze3d.*}（26.3 搬到了
 *       {@code com.mojang.renderpearl.api.*}）；</li>
 *   <li>26.2 的 {@code ShaderSource} 是函数式接口 {@code String get(Identifier, ShaderType)}，
 *       include 由 MC 自己的 {@link GlslPreprocessor} 处理（26.3 是原生回调式接口）；</li>
 *   <li>26.2 编译管线用 {@code GpuDevice.precompilePipeline(pipeline, shaderSource)}，
 *       绘制时 {@code RenderPass.setPipeline} 收的是 {@link RenderPipeline} 本身。</li>
 * </ul>
 */
public final class MmdRenderPipelines {
    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * MMD 自有 uniform 块名。uniform 是 std140 uniform block，
     * 成员顺序必须与 {@code MmdGpuDrawDevice} 写入的顺序完全一致。
     */
    public static final String MMD_UNIFORMS_BLOCK = "MmdUniforms";
    /** GPU 蒙皮骨骼调色板块名，固定 48 个 mat4。 */
    public static final String MMD_BONES_BLOCK = "MmdBones";
    /** GPU 蒙皮调色板最大骨骼数，与 native 侧保持一致。 */
    public static final int MAX_PALETTE_BONES = NativeRenderDataPort.MAX_PALETTE_BONES;

    private static final Identifier SHADER = id("core/mmd_entity");
    /** 原版 include 所在目录（相对资源路径，列举时不能带结尾斜杠）。 */
    private static final String INCLUDE_DIR = "shaders/include";
    /** 原版 include 的标识符前缀。 */
    private static final String INCLUDE_PREFIX = INCLUDE_DIR + "/";

    private static final BindGroupLayout MMD_UNIFORMS_LAYOUT = BindGroupLayout.builder()
            .withUniform(MMD_UNIFORMS_BLOCK, UniformType.UNIFORM_BUFFER)
            .build();
    private static final BindGroupLayout MMD_BONES_LAYOUT = BindGroupLayout.builder()
            .withUniform(MMD_BONES_BLOCK, UniformType.UNIFORM_BUFFER)
            .build();

    /**
     * 管线只声明 uniform 块绑定：ProjMat 来自 Minecraft 的 Projection 块，
     * 光照方向来自 Lighting 块，雾参数来自 Fog 块。
     *
     * <p>这里**故意不声明** {@code BindGroupLayouts.DYNAMIC_TRANSFORMS}：
     * vanilla 的动态变换槽位是每帧预算，MMD 每帧多占一个槽位就会把 DynamicGpuData
     * 的 per-frame 容量打爆，触发 vanilla 在帧内重建 transform UBO 并在下一帧
     * 无 fence 等待地释放仍在飞行中的旧 buffer —— 原生崩溃。
     * 全局视图矩阵改为写进 MMD 自有的 {@code MmdUniforms} 块。
     */
    private static final RenderPipeline.Snippet BASE = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.LIGHTING)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(MMD_UNIFORMS_LAYOUT)
            .withVertexShader(SHADER)
            .withFragmentShader(SHADER)
            .buildSnippet();

    private static final RenderPipeline.Snippet CPU_BASE = RenderPipeline.builder(BASE)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL)
            .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
            .buildSnippet();

    private static final RenderPipeline.Snippet GPU_BASE = RenderPipeline.builder(BASE)
            .withBindGroupLayout(MMD_BONES_LAYOUT)
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
            // GPU 蒙皮分支（骨骼调色板 + 顶点里的骨骼索引/权重）靠这个宏开启。
            .withShaderDefine("MMD_GPU_SKINNING")
            .buildSnippet();

    private static final Map<PipelineVariant, RenderPipeline> CPU_PIPELINES = createPipelines(false);
    private static final Map<PipelineVariant, RenderPipeline> GPU_PIPELINES = createPipelines(true);
    /**
     * 当前可用的已编译管线快照。
     *
     * <p>用不可变快照 + volatile 原子替换，而不是可变 HashMap：这份表由资源重载线程写、
     * 由渲染线程每帧读，普通 HashMap 在并发写下可能让渲染线程读到半更新状态。
     */
    private static volatile Map<RenderPipeline, CompiledRenderPipeline> compiledPipelines = Map.of();
    /** 记录已经报过"管线缺失"的键，避免每帧刷日志。 */
    private static final Set<String> loggedMissingPipelines = ConcurrentHashMap.newKeySet();
    private static volatile boolean ready;
    private static volatile boolean gpuReady;

    private MmdRenderPipelines() {
    }

    public static RenderPipeline pipeline(PipelineVariant variant) {
        return pipeline(variant, false);
    }

    public static RenderPipeline pipeline(PipelineVariant variant, boolean gpuSkinning) {
        RenderPipeline pipeline = (gpuSkinning ? GPU_PIPELINES : CPU_PIPELINES).get(variant);
        if (pipeline == null) {
            throw new IllegalArgumentException("unsupported MMD pipeline variant: " + variant);
        }
        return pipeline;
    }

    /**
     * 取得绘制用的管线。
     *
     * <p>26.2 的 {@code RenderPass.setPipeline} 收的是 {@link RenderPipeline} 本身
     * （26.3 才改成收已编译对象），未编译成功时返回 {@code null}，调用方应跳过该次绘制。
     */
    public static RenderPipeline compiledPipeline(PipelineVariant variant, boolean gpuSkinning) {
        RenderPipeline pipeline = pipeline(variant, gpuSkinning);
        return compiledPipelines.containsKey(pipeline) ? pipeline : null;
    }

    /**
     * 管线不可用时记一条日志（每种组合只记一次）。
     * 之前这里是静默 return，表现为"MMD 模型偶尔/完全不出现且没有任何报错"。
     */
    public static void logMissingPipeline(PipelineVariant variant, boolean gpuSkinning) {
        String key = variant + "/" + gpuSkinning;
        if (loggedMissingPipelines.add(key)) {
            LOGGER.error("MMD 管线不可用，已跳过该次绘制: {}（着色器编译失败或管线缓存未就绪）", key);
        }
    }

    public static List<RenderPipeline> all() {
        java.util.ArrayList<RenderPipeline> pipelines = new java.util.ArrayList<>(CPU_PIPELINES.values());
        pipelines.addAll(GPU_PIPELINES.values());
        return List.copyOf(pipelines);
    }

    public static boolean validateAndActivate(GpuDevice device) {
        return validateAndActivate(device, (ShaderSource) null);
    }

    public static boolean validateAndActivate(GpuDevice device, ShaderSource shaderSource) {
        Map<RenderPipeline, CompiledRenderPipeline> compiled = new HashMap<>();
        boolean valid = shaderSource != null
                && compile(device, shaderSource, CPU_PIPELINES.values(), compiled);
        boolean validGpu = shaderSource != null
                && compile(device, shaderSource, GPU_PIPELINES.values(), compiled);
        // 先原子发布新快照，再让渲染线程看到：反过来的话渲染线程可能拿到半更新状态。
        compiledPipelines = Map.copyOf(compiled);
        ready = valid;
        gpuReady = validGpu;
        if (!valid) {
            LOGGER.error("MMD 管线编译失败，本次回退原版渲染（检查 mmdskin 着色器是否与 MC 26.2 的 UBO 规范匹配）");
        }
        return valid;
    }

    /**
     * 兼容只提供着色器正文的调用形态：26.2 的 {@code ShaderSource} 只有正文，
     * include 需要自己预处理，缺失 include 时管线会编译失败并回退原版渲染。
     */
    public static boolean validateAndActivate(
            GpuDevice device,
            BiFunction<Identifier, ShaderType, String> shaderSource) {
        return validateAndActivate(device,
                shaderSource == null ? null : (ShaderSource) shaderSource::apply);
    }

    /**
     * 基于资源管理器构建 26.2 的着色器源。
     *
     * <p>26.2 的 include 由 MC 自己的 {@link GlslPreprocessor} 处理：
     * {@code #moj_import <minecraft:light.glsl>} 会被替换成
     * {@code minecraft:shaders/include/light.glsl} 的内容。
     */
    public static ShaderSource shaderSource(ResourceManager resourceManager) {
        Map<Identifier, Resource> includes = listIncludes(resourceManager);
        return (id, type) -> {
            Identifier file = type.idConverter().idToFile(id);
            Resource resource = lastResource(resourceManager, file);
            if (resource == null) {
                // 原版这里是返回 null（PipelineBuilder 会打 "Couldn't find source for ..."），
                // 抛异常反而会让整条管线编译链炸掉。
                LOGGER.error("缺少 MMD 着色器资源: {}", file);
                return null;
            }
            try {
                String text = readAll(resource);
                GlslPreprocessor preprocessor = createPreprocessor(includes, file);
                return String.join("\n", preprocessor.process(text));
            } catch (IOException exception) {
                LOGGER.error("读取 MMD 着色器失败: {}", file, exception);
                return null;
            }
        };
    }

    public static void deactivate() {
        ready = false;
        gpuReady = false;
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isGpuReady() {
        return ready && gpuReady;
    }

    private static boolean compile(GpuDevice device, ShaderSource shaderSource,
                                   Collection<RenderPipeline> pipelines,
                                   Map<RenderPipeline, CompiledRenderPipeline> compiled) {
        boolean valid = true;
        for (RenderPipeline pipeline : pipelines) {
            try {
                CompiledRenderPipeline result = device.precompilePipeline(pipeline, shaderSource);
                if (result == null || !result.isValid()) {
                    valid = false;
                    continue;
                }
                compiled.put(pipeline, result);
            } catch (RuntimeException exception) {
                LOGGER.error("MMD 管线编译失败: {}", pipeline.getLocation(), exception);
                valid = false;
            }
        }
        return valid;
    }

    private static Map<PipelineVariant, RenderPipeline> createPipelines(boolean gpu) {
        EnumMap<PipelineVariant, RenderPipeline> pipelines = new EnumMap<>(PipelineVariant.class);
        pipelines.put(PipelineVariant.OPAQUE, build("opaque", true, true, true, gpu));
        pipelines.put(PipelineVariant.OPAQUE_DOUBLE_SIDED, build("opaque_double_sided", false, true, true, gpu));
        pipelines.put(PipelineVariant.TRANSLUCENT, build("translucent", true, true, false, gpu));
        pipelines.put(PipelineVariant.TRANSLUCENT_DOUBLE_SIDED, build("translucent_double_sided", false, true, false, gpu));
        pipelines.put(PipelineVariant.TOON, buildToon("toon", true, true, true, gpu));
        pipelines.put(PipelineVariant.TOON_DOUBLE_SIDED, buildToon("toon_double_sided", false, true, true, gpu));
        pipelines.put(PipelineVariant.TOON_TRANSLUCENT, buildToon("toon_translucent", true, true, false, gpu));
        pipelines.put(PipelineVariant.TOON_TRANSLUCENT_DOUBLE_SIDED,
                buildToon("toon_translucent_double_sided", false, true, false, gpu));
        RenderPipeline.Snippet base = gpu ? GPU_BASE : CPU_BASE;
        String prefix = gpu ? "mmd_gpu_" : "mmd_";
        pipelines.put(PipelineVariant.TOON_OUTLINE, RenderPipeline.builder(base)
                .withLocation(id("pipeline/" + prefix + "toon_outline"))
                .withShaderDefine("MMD_OUTLINE")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(true)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .build());
        pipelines.put(PipelineVariant.GLOWING, RenderPipeline.builder(base)
                .withLocation(id("pipeline/" + prefix + "glowing"))
                .withShaderDefine("MMD_EMISSIVE")
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .build());
        return pipelines;
    }

    private static RenderPipeline build(String name, boolean cull, boolean blend, boolean depthWrite, boolean gpu) {
        RenderPipeline.Builder builder = RenderPipeline.builder(gpu ? GPU_BASE : CPU_BASE)
                .withLocation(id("pipeline/" + (gpu ? "mmd_gpu_" : "mmd_") + name))
                .withCull(cull)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, depthWrite))
                .withColorTargetState(blend
                        ? new ColorTargetState(BlendFunction.TRANSLUCENT)
                        : ColorTargetState.DEFAULT);
        return builder.build();
    }

    private static RenderPipeline buildToon(String name, boolean cull, boolean blend, boolean depthWrite, boolean gpu) {
        RenderPipeline.Builder builder = RenderPipeline.builder(gpu ? GPU_BASE : CPU_BASE)
                .withLocation(id("pipeline/" + (gpu ? "mmd_gpu_" : "mmd_") + name))
                .withShaderDefine("MMD_TOON")
                .withCull(cull)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, depthWrite))
                .withColorTargetState(blend
                        ? new ColorTargetState(BlendFunction.TRANSLUCENT)
                        : ColorTargetState.DEFAULT);
        return builder.build();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("mmdskin", path);
    }

    // ========================================================================
    // 26.2 的着色器 include 处理
    // ========================================================================

    /** 收集原版的 include 文件（{@code assets/minecraft/shaders/include/*.glsl}）。 */
    private static Map<Identifier, Resource> listIncludes(ResourceManager resourceManager) {
        try {
            return resourceManager.listResources(INCLUDE_DIR, id -> true);
        } catch (RuntimeException exception) {
            LOGGER.error("列举原版着色器 include 失败", exception);
            return Map.of();
        }
    }

    /**
     * 复刻原版 {@code ShaderManager.createPreprocessor} 的 import 解析。
     *
     * <p>原版把 {@code <minecraft:light.glsl>} 解析成
     * {@code minecraft:shaders/include/light.glsl}；同一个 include 只展开一次
     * （第二次返回 null，原版语义是"已经导入过了"）。
     */
    private static GlslPreprocessor createPreprocessor(Map<Identifier, Resource> includes, Identifier parent) {
        Set<Identifier> imported = new HashSet<>();
        return new GlslPreprocessor() {
            @Override
            public String applyImport(boolean flag, String name) {
                Identifier target = resolveImport(name, parent, includes);
                if (target == null) {
                    return "Could not open GLSL import " + name;
                }
                if (!imported.add(target)) {
                    // 原版语义：已经导入过，返回 null 表示"不重复展开"。
                    return null;
                }
                Resource resource = includes.get(target);
                if (resource == null) {
                    return "Could not open GLSL import " + target;
                }
                try (Reader reader = resource.openAsReader()) {
                    return IOUtils.toString(reader);
                } catch (IOException exception) {
                    return "Could not open GLSL import " + target + ": " + exception.getMessage();
                }
            }
        };
    }

    /** 解析 import 名字：先按原版的绝对写法，再退回相对当前文件的写法。 */
    private static Identifier resolveImport(String name, Identifier parent, Map<Identifier, Resource> includes) {
        try {
            Identifier absolute = Identifier.parse(name).withPrefix(INCLUDE_PREFIX);
            if (includes.containsKey(absolute)) {
                return absolute;
            }
        } catch (RuntimeException ignored) {
            // 名字不合法就试相对写法
        }
        try {
            Identifier relative = parent.withPath(path -> {
                int slash = path.lastIndexOf('/');
                return (slash < 0 ? "" : path.substring(0, slash + 1)) + name;
            });
            if (includes.containsKey(relative)) {
                return relative;
            }
        } catch (RuntimeException ignored) {
            // 两种写法都不合法
        }
        return null;
    }

    private static String readAll(Resource resource) throws IOException {
        try (Reader reader = resource.openAsReader()) {
            return IOUtils.toString(reader);
        }
    }

    private static Resource lastResource(ResourceManager resourceManager, Identifier file) {
        List<Resource> stack = resourceManager.getResourceStack(file);
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }
}
