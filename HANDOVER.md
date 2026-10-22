# MC-MMD-rust 1.1.0 交接说明

> 交付内容：Minecraft 1.20.1 模组「MC-MMD-rust」，含 **死亡布娃娃**功能重写版。
> 交付日期：2026-10-02　|　分支：`1.20-vr`　|　HEAD：`4051d38`　|　tag：`v1.1.0`
> 交付 jar：`mmdskin-fabric-1.1.0-1.20.1.jar`（sha256 前缀 `ae0cf7ec37e91c47`）

---

## 1. 这是什么

- Minecraft **1.20.1** 客户端模组（Fabric + Forge 双加载器），把玩家/生物的 MMD 模型渲染进游戏，
  并让**死亡时生成独立的 MMD 尸体模型**，由 Bullet3 物理接管（16 段刚体 + 6DOF 关节）。
- 渲染引擎用 Rust 写（`rust_engine/`），Java 通过 JNI 调用。
- 本次交付相对上游 `shiroha-233/MC-MMD-rust` 的 `1.20-vr` 分支：**Java 侧死亡布娃娃功能全部重写**，
  Rust 侧物理层从已验证的旧分支移植（数值全部存档在 `RAGDOLL_PHYSICS_PARAMS.txt`）。

## 2. 当前状态

- 布娃娃主体功能可用：死亡 → 尸体模型加载 → 死亡动画 → 物理接管 → 保留/逐出 → 60 秒后冻结物理。
- 死亡屏劫持（透明屏 / 单击自由观战 / 双击复活）、隐藏死亡白烟、死亡瞬间隐藏玩家本体与影子均可用。
- 上限逐出、保留策略（附近/定时/永久）、物理保持时长等设置全部生效。
- **仍存一个边缘问题**：死亡点紧邻水/岩浆时，尸体可能沉入液体方块被液面深度遮挡，表现为"看不见/贴图丢失"。
  含流体的双射线采样版本实测无改善并更差，已回退（`2b79346` 保留在历史里，可查）。后续若要根治，
  方向是让尸体停在液面（或给布娃娃加浮力），需要改 Rust 物理层。

## 3. 目录结构

```
MC-MMD-rust/
├─ common/    Java 跨加载器代码（配置、布娃娃核心、渲染接入、UI）
├─ fabric/    Fabric 专属（mixin、设置界面、客户端钩子）
├─ forge/     Forge 专属（同构）
├─ rust_engine/   Rust 渲染/物理引擎
│  ├─ src/ragdoll/        布娃娃物理（mod.rs 507 行 / spec.rs 929 行）
│  ├─ src/physics/        Bullet3 FFI 封装
│  ├─ src/model/runtime.rs 模型运行时（含布娃娃接管逻辑）
│  ├─ src/skeleton/bone_set.rs 接管骨集合
│  ├─ src/jni_bridge/native_func.rs  全部 JNI 导出
│  └─ deps/bullet3/       vendored Bullet3 源码（编译必需）
├─ RAGDOLL_PHYSICS_PARAMS.txt   物理层全部关键数值存档（重写物理时照抄）
└─ HANDOVER.md            本文件
```

**布娃娃相关 Java 文件（全部为本次重写）**

| 文件 | 职责 |
|---|---|
| `common/.../player/ragdoll/RagdollManager.java` | 核心：尸体登记/加载/就绪/启物理/保留/逐出/冻结/地面采样/死亡相机/尸体渲染 |
| `common/.../player/ragdoll/RagdollRenderEntrypoint.java` | 平台 mixin 调用尸体渲染的统一入口（异常隔离） |
| `common/.../config/IRagdollConfig.java` | 布娃娃配置接口（10 项） |
| `common/.../bridge/runtime/NativeRagdollPort.java` | JNI 能力边界（6 个方法） |
| `fabric|forge/.../mixin/*/DeathScreenMixin.java` | 死亡屏劫持（透明屏/单击观战/双击复活/鼠标与滚轮转发） |
| `fabric|forge/.../mixin/*/LivingEntityDeathParticleMixin.java` | 隐藏原版死亡白烟 |
| `fabric|forge/.../mixin/*/EntityRenderDispatcherMixin.java` | 隐藏被尸体管线接管的实体影子 |
| `fabric|forge/.../mixin/*/CameraMixin.java` | 死亡观战相机接管（在既有注入点前加） |
| `fabric|forge/.../mixin/*/LevelRendererMixin.java` | renderLevel TAIL 渲染尸体 |

## 4. 构建与部署

```bash
# 前置：JDK 17、cargo/rustc、网络代理（本机需 http.proxyHost=127.0.0.1 proxyPort=10808）
cd MC-MMD-rust
export PATH="$HOME/.cargo/bin:$PATH"
export GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808"

./gradlew :fabric:build        # 产物：fabric/build/libs/mmdskin-fabric-1.1.0-1.20.1.jar
./gradlew :forge:build         # 产物：forge/build/libs/mmdskin-forge-1.1.0-1.20.1.jar
```

- Rust 由 `common` 模块的 `buildRustNative` task 自动编译，dll 以资源 `natives/<平台>/mmd_engine.dll` 打进 jar。
- 部署：把 fabric jar 复制到 `.minecraft/versions/<版本>/mods/`（**先删旧版同名 jar**，避免双载）。
- **版本哨兵**：`NativeLibraryLoader.LIBRARY_VERSION` 与 Rust `native_func.rs` 的 `VERSION` 必须一致，
  改了原生库就要同时升（当前 `v1.1.1`），否则会加载到旧缓存 dll 报 `UnsatisfiedLinkError`。
- 本地 git 已包含完整历史（46MB），每一步改动都有独立提交，可精确回退。

## 5. 布娃娃运行流程

1. **死亡触发**（渲染栈内，幂等）：玩家 `AnimationStateManager`、生物 `EntityAnimationResolver`、
   女仆 `MaidMMDRenderer` 三处调用 `RagdollManager.tryEnterRagdoll(entity, model)`
   → 登记尸体（自增 corpseId + 死亡实体对象身份绑定）、取地面高度、快照启物理参数、登记后立即按上限逐出最旧
2. **模型加载**（异步）：缓存键 `PLAYER:<uuid>|corpse|<id>:<model>`，每次死亡独立实例
   → 热加载实测 ~100ms（日志 `[Ragdoll] 尸体模型就绪`）
3. **死亡动画**：模型就绪后播 die 动画并按已流逝时间 `seekLayer` 对齐
4. **启物理**：模型实际渲染满 3 帧后（骨骼离开绑定姿势）才构建布娃娃，避免 T-pose 摊开；
   启物理时用**位置差分 EMA** 作为交接速度（继承死亡瞬间的动量/惯性）
5. **地面**：`level.clip` 垂直射线取实心方块顶面；每 1s 复检，地面变化时 `setRagdollGround`（引擎侧同时刷新
   broadphase 接触对并唤醒刚体）
6. **保留与逐出**：NEARBY（半径）/ SECONDS（时长）/ FOREVER + 数量上限 LRU；逐出时**延迟到 tick 间隙**
   销毁模型（绝不在渲染过程中删 GL 对象）
7. **冻结**：启物理满 N 秒（默认 60）后跳过布娃娃步进并关闭 MMD 物理，姿势保持
8. **渲染**：`LevelRenderer.renderLevel` TAIL 注入 → 渲染前/后各设一次模型位置（引擎 syncModelState 会用玩家
   位置覆盖模型位置）→ 重新打开光照层（否则黑模型）→ 渲染 → 收尾做 GL 状态修复

## 6. 设置项（Cloth Config「死亡布娃娃」分类）

| 项 | 默认 | 范围 | 说明 |
|---|---|---|---|
| 启用死亡布娃娃 | 开 | | 总开关 |
| 尸体保留模式 | 附近 | 附近/定时/永久 | |
| 附近保留半径 | 48 | 8–256 | 玩家超出该距离即逐出 |
| 尸体保留时长 | 60 | 5–600 | 定时模式 |
| 尸体数量上限 | **2** | 1–64 | 保守默认：每具尸体都有渲染+物理开销 |
| 关节强度 | 1.35 | 0.30–4.00 | 越大越硬 |
| 隐藏死亡白烟 | 开 | | |
| 劫持死亡界面 | 开 | | 困难模式不生效 |
| 死亡动画时长 | 0 | 0–5 秒 | 0 = 立即布娃娃 |
| 尸体物理保持时长 | 60 | 0–300 秒 | 到点冻结物理；0 = 永久模拟 |

> 注：**附近保留半径只在你"来过尸体附近"之后才开始逐出**——重生点离死亡点远时不会刚生成就被删。

## 7. 修复历程（git 提交即档）

| 提交 | 内容 |
|---|---|
| `0a88be4` | 移植 Rust 物理层 + vendored bullet3，版本哨兵 v1.1.0 |
| `e284b10` | 物理数值存档（`RAGDOLL_PHYSICS_PARAMS.txt`） |
| `5f208fc` | 配置层重写：10 项设置 + 双加载器界面 + 三语言 |
| `6639359` | JNI 桥接：6 个 native + Port + 版本哨兵 |
| `ab9cfe8` | RagdollManager 重写：每次死亡独立记录、射线地面、弱引用、O(1) 抑制索引、死亡相机、光照层修复 |
| `ebbc38e` | 平台接入：三处死亡触发、三处渲染抑制、四类 mixin × 双加载器、tick 挂点 |
| `854a27a` | 修复启动崩溃：`mouseMoved/mouseScrolled` 是继承方法，`@Inject` 无法解析描述符 → 改软覆盖 |
| `ed249bd` | 布娃娃改为从死亡动画姿势构建（等 3 帧再启物理），避免 T-pose |
| `fa3ab69` | 尸体物理保持时长（冻结）+ 保守默认 + 惯性交接（×20 单位修正 + EMA）+ 死亡瞬间隐藏 |
| `44073a6` | 贴图竞态修复：`TextureRepository.getAndRef` 原子化（原来 GetTexture/addRef 分步会丢引用，GL id 被删后复用 → 随机怪色尸体） |
| `5e15fe1` | 引擎 GL 状态收尾修复（不改上游源码） |
| `9a71c89` | 外部销毁（保存设置触发 reloadAll）后干净复位 + 静默逐出补日志 |
| `6901e0d` | 改为每次死亡独立实例（多具尸体共存、动作独立） |
| `2b79346` | 含流体的双射线地面采样（**实测无改善，已回退但保留历史**） |
| `4051d38` | **GL 销毁延迟到 tick 间隙**（渲染中途删 GL 对象 → id 复用 + 缓存失同步 → 第三具尸体空贴图/闪一下/连锁消失） |

## 8. 上游引擎层已知缺陷（未改上游源码，全部在调用方规避）

审计（反汇编核实 MC 1.20.1 行为）发现引擎自身 6 处 GL 状态缺陷：

1. 裸 `glBindTexture` 绕过 Mojang 纹理绑定缓存（缓存与实际失同步 → 随机错误纹理）
2. toon（三渲二）路径裸 `glUseProgram(0)` 不清 `ShaderInstance.lastProgramId` → 下次 apply 跳过程序切换
3. 引擎把 MMD 光罩 id 写进**共享的 MC 着色器单例**的 Sampler1/2 → 光照/overlay 单元被污染（整体染色）
4. 描边 pass 的 `depthMask(false)` 异常时不恢复
5. 描边 pass 的 `glCullFace(GL_FRONT)` 异常时永久化（vanilla 永不修复）
6. 状态恢复不在 try/finally 内 → 一次异常把偶发错乱固化成常态

**规避方式**：`RagdollManager.renderCorpses` 收尾调 `repairUpstreamGlState()`——纹理缓存再同步、清空
Sampler1/2、`shader.clear()`、复位颜色/深度/剔除面。合并上游时这些规避可保留。

## 9. 排查手法（复现问题时用）

- 游戏日志：`versions/1.20.1-Fabric 0.19.5/logs/latest.log`，关注 `[Ragdoll]` 生命周期：
  `尸体登记 → 尸体模型就绪 → 布娃娃启用 → 地面高度变化 → 物理保持期到点 → 数量上限逐出/超出附近保留半径逐出`
- 崩溃报告：Minecraft 崩溃窗口生成 zip，放 `D:\mcmmd\tmp\` 即可
- 混战排查建议：先看日志判断卡在哪一环（登记/就绪/启用/渲染），日志已覆盖全部阶段
- 复现"尸体空贴图"时的关键判据：**尸体是否在同一帧被逐出并销毁过模型**（看日志有无 `数量上限逐出`）

## 10. 物理层重写指引

若将来要重写 Rust 物理层：`RAGDOLL_PHYSICS_PARAMS.txt` 内含全部关键数值（16 段质量分布、半径表、
球/铰链限位表、ERP/CFM 推导公式、阻尼/摩擦/恢复/重力、腿部自碰撞收缩规则、运行时安全钳制、
模型更新管线中的接管顺序），照抄即可保持手感不变。

## 11. 交付物

- 源码包：`MC-MMD-rust-1.1.0-source.zip`（含完整 git 历史，排除 `build/`、`target/`、`.gradle/`）
- 可运行包：`mmdskin-fabric-1.1.0-1.20.1.jar`（已在测试实例 mods 目录中验证）
- 数值存档：`RAGDOLL_PHYSICS_PARAMS.txt`
- 本交接说明：`HANDOVER.md`
