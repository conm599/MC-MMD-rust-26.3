package com.shiroha.mmdskin.player.ragdoll;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.bridge.runtime.NativeAnimationBridgeHolder;
import com.shiroha.mmdskin.bridge.runtime.NativeRagdollPort;
import com.shiroha.mmdskin.bridge.runtime.NativeScenePort;
import com.shiroha.mmdskin.config.ConfigManager;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.model.runtime.ModelRequestKey;
import com.shiroha.mmdskin.player.runtime.EntityAnimState;
import com.shiroha.mmdskin.render.bootstrap.ClientRenderRuntime;
import com.shiroha.mmdskin.render.scene.RenderScene;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 死亡布娃娃管理器（客户端，从零实现）。
 *
 * <p>核心设计约定：
 * <ul>
 *   <li><b>每次死亡一条独立记录</b>：{@code corpses} 用自增 corpseId 做 key，不用实体 UUID
 *       （玩家复活后 UUID 不变，按 UUID 去重会导致同一玩家第二次死亡不创建尸体）。
 *       同时维护 entity 对象身份 → corpseId 索引，渲染抑制查询 O(1)。</li>
 *   <li><b>地面用方块射线检测</b>：从尸体位置向下 {@link ClipContext} 打射线取真实承重面，
 *       不用 Heightmap——高度图只认"该列最高方块"，悬洞/隧道/树叶/水面都会误判，
 *       导致挖掉尸体下方方块后尸体仍浮在虚拟地板上。</li>
 *   <li><b>实体引用弱持有</b>：失败冷却表用 {@link WeakHashMap}，实体被回收后条目自动消失，
 *       不泄漏实体对象。</li>
 *   <li>布娃娃启动失败进 5 秒冷却，不每帧重试。</li>
 * </ul>
 */
public final class RagdollManager {
    private static final Logger logger = LogManager.getLogger();

    private static final RagdollManager INSTANCE = new RagdollManager();

    /** 世界坐标 ↔ 模型局部单位换算（与引擎 UNIT_TO_M 一致）。 */
    private static final float MODEL_SCALE = 0.09f;
    private static final float DEGREES_TO_RADIANS = (float) Math.PI / 180.0F;
    private static final Vector3f ZERO_TRANSLATION = new Vector3f(0.0f, 0.0f, 0.0f);

    /** 地面高度复检节流（毫秒）。 */
    private static final long GROUND_CHECK_INTERVAL_MS = 1000;
    /** 模型缓存续命 / 重载检测间隔（毫秒）。 */
    private static final long CACHE_TOUCH_INTERVAL_MS = 5000;
    /** 布娃娃启动失败后的重试冷却（毫秒）。 */
    private static final long FAILED_RETRY_COOLDOWN_MS = 5000;
    /** 尸体渲染距离剔除（方块，平方）。 */
    private static final double RENDER_CULL_DISTANCE_SQ = 64.0 * 64.0;
    /** 地面射线最大下探深度（方块）。 */
    private static final double GROUND_RAY_MAX_DEPTH = 32.0;

    /** 每次死亡一个独立记录；key = 自增 corpseId。 */
    private final Map<Long, Corpse> corpses = new LinkedHashMap<>();
    private long nextCorpseId = 1L;

    /** entity 对象身份 → corpseId 索引（renderCorpses/抑制查询 O(1)；仅客户端线程访问）。 */
    private final IdentityHashMap<LivingEntity, Long> corpseIdByEntity = new IdentityHashMap<>();

    /** 布娃娃启动失败的实体（5 秒内不重试；WeakHashMap 随实体回收自动清理）。 */
    private final WeakHashMap<LivingEntity, Long> failedAttempts = new WeakHashMap<>();

    private volatile NativeRagdollPort ragdollPort;
    private volatile NativeScenePort scenePort;

    // ======== 死亡界面劫持（相机） ========
    private boolean wasLocalDead;
    private CameraType savedCameraType;
    private boolean cameraHijacked;

    // ======== 死亡观战相机（单击进入：鼠标环视 + 滚轮缩放） ========
    private boolean deathCamObserve;
    private float deathCamYaw;
    private float deathCamPitch = 25.0f;
    private double deathCamDistance = 4.5;

    private static final class Corpse {
        final long id;
        final UUID subjectId;
        /** 精确绑定到死亡时的实体对象；复活后是新的实体对象，即使 UUID 相同也不互相抑制。 */
        final LivingEntity sourceEntity;
        /** 尸体专用缓存键（与活人模型分离，每次死亡独立）。 */
        final ModelRequestKey corpseKey;
        final long deathTimeMs;
        /** 尸体模型实例（异步加载完成后填充；模型重载时被替换）。 */
        ManagedModel model;
        /** 布娃娃启动参数快照（[0] 随地面复检/位置跟踪动态刷新）。 */
        final float[] pendingParams;
        /** 尸体世界坐标（死亡动画窗口内跟踪实体，布娃娃启用后冻结为模型原点）。 */
        double x;
        double y;
        double z;
        float yawDegrees;
        /** 当前地面世界高度（射线检测）。 */
        double groundWorldY;
        long lastGroundCheckMs;
        long lastCacheTouchMs;
        boolean renderReady;
        boolean ragdollReady;
        boolean deathAnimStarted;
        // ===== 死亡实体跟踪（登记即开始，直到启物理交接） =====
        /** 上一 tick 的实体位置（计算交接速度用）。 */
        double lastTickX;
        double lastTickY;
        double lastTickZ;
        boolean hasLastTickPos;
        /** 实体速度 EMA（格/tick）——位置差分比 getDeltaMovement 更能代表"玩家当前真实运动"。 */
        double emaVelX;
        double emaVelY;
        double emaVelZ;
        int velocitySamples;
        /** 尸体模型就绪（首次渲染前）的时刻；用于等动画真正应用到骨骼。 */
        long readyMs;
        /**
         * 尸体模型实际渲染过的帧数。引擎在 render() 内部才执行动画更新，
         * 就绪后至少渲染过几帧，骨骼才会离开绑定姿势（T-pose）——
         * 布娃娃必须等到此时再从当前姿势构建，否则刚体按 T-pose 摊开。
         */
        int renderedFrames;
        /** 启物理成功的时刻（物理保持时长计时起点）。 */
        long ragdollReadyMs;
        /** 物理已冻结（保持期到点）：姿势保持、跳过布娃娃步进与 MMD 物理。 */
        boolean physicsFrozen;

        Corpse(long id, UUID subjectId, LivingEntity sourceEntity, ModelRequestKey corpseKey,
               long deathTimeMs, float[] pendingParams,
               double x, double y, double z, float yawDegrees, double groundWorldY) {
            this.id = id;
            this.subjectId = subjectId;
            this.sourceEntity = sourceEntity;
            this.corpseKey = corpseKey;
            this.deathTimeMs = deathTimeMs;
            this.pendingParams = pendingParams;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yawDegrees = yawDegrees;
            this.groundWorldY = groundWorldY;
            this.lastGroundCheckMs = deathTimeMs;
            this.lastCacheTouchMs = 0L;
        }
    }

    private RagdollManager() {
    }

    public static RagdollManager getInstance() {
        return INSTANCE;
    }

    /** 运行时装配（ClientRenderRuntime 组合根调用一次）。 */
    public void configureRuntimeCollaborators(NativeRagdollPort ragdollPort, NativeScenePort scenePort) {
        this.ragdollPort = ragdollPort;
        this.scenePort = scenePort;
    }

    // ========================================================================
    // 死亡触发
    // ========================================================================

    /**
     * 尝试为死亡实体启动布娃娃管线（渲染层死亡解析点调用，每帧可重复调用，内部幂等）。
     *
     * @return true = 该实体的尸体管线已登记（实体路径保持死亡动画直到布娃娃接管）
     */
    public boolean tryEnterRagdoll(LivingEntity entity, ManagedModel entityModel) {
        if (!ConfigManager.isRagdollEnabled() || ragdollPort == null) {
            return false;
        }
        if (entity == null || entityModel == null) {
            return false;
        }
        if (!entity.isDeadOrDying()) {
            return false;
        }
        if (corpseIdByEntity.containsKey(entity)) {
            return true;
        }
        Long lastFail = failedAttempts.get(entity);
        if (lastFail != null && System.currentTimeMillis() - lastFail < FAILED_RETRY_COOLDOWN_MS) {
            return false;
        }

        UUID subjectId = entity.getUUID();
        double groundWorldY = sampleGroundY(entity.level(), entity.getX(), entity.getY(), entity.getZ(), entity.getY());
        float[] params = buildRagdollParams(entity, groundWorldY);

        // 尸体专用缓存键：与活人模型彻底分离，且每次死亡独立（同玩家两次死亡 = 两个缓存键）
        ModelRequestKey originKey = entityModel.requestKey();
        long corpseId = nextCorpseId++;
        ModelRequestKey corpseKey = new ModelRequestKey(
                originKey.subjectKind(),
                subjectId + "|corpse|" + corpseId,
                originKey.modelName());

        Corpse corpse = new Corpse(
                corpseId, subjectId, entity, corpseKey,
                System.currentTimeMillis(), params,
                entity.getX(), entity.getY(), entity.getZ(),
                entity.yBodyRot,
                groundWorldY);
        corpses.put(corpseId, corpse);
        corpseIdByEntity.put(entity, corpseId);
        evictOverflow();

        logger.info("[Ragdoll] 尸体登记: id={} {} (model={}, 当前 {} 具)",
                corpseId, subjectId, originKey.modelName(), corpses.size());
        return true;
    }

    /**
     * 布娃娃启动参数快照。契约见 {@link com.shiroha.mmdskin.bridge.runtime.NativeRagdollPort}。
     */
    private float[] buildRagdollParams(LivingEntity entity, double groundWorldY) {
        float[] params = new float[16];
        // [0] 模型局部地面高度（实体脚底相对模型原点）
        params[0] = (float) ((groundWorldY - entity.getY()) / MODEL_SCALE);
        // [1-3] 死亡初速度（世界 m/s；引擎按 yaw 旋转换算到模型局部空间）。
        // 注意单位：getDeltaMovement 是"格/tick"，契约要"米/秒"，1 tick = 1/20 秒 → ×20。
        // 漏乘 20 就是"布娃娃没有惯性"的根因（速度只剩 1/20，看起来原地瘫倒）。
        Vec3 delta = entity.getDeltaMovement();
        params[1] = (float) (delta.x * 20.0);
        params[2] = (float) (delta.y * 20.0);
        params[3] = (float) (delta.z * 20.0);
        // [4] yaw（弧度，速度换算用）
        params[4] = entity.yBodyRot * DEGREES_TO_RADIANS;
        // [5] 厚度 / [6] 摩擦 / [7] 恢复 / [8] 线阻尼 / [9] 角阻尼：0 = 引擎默认（lab 手感）
        // [10] 重力 Y：0 = 引擎默认
        // [11] 关节强度（用户配置；引擎侧默认 1.35）
        params[11] = ConfigManager.getRagdollJointStrength();
        // [12] 总质量 / [13] 手臂限位：0 = 引擎默认
        // [14] 世界→模型换算
        params[14] = MODEL_SCALE;
        return params;
    }

    // ========================================================================
    // 每 tick：模型就绪检测 + 保留策略 + 相机劫持
    // ========================================================================

    public void tick(Minecraft minecraft) {
        if (corpses.isEmpty()) {
            wasLocalDead = false;
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            restoreCamera(minecraft);
            clearAll();
            return;
        }
        long now = System.currentTimeMillis();

        Iterator<Corpse> it = corpses.values().iterator();
        while (it.hasNext()) {
            Corpse corpse = it.next();

            // 保留策略逐出
            if (shouldEvictCorpse(minecraft, corpse, now)) {
                releaseCorpse(corpse);
                it.remove();
                continue;
            }

            // 模型就绪检测（异步加载完成 → 接管渲染并播死亡动画）；周期性 acquire 兼作续命 + 重载检测
            if (corpse.model == null || now - corpse.lastCacheTouchMs > CACHE_TOUCH_INTERVAL_MS) {
                corpse.lastCacheTouchMs = now;
                touchCache(corpse);
            }

            // 登记即跟踪死亡实体（击退/下落还在飞）：尸体模型出现的位置、启物理的交接速度都来自这里
            if (!corpse.ragdollReady
                    && corpse.sourceEntity != null && !corpse.sourceEntity.isRemoved()) {
                LivingEntity tracked = corpse.sourceEntity;
                corpse.x = tracked.getX();
                corpse.y = tracked.getY();
                corpse.z = tracked.getZ();
                corpse.yawDegrees = tracked.yBodyRot;
                corpse.pendingParams[0] = (float) ((corpse.groundWorldY - corpse.y) / MODEL_SCALE);

                if (corpse.hasLastTickPos) {
                    double dvx = corpse.x - corpse.lastTickX;
                    double dvy = corpse.y - corpse.lastTickY;
                    double dvz = corpse.z - corpse.lastTickZ;
                    corpse.emaVelX = corpse.emaVelX * 0.5 + dvx * 0.5;
                    corpse.emaVelY = corpse.emaVelY * 0.5 + dvy * 0.5;
                    corpse.emaVelZ = corpse.emaVelZ * 0.5 + dvz * 0.5;
                    corpse.velocitySamples++;
                }
                corpse.lastTickX = corpse.x;
                corpse.lastTickY = corpse.y;
                corpse.lastTickZ = corpse.z;
                corpse.hasLastTickPos = true;
            }

            // 地面高度复检（射线检测，节流；模型就绪即生效；冻结后无意义，跳过省性能）
            if (corpse.renderReady && !corpse.physicsFrozen
                    && now - corpse.lastGroundCheckMs > GROUND_CHECK_INTERVAL_MS) {
                corpse.lastGroundCheckMs = now;
                double groundY = sampleGroundY(minecraft.level, corpse.x, corpse.y, corpse.z, corpse.groundWorldY);
                if (Math.abs(groundY - corpse.groundWorldY) > 0.01) {
                    logger.info("[Ragdoll] 地面高度变化: id={} {} -> {}", corpse.id, corpse.groundWorldY, groundY);
                    corpse.groundWorldY = groundY;
                    corpse.pendingParams[0] = (float) ((groundY - corpse.y) / MODEL_SCALE);
                    if (corpse.ragdollReady && corpse.model.modelInstance() != null) {
                        long handle = corpse.model.modelInstance().getModelHandle();
                        ragdollPort.setRagdollGround(handle, corpse.pendingParams[0]);
                    }
                }
            }

            // 物理保持期到点 → 冻结（性能优化）：跳过布娃娃步进 + 关 MMD 物理（发饰/裙摆），
            // 接管骨全局矩阵停在最后一次物理姿势，尸体可见但不再消耗物理 CPU。
            if (corpse.ragdollReady && !corpse.physicsFrozen && corpse.model != null) {
                int keepSeconds = ConfigManager.getRagdollPhysicsKeepSeconds();
                if (keepSeconds > 0 && now - corpse.ragdollReadyMs > keepSeconds * 1000L) {
                    ModelInstance frozenInstance = corpse.model.modelInstance();
                    if (frozenInstance != null && frozenInstance.getModelHandle() != 0L) {
                        ragdollPort.setModelPhysicsEnabled(frozenInstance.getModelHandle(), false);
                        ragdollPort.setRagdollFrozen(frozenInstance.getModelHandle(), true);
                        corpse.physicsFrozen = true;
                        logger.info("[Ragdoll] 物理保持期到点，尸体冻结: id={} ({} s)", corpse.id, keepSeconds);
                    }
                }
            }

            // 死亡动画窗口结束 → 从当前姿势启物理。
            // 前置：模型至少实际渲染过 3 帧（动画已应用到骨骼，离开 T-pose）；
            // 兜底：2 秒后无条件启用，防止尸体在视野外永不启物理。
            if (corpse.renderReady && !corpse.ragdollReady
                    && corpse.readyMs > 0L
                    && (corpse.renderedFrames >= 3
                        || now - corpse.readyMs >= 2000L)) {
                long deathAnimMs = (long) (ConfigManager.getRagdollDeathAnimSeconds() * 1000.0f);
                if (now - corpse.deathTimeMs >= deathAnimMs) {
                    if (!enableRagdollOn(corpse)) {
                        releaseCorpse(corpse);
                        it.remove();
                    }
                }
            }
        }

        evictOverflow();
        tickCameraHijack(minecraft);
    }

    private boolean shouldEvictCorpse(Minecraft minecraft, Corpse corpse, long now) {
        String keepMode = ConfigManager.getCorpseKeepMode();
        switch (keepMode) {
            case "SECONDS" -> {
                if (now - corpse.deathTimeMs > ConfigManager.getCorpseKeepSeconds() * 1000L) {
                    return true;
                }
            }
            case "FOREVER" -> {
                // 不按时间/距离逐出，仅受数量上限约束
                return false;
            }
            // NEARBY（默认）
            default -> {
                if (minecraft.player != null) {
                    double radius = ConfigManager.getCorpseKeepRadius();
                    double dx = corpse.x - minecraft.player.getX();
                    double dy = corpse.y - minecraft.player.getY();
                    double dz = corpse.z - minecraft.player.getZ();
                    if (dx * dx + dy * dy + dz * dz > radius * radius) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 获取/续命尸体模型；检测到重载（实例变化）时重启死亡动画并待布娃娃重启。 */
    private void touchCache(Corpse corpse) {
        try {
            ManagedModel refreshed = ClientRenderRuntime.get().modelRepository().acquire(corpse.corpseKey);
            if (refreshed == null || refreshed.modelInstance() == null) {
                return;
            }
            if (corpse.model == null) {
                // 首次就绪：接管渲染，播死亡动画
                corpse.model = refreshed;
                corpse.renderReady = true;
                corpse.readyMs = System.currentTimeMillis();
                startCorpseDeathAnim(corpse);
                logger.info("[Ragdoll] 尸体模型就绪: id={} ({} ms)",
                        corpse.id, System.currentTimeMillis() - corpse.deathTimeMs);
            } else if (corpse.model != refreshed) {
                // 模型被重载（实例对象变化）：重启死亡动画，布娃娃在新实例上重启
                if (corpse.ragdollReady) {
                    ragdollPort.disableRagdoll(corpse.model.modelInstance().getModelHandle());
                    corpse.ragdollReady = false;
                    corpse.deathAnimStarted = false;
                }
                corpse.model = refreshed;
                startCorpseDeathAnim(corpse);
                logger.info("[Ragdoll] 尸体模型被重载，重启布娃娃: id={}", corpse.id);
            }
        } catch (Exception e) {
            logger.warn("[Ragdoll] 尸体模型获取失败: id={}", corpse.id, e);
        }
    }

    /** 播放死亡动画并按已流逝时间对齐进度（30fps）。 */
    private void startCorpseDeathAnim(Corpse corpse) {
        if (corpse.deathAnimStarted) {
            return;
        }
        corpse.deathAnimStarted = true;
        ModelInstance instance = corpse.model.modelInstance();
        long dieAnim = corpse.model.animationLibrary().animation(EntityAnimState.State.Die.propertyName);
        if (dieAnim == 0L) {
            return;
        }
        instance.changeAnim(dieAnim, 0);
        instance.setLayerLoop(0, false);
        float elapsedSeconds = (System.currentTimeMillis() - corpse.deathTimeMs) / 1000.0f;
        NativeAnimationBridgeHolder.get().seekLayer(instance.getModelHandle(), 0L, elapsedSeconds * 30.0f);
    }

    /** 启物理；false = 启动失败（记冷却并逐出该尸体）。 */
    private boolean enableRagdollOn(Corpse corpse) {
        ModelInstance instance = corpse.model.modelInstance();
        if (instance == null || instance.getModelHandle() == 0L) {
            return false;
        }
        float[] params = corpse.pendingParams.clone();
        // 交接速度：优先用位置差分 EMA——它是玩家死亡后仍在飞行的"当前真实速度"，
        // 尸体全程跟踪实体，启物理瞬间以此接管轨迹，不分离、不丢失惯性。
        // （×20：EMA 是格/tick，FFI 契约要米/秒）
        // 实体已消失（mob 死亡 20 tick 后被移除）时退回登记时的速度快照。
        if (corpse.velocitySamples >= 2) {
            params[1] = (float) (corpse.emaVelX * 20.0);
            params[2] = (float) (corpse.emaVelY * 20.0);
            params[3] = (float) (corpse.emaVelZ * 20.0);
        }
        LivingEntity source = corpse.sourceEntity;
        if (source != null && !source.isRemoved()) {
            params[4] = source.yBodyRot * DEGREES_TO_RADIANS;
        }
        boolean ok = ragdollPort.enterRagdoll(instance.getModelHandle(), params);
        if (!ok) {
            logger.info("[Ragdoll] 布娃娃启动失败（冷却 {} ms）: id={}", FAILED_RETRY_COOLDOWN_MS, corpse.id);
            failedAttempts.put(corpse.sourceEntity, System.currentTimeMillis());
            return false;
        }
        corpse.ragdollReady = true;
        corpse.ragdollReadyMs = System.currentTimeMillis();
        logger.info("[Ragdoll] 布娃娃启用: id={} 交接速度=({}, {}, {}) m/s",
                corpse.id, params[1], params[2], params[3]);
        return true;
    }

    // ========================================================================
    // 地面采样（射线检测）
    // ========================================================================

    /**
     * 从 (x, y+0.5, z) 垂直向下射线取第一个实心方块顶面。
     *
     * @param fallback 射线未命中时的返回值（保持当前地面，不突变）
     */
    private double sampleGroundY(Level level, double x, double y, double z, double fallback) {
        if (level == null) {
            return fallback;
        }
        try {
            Vec3 from = new Vec3(x, y + 0.5, z);
            Vec3 to = new Vec3(x, y - GROUND_RAY_MAX_DEPTH, z);
            ClipContext clip = new ClipContext(
                    from, to,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    Minecraft.getInstance().player);
            HitResult hit = level.clip(clip);
            if (hit.getType() == HitResult.Type.BLOCK) {
                return hit.getLocation().y;
            }
        } catch (Exception e) {
            logger.debug("[Ragdoll] 地面射线检测失败，保持当前地面", e);
        }
        return fallback;
    }

    // ========================================================================
    // 实体渲染抑制 / 死亡白烟
    // ========================================================================

    /**
     * 死亡实体本体的渲染抑制（MMD 路径 + 原版路径 + 阴影）。
     * <b>登记即隐藏</b>，不等尸体模型就绪：否则死亡瞬间会出现"本体带着阴影飞走 +
     * 尸体原地出现"的分离——玩家身体从死亡那一帧起就由尸体管线接管。
     * 只对死亡实体本体生效：复活后的新实体 health>0，不受抑制。
     */
    public boolean shouldSuppressEntityRender(LivingEntity entity) {
        if (entity == null || !entity.isDeadOrDying()) {
            return false;
        }
        return findCorpseForEntity(entity) != null;
    }

    /** 是否应隐藏该实体的原版死亡白烟（死亡粒子劫持）。 */
    public boolean shouldHideDeathPoof(Entity entity) {
        if (!ConfigManager.isHideDeathPoof()) {
            return false;
        }
        return entity instanceof LivingEntity living && findCorpseForEntity(living) != null;
    }

    /** O(1) 身份查找：entity 对象 → corpse。 */
    private Corpse findCorpseForEntity(LivingEntity entity) {
        Long id = corpseIdByEntity.get(entity);
        return id != null ? corpses.get(id) : null;
    }

    public int corpseCount() {
        return corpses.size();
    }

    // ========================================================================
    // 死亡界面劫持（相机）
    // ========================================================================

    /** 死亡界面劫持是否生效（透明死亡屏 + 单击观战 + 双击复活）。 */
    public boolean isDeathScreenHijackActive() {
        return ConfigManager.isHijackDeathScreen();
    }

    private void tickCameraHijack(Minecraft minecraft) {
        boolean localDead = minecraft.player != null && minecraft.player.isDeadOrDying();
        wasLocalDead = localDead;
        if (localDead && !cameraHijacked && ConfigManager.isHijackDeathScreen()) {
            savedCameraType = minecraft.options.getCameraType();
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            cameraHijacked = true;
        } else if (!localDead && cameraHijacked) {
            restoreCamera(minecraft);
        }
    }

    /** 单击切换自由观战（死亡屏点击层调用；不满足双击复活时走到这里）。 */
    public void toggleDeathCamObserve() {
        Minecraft minecraft = Minecraft.getInstance();
        deathCamObserve = !deathCamObserve;
        if (deathCamObserve) {
            deathCamYaw = minecraft.player != null ? minecraft.player.yHeadRot : deathCamYaw;
            minecraft.mouseHandler.releaseMouse();
        } else {
            minecraft.mouseHandler.grabMouse();
        }
    }

    public boolean isDeathCamObserve() {
        return deathCamObserve;
    }

    /** 自由观战鼠标环视（灵敏度跟随 MC 设置，不硬编码）。 */
    public void deathCamMouseMove(double dx, double dy) {
        double sensitivity = Minecraft.getInstance().options.sensitivity().get();
        double degreesPerPx = 0.4 * (0.5 + sensitivity);
        deathCamYaw -= dx * degreesPerPx;
        deathCamPitch = (float) Mth.clamp(deathCamPitch - dy * degreesPerPx, -85.0, 85.0);
    }

    /** 自由观战滚轮缩放。 */
    public void deathCamScroll(double delta) {
        deathCamDistance = Mth.clamp(deathCamDistance - delta * 0.6, 0.8, 30.0);
    }

    /**
     * 相机接管目标（{@code CameraMixin} 每帧调用）。
     *
     * @return {x, y, z, yawDegrees, pitchDegrees}；非观战状态返回 null（不接管）
     */
    public double[] deathCamTarget() {
        if (!deathCamObserve) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return null;
        }

        // 支点：本地玩家的尸体位置（没有就玩家位置）+1m
        double px;
        double py;
        double pz;
        Corpse corpse = findCorpseForEntity(minecraft.player);
        if (corpse != null) {
            px = corpse.x;
            py = corpse.y;
            pz = corpse.z;
        } else {
            px = minecraft.player.getX();
            py = minecraft.player.getY();
            pz = minecraft.player.getZ();
        }
        py += 1.0;

        // 期望相机位置 = 支点 - 视线 × 距离；被地形挡住时像原版第三人称一样拉近
        double radYaw = Math.toRadians(deathCamYaw);
        double radPitch = Math.toRadians(deathCamPitch);
        double lx = -Math.sin(radYaw) * Math.cos(radPitch);
        double ly = -Math.sin(radPitch);
        double lz = Math.cos(radYaw) * Math.cos(radPitch);
        Vec3 pivot = new Vec3(px, py, pz);
        Vec3 desired = pivot.subtract(lx * deathCamDistance, ly * deathCamDistance, lz * deathCamDistance);
        try {
            ClipContext clip = new ClipContext(
                    pivot, desired,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    minecraft.player);
            HitResult hit = minecraft.level.clip(clip);
            if (hit.getType() != HitResult.Type.MISS) {
                desired = hit.getLocation().add(lx * 0.2, ly * 0.2, lz * 0.2);
            }
        } catch (Exception ignored) {
            // 射线失败时保持期望位置
        }

        return new double[]{desired.x, desired.y, desired.z, deathCamYaw, deathCamPitch};
    }

    private void restoreCamera(Minecraft minecraft) {
        if (cameraHijacked && minecraft.options != null && savedCameraType != null) {
            minecraft.options.setCameraType(savedCameraType);
        }
        cameraHijacked = false;
        if (deathCamObserve) {
            deathCamObserve = false;
            if (minecraft.mouseHandler != null && minecraft.screen == null) {
                minecraft.mouseHandler.grabMouse();
            }
        }
    }

    // ========================================================================
    // 尸体渲染（LevelRenderer TAIL 通道）
    // ========================================================================

    /**
     * 渲染所有就绪尸体（平台 LevelRendererMixin TAIL 注入点调用）。
     *
     * <p>位置同步契约：引擎内部 syncModelState 会用玩家位置覆盖模型位置，
     * 因此渲染前、后各调一次 {@code setModelPositionAndYaw}（场景模型同款约定）。
     */
    public void renderCorpses(PoseStack poseStack, float tickDelta, Camera camera) {
        if (corpses.isEmpty() || scenePort == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Vec3 camPos = camera.getPosition();
        double camX = camPos.x;
        double camY = camPos.y;
        double camZ = camPos.z;

        for (Corpse corpse : corpses.values()) {
            if (!corpse.renderReady || corpse.model == null) {
                continue;
            }
            ModelInstance instance = corpse.model.modelInstance();
            if (instance == null || instance.getModelHandle() == 0L) {
                continue;
            }
            double dx = corpse.x - camX;
            double dy = corpse.y - camY;
            double dz = corpse.z - camZ;
            if (dx * dx + dy * dy + dz * dz > RENDER_CULL_DISTANCE_SQ) {
                continue;
            }

            int packedLight = LevelRenderer.getLightColor(
                    minecraft.level, BlockPos.containing(corpse.x, corpse.y + 0.5, corpse.z));
            float posX = (float) (corpse.x * MODEL_SCALE);
            float posY = (float) (corpse.y * MODEL_SCALE);
            float posZ = (float) (corpse.z * MODEL_SCALE);
            float yawRadians = corpse.yawDegrees * DEGREES_TO_RADIANS;

            scenePort.setModelPositionAndYaw(instance.getModelHandle(), posX, posY, posZ, yawRadians);

            poseStack.pushPose();
            poseStack.translate((float) dx, (float) dy, (float) dz);

            // LevelRenderer 在实体渲染后已关闭光照层（TAIL 注入点），
            // 不重新打开会导致 entity 着色器采样到全黑光图 → 尸体黑模型
            minecraft.gameRenderer.lightTexture().turnOnLightLayer();
            try {
                RenderSystem.setShader(GameRenderer::getRendertypeEntityTranslucentShader);
                instance.render(
                        minecraft.player,
                        corpse.yawDegrees,
                        0.0f,
                        ZERO_TRANSLATION,
                        tickDelta,
                        poseStack,
                        packedLight,
                        RenderScene.WORLD
                );
                // 引擎在 render() 内部执行动画/物理更新：记一次"骨骼姿势已应用"
                corpse.renderedFrames++;
            } finally {
                minecraft.gameRenderer.lightTexture().turnOffLightLayer();
            }

            poseStack.popPose();
            // 渲染后再设一次：引擎内部 syncModelState 用玩家位置覆盖过模型位置
            scenePort.setModelPositionAndYaw(instance.getModelHandle(), posX, posY, posZ, yawRadians);
        }
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    /** 断线 / 换世界清理。 */
    public void clearAll() {
        Minecraft minecraft = Minecraft.getInstance();
        for (Corpse corpse : corpses.values()) {
            releaseCorpse(corpse);
        }
        corpses.clear();
        corpseIdByEntity.clear();
        failedAttempts.clear();
        restoreCamera(minecraft);
    }

    private void evictOverflow() {
        int max = ConfigManager.getMaxCorpses();
        if (max <= 0 || corpses.size() <= max) {
            return;
        }
        ArrayList<Corpse> sorted = new ArrayList<>(corpses.values());
        sorted.sort(Comparator.comparingLong(c -> c.deathTimeMs));
        int toEvict = corpses.size() - max;
        for (int i = 0; i < toEvict && i < sorted.size(); i++) {
            Corpse corpse = sorted.get(i);
            corpses.remove(corpse.id);
            releaseCorpse(corpse);
            logger.info("[Ragdoll] 数量上限逐出: id={}", corpse.id);
        }
    }

    private void releaseCorpse(Corpse corpse) {
        try {
            if (corpse.model != null && corpse.model.modelInstance() != null
                    && corpse.model.modelInstance().getModelHandle() != 0L) {
                ragdollPort.disableRagdoll(corpse.model.modelInstance().getModelHandle());
            }
        } catch (Exception e) {
            logger.warn("[Ragdoll] 尸体释放异常: id={}", corpse.id, e);
        }
        Long indexedId = corpseIdByEntity.get(corpse.sourceEntity);
        if (indexedId != null && indexedId == corpse.id) {
            corpseIdByEntity.remove(corpse.sourceEntity);
        }
        // 模型实例留在仓储里走正常缓存淘汰（此处只解绑）
        corpse.model = null;
    }
}
