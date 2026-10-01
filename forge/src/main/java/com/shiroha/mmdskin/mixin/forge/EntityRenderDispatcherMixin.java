package com.shiroha.mmdskin.mixin.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.player.ragdoll.RagdollManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 尸体管线的实体本体从登记那一帧起整体隐藏（含影子圆盘）。
 * 不藏影子的话：本体不可见但影子还在飞，看起来像一块黑色圆盘飘在场景里。
 *
 * <p>renderShadow 是 EntityRenderDispatcher 的本类私有方法（非继承），
 * 字符串 @Inject 的描述符能被 refmap 正常解析（继承方法才要软覆盖，见 DeathScreenMixin）。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
    private void mmd$onRenderShadow(PoseStack poseStack, MultiBufferSource bufferSource, Entity entity,
                                    float yaw, float partialTick, LevelReader level, float shadowRadius,
                                    CallbackInfo ci) {
        if (entity instanceof LivingEntity living
                && RagdollManager.getInstance().shouldSuppressEntityRender(living)) {
            ci.cancel();
        }
    }
}
