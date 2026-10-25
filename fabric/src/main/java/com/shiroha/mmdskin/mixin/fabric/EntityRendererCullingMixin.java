// 负责在 Fabric 实体剔除入口合并已加载 MMD 模型的 bounds。
package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.client.entity.MmdCullingBounds;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererCullingMixin {
    // 26.2 的签名是 getBoundingBoxForCulling(Entity)，
    // 26.3 起才多了一个 partialTick 参数（描述符必须跟着版本走，否则 mixin 注入失败）。
    @Inject(method = "getBoundingBoxForCulling(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/AABB;",
            at = @At("RETURN"), cancellable = true)
    private void mmdskin$expandCullingBounds(Entity entity,
                                             CallbackInfoReturnable<AABB> callback) {
        if (entity instanceof LivingEntity living) {
            callback.setReturnValue(MmdCullingBounds.expand(living, callback.getReturnValue()));
        }
    }
}
