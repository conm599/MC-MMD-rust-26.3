package com.shiroha.mmdskin.player.animation;

import com.shiroha.mmdskin.bridge.runtime.NativeAnimationBridgeHolder;
import com.shiroha.mmdskin.model.runtime.ManagedModel;
import com.shiroha.mmdskin.model.runtime.ModelInstance;
import com.shiroha.mmdskin.player.ragdoll.RagdollManager;
import com.shiroha.mmdskin.player.runtime.EntityAnimState;
import com.shiroha.mmdskin.player.sync.PlayerActionSyncService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AnimationStateManager {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final float TRANSITION_TIME = 0.25f;
    static final String DRINK_ANIMATION = "Drink";

    public static void updateAnimationState(AbstractClientPlayer player, ManagedModel model) {
        if (model.entityState().playCustomAnim) {
            if (!model.entityState().playStageAnim) {
                boolean local = isLocalPlayer(player);
                if (local && shouldStopCustomAnimation(player)) {
                    stopCustomAnim(model);
                    PlayerActionSyncService.getInstance().syncAnimStop();
                }
            }
        }

        if (!model.entityState().playCustomAnim) {
            updateLayer0Animation(player, model);
            updateLayer1Animation(player, model);
            updateLayer2Animation(player, model);
        }
    }

    private static void updateLayer0Animation(AbstractClientPlayer player, ManagedModel model) {
        // 死亡钩子：进 Die 状态前登记尸体（幂等；布娃娃就绪后实体路径渲染被取消）
        if (player.getHealth() == 0.0f) {
            RagdollManager.getInstance().tryEnterRagdoll(player, model);
        }
        EntityAnimState.State target = resolveLayer0State(player);
        changeAnimationOnce(model, target, 0);
    }

    private static EntityAnimState.State resolveLayer0State(AbstractClientPlayer player) {
        if (player.getHealth() == 0.0f) return EntityAnimState.State.Die;
        if (player.isFallFlying()) return EntityAnimState.State.ElytraFly;
        if (player.isSleeping()) return EntityAnimState.State.Sleep;
        if (player.isPassenger()) return resolveRidingState(player);
        if (player.isSwimming()) return EntityAnimState.State.Swim;
        if (player.onClimbable()) return resolveClimbingState(player);
        if (player.isSprinting() && !player.isShiftKeyDown()) return EntityAnimState.State.Sprint;
        if (player.isVisuallyCrawling()) return resolveCrawlState(player);
        if (hasMovement(player)) return EntityAnimState.State.Walk;
        return EntityAnimState.State.Idle;
    }

    private static EntityAnimState.State resolveRidingState(AbstractClientPlayer player) {
        var vehicle = player.getVehicle();
        if (vehicle != null && isHorselike(vehicle.getType()) && hasMovement(player)) {
            return EntityAnimState.State.OnHorse;
        }
        return EntityAnimState.State.Ride;
    }

    private static EntityAnimState.State resolveClimbingState(AbstractClientPlayer player) {
        double vy = player.getY() - player.yo;
        if (vy > 0) return EntityAnimState.State.OnClimbableUp;
        if (vy < 0) return EntityAnimState.State.OnClimbableDown;
        return EntityAnimState.State.OnClimbable;
    }

    private static EntityAnimState.State resolveCrawlState(AbstractClientPlayer player) {
        return hasMovement(player) ? EntityAnimState.State.Crawl : EntityAnimState.State.LieDown;
    }

    private static void updateLayer1Animation(AbstractClientPlayer player, ManagedModel model) {
        if ((!player.isUsingItem() && !player.swinging && player.hurtTime <= 0) || player.isSleeping()) {
            if (model.entityState().stateLayers[1] != EntityAnimState.State.Idle) {
                model.entityState().stateLayers[1] = EntityAnimState.State.Idle;
                model.entityState().layerAnimationKeys[1] = null;
                model.modelInstance().setLayerLoop(1, true);
                model.modelInstance().transitionAnim(0, 1, TRANSITION_TIME);
            }
        } else if (player.hurtTime <= 0) {
            updateHandAnimation(player, model);
        }
    }

    private static void updateHandAnimation(AbstractClientPlayer player, ManagedModel model) {
        if (player.getUsedItemHand() == InteractionHand.MAIN_HAND && player.isUsingItem()) {
            updateUsingItemAnimation(model, player.getItemInHand(InteractionHand.MAIN_HAND),
                    EntityAnimState.State.ItemRight, "Right", 1);
        } else if (player.swingingArm == InteractionHand.MAIN_HAND && player.swinging) {
            String itemId = getItemId(player.getItemInHand(InteractionHand.MAIN_HAND));
            applyCustomItemAnimation(model, EntityAnimState.State.SwingRight, itemId,
                    "Right", UseAnim.NONE, "swinging", 1);
        } else if (player.getUsedItemHand() == InteractionHand.OFF_HAND && player.isUsingItem()) {
            updateUsingItemAnimation(model, player.getItemInHand(InteractionHand.OFF_HAND),
                    EntityAnimState.State.ItemLeft, "Left", 1);
        } else if (player.swingingArm == InteractionHand.OFF_HAND && player.swinging) {
            String itemId = getItemId(player.getItemInHand(InteractionHand.OFF_HAND));
            applyCustomItemAnimation(model, EntityAnimState.State.SwingLeft, itemId,
                    "Left", UseAnim.NONE, "swinging", 1);
        }
    }

    private static void updateUsingItemAnimation(ManagedModel model, ItemStack itemStack,
                                                 EntityAnimState.State targetState, String activeHand, int layer) {
        String triggerAnimation = resolveUseTriggerAnimationName(itemStack.getUseAnimation());
        if (triggerAnimation != null) {
            long triggerAnim = model.animationLibrary().animation(triggerAnimation);
            if (triggerAnim != 0) {
                applyLayerAnimation(model, targetState, triggerAnimation, triggerAnim, layer, false);
                return;
            }
        }

        applyCustomItemAnimation(model, targetState, getItemId(itemStack), activeHand,
                itemStack.getUseAnimation(), "using", layer);
    }

    private static void updateLayer2Animation(AbstractClientPlayer player, ManagedModel model) {
        if (player.isShiftKeyDown() && !player.isVisuallyCrawling()) {
            changeAnimationOnce(model, EntityAnimState.State.Sneak, 2);
            return;
        }

        if (model.entityState().stateLayers[2] != EntityAnimState.State.Idle) {
            model.entityState().stateLayers[2] = EntityAnimState.State.Idle;
            model.entityState().layerAnimationKeys[2] = null;
            model.modelInstance().transitionAnim(0, 2, TRANSITION_TIME);
        }
    }

    private static void stopCustomAnim(ManagedModel model) {
        model.entityState().playCustomAnim = false;
        model.entityState().playStageAnim = false;
        model.modelInstance().changeAnim(model.animationLibrary().animation("idle"), 0);
        model.modelInstance().setLayerLoop(1, true);
        model.modelInstance().changeAnim(0, 1);
        model.modelInstance().changeAnim(0, 2);
        model.modelInstance().resetPhysics();
        model.entityState().invalidateStateLayers();
    }

    private static boolean shouldStopCustomAnimation(AbstractClientPlayer player) {
        return player.getHealth() == 0.0f || player.isFallFlying()
                || player.isSleeping() || player.isSwimming()
                || player.onClimbable() || player.isSprinting()
                || player.isVisuallyCrawling() || player.isPassenger()
                || hasMovement(player);
    }

    private static boolean hasMovement(AbstractClientPlayer player) {
        return player.getX() - player.xo != 0.0f || player.getZ() - player.zo != 0.0f;
    }

    private static boolean isLocalPlayer(AbstractClientPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getUUID().equals(player.getUUID());
    }

    private static boolean isHorselike(EntityType<?> type) {
        return type == EntityType.HORSE || type == EntityType.DONKEY
                || type == EntityType.MULE || type == EntityType.SKELETON_HORSE
                || type == EntityType.ZOMBIE_HORSE;
    }

    private static void changeAnimationOnce(ManagedModel model, EntityAnimState.State targetState, int layer) {
        String animationKey = targetState.propertyName;
        if (model.entityState().stateLayers[layer] != targetState
                || !Objects.equals(model.entityState().layerAnimationKeys[layer], animationKey)) {
            model.entityState().stateLayers[layer] = targetState;
            model.entityState().layerAnimationKeys[layer] = animationKey;
            long anim = model.animationLibrary().animation(animationKey);
            if (targetState == EntityAnimState.State.Die) {
                // 死亡：硬切到死亡动画并跳过开头的绑定姿势帧。
                // 用 transitionAnim 交叉淡入的话，渐变终点是死亡动画第 0 帧（站直、手臂微张），
                // 手臂会从垂着慢慢张开到两侧——看着像角色先僵一下再倒。
                model.modelInstance().changeAnim(anim, layer);
                model.modelInstance().setLayerLoop(layer, false);
                seekLayer(model, layer, RagdollManager.DEATH_ANIM_SKIP_FRAMES);
            } else {
                model.modelInstance().transitionAnim(anim, layer, TRANSITION_TIME);
            }
        }
    }

    /** 把指定层的动画时间跳到指定帧。 */
    private static void seekLayer(ManagedModel model, int layer, float frame) {
        try {
            ModelInstance instance = model.modelInstance();
            if (instance == null) {
                return;
            }
            NativeAnimationBridgeHolder.get().seekLayer(instance.getModelHandle(), layer, frame);
        } catch (Exception e) {
            // 跳帧失败不影响主流程，退回从第 0 帧播
            LOGGER.debug("跳过死亡动画开头帧失败: {}", e.getMessage());
        }
    }

    private static void applyCustomItemAnimation(ManagedModel model, EntityAnimState.State targetState,
                                                 String itemName, String activeHand, UseAnim useAnim,
                                                 String handState, int layer) {
        boolean shouldLoop = !"using".equals(handState);
        for (String animationKey : resolveItemAnimationKeys(itemName, activeHand, useAnim, handState)) {
            long anim = model.animationLibrary().animation(animationKey);
            if (anim != 0) {
                applyLayerAnimation(model, targetState, animationKey, anim, layer, shouldLoop);
                return;
            }
        }

        if (targetState == EntityAnimState.State.ItemRight || targetState == EntityAnimState.State.SwingRight) {
            changeAnimationOnce(model, EntityAnimState.State.SwingRight, layer);
            model.modelInstance().setLayerLoop(layer, shouldLoop);
        } else if (targetState == EntityAnimState.State.ItemLeft || targetState == EntityAnimState.State.SwingLeft) {
            changeAnimationOnce(model, EntityAnimState.State.SwingLeft, layer);
            model.modelInstance().setLayerLoop(layer, shouldLoop);
        }
    }

    private static void applyLayerAnimation(ManagedModel model, EntityAnimState.State targetState, String animationKey,
                                            long animHandle, int layer, boolean shouldLoop) {
        if (animHandle == 0) {
            return;
        }
        if (model.entityState().stateLayers[layer] != targetState
                || !Objects.equals(model.entityState().layerAnimationKeys[layer], animationKey)) {
            model.entityState().stateLayers[layer] = targetState;
            model.entityState().layerAnimationKeys[layer] = animationKey;
            model.modelInstance().setLayerLoop(layer, shouldLoop);
            model.modelInstance().transitionAnim(animHandle, layer, TRANSITION_TIME);
        }
    }

    static String resolveUseTriggerAnimationName(UseAnim useAnim) {
        if (useAnim == null) {
            return null;
        }
        return switch (useAnim) {
            case EAT, DRINK -> DRINK_ANIMATION;
            default -> null;
        };
    }

    static List<String> resolveItemAnimationKeys(String itemName, String activeHand, UseAnim useAnim,
                                                 String handState) {
        List<String> animationKeys = new ArrayList<>();
        animationKeys.add(buildItemAnimationKey(itemName, activeHand, handState));

        if (useAnim == UseAnim.BOW) {
            String alternateHand = "Right".equals(activeHand) ? "Left" : "Right";
            animationKeys.add(buildItemAnimationKey(itemName, alternateHand, handState));
        }

        return animationKeys;
    }

    private static String buildItemAnimationKey(String itemName, String activeHand, String handState) {
        return String.format("itemActive_%s_%s_%s", itemName, activeHand, handState);
    }

    private static String getItemId(ItemStack itemStack) {
        String descriptionId = itemStack.getItem().getDescriptionId();
        int dotIndex = descriptionId.indexOf('.');
        return dotIndex >= 0 ? descriptionId.substring(dotIndex + 1) : descriptionId;
    }
}
