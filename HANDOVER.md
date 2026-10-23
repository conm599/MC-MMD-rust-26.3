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
- **断线/退出存档曾必崩**（2026-10-22 修复，见 §7 末行）：根因是断线清理在 Netty IO 线程删 GL 对象；现所有 GL 销毁走 `GlDisposal` 回渲染线程。
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
| `6b02efd` | **模型分发功能**（多人联机）：房主在服务器 `3d-skin/EntityPlayer/<名字>/` 放模型 + 改 `config/mmdskin/distribution.json` 授权，服务器按限速分块推送，客户端落盘到自己的模型目录后现有渲染链路直接接管（**渲染链路零改动**）。默认三重全关（`acknowledged` / `enabled` / 每模型 `distributable`），房主端只有配置文件、没有 UI。含断点续传、MD5 校验、OSS 直链旁路（服务器出口可降到 0）、两级责任提示页。详见 §12 |
| `7c6343c` | **断线/退出存档必崩修复**：Fabric 的 `ClientPlayConnectionEvents.DISCONNECT` 跑在 **Netty IO 线程**，断线清理链路在非渲染线程调 `glDelete*` → LWJGL `No context is current` → **直接 abort 整个 JVM**（无 Java 异常栈、无崩溃报告，世界也存不下来）。修复：新增 `render/backend/GlDisposal.java`，所有 GL 销毁统一经 `GlDisposal.onRenderThread()` 交回渲染线程（`gpu` / `opengl` 两个后端都覆盖）。**清理链路的线程与时序完全不变**，唯一差别是 `glDelete*` 在渲染线程执行 |
| `b9de9cd` | **死亡瞬间闪烁修复**：原来本体渲染一登记就隐藏，尸体模型异步加载 ~80 ms（≈5 帧）空窗里角色凭空消失。改成「尸体能画了才接管」（`shouldSuppressEntityRender` 返回 `corpse.renderReady && corpse.model != null`），空窗期让本体继续渲染，靠 `startCorpseDeathAnim` 按已流逝时间对齐 Death 动画保证交接连续 |
| `2002475` | **死亡瞬间「绷直 + 手臂微张」修复**：死亡走 `transitionAnim` 交叉淡入，渐变终点是死亡动画第 0 帧，而 MMD 死亡动画第 0 帧就是**绑定姿势**（站直、手臂微张）——渐变过程手臂从垂着慢慢张开到两侧，看着像角色先僵一下才跟物理倒。改法：进 Die 状态时 `changeAnim` 硬切 + `setLayerLoop(false)` + `seekLayer` 跳到第 3 帧（`RagdollManager.DEATH_ANIM_SKIP_FRAMES`）；尸体侧按已流逝时间对齐的 seek 同样加 3 帧偏移，两边接得上 |

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

---

## 12. 模型分发功能（多人联机）

> 面向房主/玩家的使用说明见仓库根目录 **`MODEL_DISTRIBUTION_GUIDE.md`**。

### 12.1 是什么

房主把 MMD 模型放在**服务器**上，授权后，联机玩家可以在客户端一键下载并使用。
**模型文件永远不经过任何第三方**；服务器只做传输。

设计前提：绝大多数 MMD 模型禁止二次配布，所以**默认一律不分发**，
房主必须逐个模型显式授权，并对授权行为负责。

### 12.2 房主 vs 服主：两种部署，两个必须分清的地方

| | 房主（单人 / 局域网） | 服主（专用服务器） |
|---|---|---|
| 机器上跑什么 | 客户端 + 服务端**同一个进程** | **只有**服务端，没有客户端 |
| 房主本人 | 也在房间里，是个玩家 | 不在游戏里（也可以自己另外开客户端连） |
| 模型文件在哪 | 房主自己的游戏目录 | 服务器目录 |
| 怎么授权 | 改同一份 `config/mmdskin/distribution.json` | 同左（服务器目录里的那一份） |
| 房主自己会被提示下载吗 | **不会**（本地已经有，客户端按"本地是否存在"过滤掉） | 不适用 |

**坑一：游戏目录不能靠 `Minecraft.getInstance()` 拿。**
那个静态 `instance` 字段只有**客户端** `Minecraft` 构造器才赋值；专用服务器走
`net.minecraft.server.Main`，**根本不创建客户端 Minecraft 实例**，所以
`Minecraft.getInstance()` 在服主那边是 `null`（而且专用服务器 jar 里
`net.minecraft.client.Minecraft` 这个类压根不存在，引用了就是 NoClassDefFoundError）。

所以游戏目录改由 `config/GameDirectoryProvider` 注入，两个平台的**公共入口**里设置：
- Fabric：`MmdSkinFabric.onInitialize()` → `FabricLoader.getInstance().getGameDir().toFile()`
- Forge：`MmdSkinForge` 构造器 → `FMLPaths.GAMEDIR.get().toFile()`

`PathConstants.getGameDirectory()` 统一走这个 provider。
**服务端可达的类里不许出现 `net.minecraft.client.*` 的 import。**

**坑二：房主自己会收到清单。**
房主就在房间里，所以他也算一个"客户端"，会收到 MANIFEST。
客户端侧按**本地是否已存在该模型目录**过滤，房主自己的模型不会出现在下载列表里；
已经装过同款模型的玩家同理。

### 12.3 房主怎么开（只有配置文件，没有 UI —— 最保守）

1. 把模型放 `<服务器目录>/3d-skin/EntityPlayer/<模型名>/`（与客户端目录同构）
2. 改 `<服务器目录>/config/mmdskin/distribution.json`：

```jsonc
{
  "acknowledged": true,              // 责任确认：不改成 true，整个功能不启动
  "enabled": true,                   // 全局总开关
  "rateLimitKbPerSec": 256,          // 单客户端限速（0 = 不限）
  "globalRateLimitKbPerSec": 1024,   // 服务器总出口配额（0 = 不限）
  "models": {
    "纳西妲": { "distributable": true, "ossUrl": "" }
  }
}
```

3. **保存即生效**（2026-10-22 改）：服务器每 2 秒轮询配置文件时间戳，改完立刻重新载入并**把新清单推给所有在线玩家**，不用重启、玩家不用重连。撤销某个模型的分发也会立刻从玩家列表里消失

### 12.4 三种状态

| 状态 | 表现 |
|---|---|
| 全关（默认） | 行为与本模组发布版**完全一致**，零影响 |
| 服务器转发 | 客户端从服务器下载，占服务器出口带宽（按限速） |
| OSS 直链 | 房主填直链，服务器比对「本地副本 MD5 vs OSS ETag」通过后，客户端直接从对象存储下载，**服务器出口 = 0** |

> OSS 的 ETag 在单次上传（未分片）时就是文件 MD5，所以可以直接比对；
> 分片上传的 ETag 不是 MD5，会判为不一致并自动退回服务器转发。

### 12.5 客户端流程

```
进服 → 服务器推清单 → 模型列表顶部出现「⬇ 服务器模型分发 (N)」
  → 一级提示页（条件说明 + 责任分离 + 带宽告知 + 好心提醒）
  → 二级确认页（选模型、看体积与来源、必须勾「我确认拥有分发权」）
  → 下载（32 KB 分块 / 限速 / 断点续传 / MD5 校验）
  → 写入临时目录 .__mmd_partial，全量校验通过后原子替换到正式目录
  → ModelCatalogEntry.invalidateCache() → 模型列表刷新后即可选用
```

### 12.6 关键实现位置

| 文件 | 职责 |
|---|---|
| `distrib/ModelDistributionConfig.java` | 房主配置读写 + `acknowledged` 责任确认 |
| `distrib/ModelDistributionManifest.java` | 清单模型（文件列表 + 大小 + MD5）与线上格式 |
| `distrib/ModelDistributionService.java` | 服务端：扫描、清单、令牌桶限速、OSS ETag 校验、分块转发 |
| `distrib/ModelDistributionClient.java` | 客户端：接收、落盘、MD5 校验、续传进度、刷新目录缓存 |
| `ui/selector/DistributionNoticeScreen.java` | 一级提示页 |
| `ui/selector/DistributionConfirmScreen.java` | 二级确认页 |

协议走现有通道，新增 5 个 OpCode（`ui/network/NetworkOpCode.java`）：
`DISTRIB_MANIFEST=20` / `DISTRIB_OFFER=21` / `DISTRIB_CHUNK=22` / `DISTRIB_REQUEST=23` / `DISTRIB_COMPLETE=24`。

### 12.7 审计发现（2026-10-22 三轮对抗性复查，已修）

| 问题 | 后果 | 修法 |
|---|---|---|
| 临时目录是安装目标的**子目录** | 安装**永远失败**，还会把玩家原模型弄丢 | 临时目录改成**同级兄弟** `<名字>.__mmd_partial`；失败时回滚备份 |
| 限速器按 tick 预算硬发整块 | 实际速率是配置值的 **2.5 倍**（配 256 实际 640 KB/s） | 令牌桶按**实际发出字节**扣减，额度不够就等下一 tick |
| 整个模型入队时一次性读进内存 | 500 MB 模型 = 500 MB 堆/玩家，主线程冻结 | 改成**游标式按需读盘**，服务端内存里最多一个 32 KB 块 |
| 玩家进服在主线程算全模型 MD5 + 阻塞式 OSS HEAD | 任何人连进来就能冻住服务器 | 进服只算**摘要**（不碰 MD5）；MD5/OSS 校验挪到后台预热 |
| 清单缓存键用**目录 mtime** | 房主改了模型文件后玩家**永远下载失败** | 缓存键改成**文件内容指纹**（path+size+mtime） |
| 清单里的**文件路径没校验** | 恶意服务器可写玩家硬盘任意路径 | 新增 `isValidRelativePath`，客户端三处写入点全部校验 |
| 清单里的**模型名没校验** | 恶意/畸形清单可让客户端 NPE 崩溃 | `onManifest` 逐个校验，非法项丢弃 |
| OSS 只校验第一个文件 | 后面文件不一致时**静默失败且不回退** | 客户端任一文件失败 → 主动回落 TCP 转发 |
| 责任确认框**没真的卡住下载** | "责任分离"形同虚设 | 点击处理里强制检查勾选状态，未勾直接不下载 |
| 按钮点击用**上一帧的 hover 状态** | 光标挪一下就点错按钮 | 改用真实坐标命中判定 |
| 两个新页面**不能 ESC 关闭** | 高 GUI 缩放下可能被锁死在流程里 | 补 `keyPressed`（ESC → onClose） |
| 断点续传用**序号** | 宿主清单一变就错位，卡死 | 改用**文件路径**，宿主改文件也不会错位 |
| 同一玩家下多个模型只发一次 COMPLETE | 只有一个能装上 | 传输任务改成队列，逐个发 COMPLETE |

另：**Forge 专用服务器能正常启动** —— 已用 JVM 实测确认
`registerMessage` 时的类链接**不会**解析 `net.minecraft.client.*`，
`NoClassDefFoundError` 只在**真正执行**客户端方法时才发生（服务端永不执行）。

### 12.8 清单分页（OFFER 载荷上限）

一份完整清单可能有上万个文件，直接塞进一个包会撞上：
- 代理的插件消息上限（BungeeCord 默认 **32767 字节**，看的是压缩前的载荷）
- vanilla 的 1 MB 解压后上限

所以清单**按页下发**：每页 ≤ **12 KB**（gzip 前），页内文件 ≤ 256 个，
每页还额外做一次 gzip（路径重复率高，压缩比约 5–8 倍）。
客户端把页攒齐了再开始下载。

另外加了两个硬上限：
- 单模型文件数 > **4096** → 直接拒绝分发并打明确日志（这种目录本来就不正常）
- 单文件 > 256 MB → 跳过

### 12.9 配置热更新（实时推送）

服务器每 2 秒检查 `distribution.json` 的修改时间（`ModelDistributionConfig.fileStamp()`）：
变了就 `reload()` + 清清单缓存 + **把新清单广播给所有在线玩家**。

- 平台层各自广播：Fabric 用 `PlayerLookup.all(server)`，Forge 用 `event.getServer().getPlayerList().getPlayers()`。
- 客户端 `onManifest` **整份替换**清单集合 —— 房主撤销分发的模型会立刻从玩家列表里消失。

### 12.10 自动下载附近玩家的模型

设置里新增两个选项（**默认关**）：

| 选项 | 默认 | 说明 |
|---|---|---|
| 自动下载周围玩家的模型 | 关 | 只下**半径内玩家正在使用**、且服务器管理员**已授权分发**的模型 |
| 自动下载半径 | 48 格 | 只扫描这个距离内的玩家 |

刻意**不做**"把服务器上所有模型都下下来"——那既没道理也会把带宽吃光。
第一次自动下载前会先弹一次责任条款页（玩家点"我已了解"才开始）。

### 12.11 回归测试

`common/src/test/java/com/shiroha/mmdskin/distrib/ModelDistributionTest.java`（8 条，全绿）：
模型名/相对路径穿越拦截、默认三重关闭、分块帧编解码对称、截断帧拒绝、
安装序列（临时目录必须是同级兄弟 / 安装失败不能丢原模型）、
清单分页（单页不超上限 / 拼回来能还原）、gzip 往返。

### 12.12 注意

- 分发单元是**模型文件夹**（PMX 用相对路径引贴图，只发 `.pmx` 会白模）
- 断点续传按**文件**粒度（不做半个文件续传）
- **不碰 `BONE_SYNC`** —— 它那条流式骨骼通道看着没接线，但远端动作本来就是在接收方本地重建的，动了反而会坏
- 专用服务器无需额外准备：把文件放进 `3d-skin/EntityPlayer/` 即可，模组服务端入口本来就在
