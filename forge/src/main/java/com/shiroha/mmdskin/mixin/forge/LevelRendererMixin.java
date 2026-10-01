package com.shiroha.mmdskin.mixin.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.player.ragdoll.RagdollRenderEntrypoint;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.shiroha.mmdskin.compat.vr.VRArmHider;
import com.shiroha.mmdskin.forge.YsmCompat;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.compat.iris.IrisCompat;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** LevelRenderer Mixin，用于在 MMD 第一人称与 VR 场景下决定本地玩家是否强制渲染。 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Redirect(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z", ordinal = 0)
    )
    private boolean onCameraIsDetached(Camera camera) {
        if (IrisCompat.isRenderingShadows()) {
            return camera.isDetached();
        }

        Entity entity = camera.getEntity();
        if (!(entity instanceof AbstractClientPlayer player)) {
            return camera.isDetached();
        }

        String playerName = player.getName().getString();
        String selectedModel = PlayerModelSyncService.getPlayerModel(player.getUUID(), playerName, true);

        boolean isMmdDefault = selectedModel == null || selectedModel.isEmpty()
                || selectedModel.equals("默认 (原版渲染)");
        boolean isMmdActive = !isMmdDefault;
        boolean isVanilaMmdModel = isMmdActive && (selectedModel.equals("VanillaModel")
                || selectedModel.equalsIgnoreCase("vanilla")
                || selectedModel.equals("VanilaModel")
                || selectedModel.equalsIgnoreCase("vanila"));

        if (isMmdActive && !isVanilaMmdModel && VRArmHider.isLocalPlayerInVR()) {
            return true;
        }

        if (FirstPersonManager.shouldRenderFirstPerson() && isMmdActive && !isVanilaMmdModel) {

            if (YsmCompat.isYsmModelActive(player)) {
                if (YsmCompat.isDisableSelfModel()) {
                    return camera.getXRot() >= 0;
                }
                return false;
            }

            return camera.getXRot() >= 0;
        }

        return camera.isDetached();
    }

    /** 死亡布娃娃：实体渲染完成后画尸体（光照层由 RagdollManager 自己开关）。 */
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void mmd$onRenderLevelTail(PoseStack poseStack, float partialTick, long finishNanoTime,
                                       boolean renderBlockOutline, Camera camera,
                                       GameRenderer gameRenderer, LightTexture lightTexture,
                                       org.joml.Matrix4f projectionMatrix, CallbackInfo ci) {
        RagdollRenderEntrypoint.renderCorpses(poseStack, partialTick, camera);
    }
}
