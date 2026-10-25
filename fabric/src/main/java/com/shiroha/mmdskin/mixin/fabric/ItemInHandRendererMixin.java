/** 文件职责：隐藏第一人称原版手臂并在 VR 下接管手持物渲染。 */
package com.shiroha.mmdskin.mixin.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.client.MmdClientRenderRuntime;
import com.shiroha.mmdskin.compat.vr.VRArmHider;
import com.shiroha.mmdskin.config.UIConstants;
import com.shiroha.mmdskin.fabric.compat.YsmCompat;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemInHandRenderer.class, priority = 900)
public abstract class ItemInHandRendererMixin {
    /**
     * 26.2 适配：26.3 把 {@code ItemInHandRenderer} 拆成了
     * {@code FirstPersonHandsAndItemsRenderer}（手臂与手持物的绘制合并进
     * {@code submitHandsWithItems(...)}），26.2 还没拆，入口仍是
     * {@code ItemInHandRenderer.submitHandsWithItems(...)}，
     * 只是参数还是 {@code LocalPlayer} 而不是 26.3 的两个 render state。
     *
     * <p>语义与 26.3 一致：MMD 模型在第一人称下生效时，原版手臂（含手持物）整段不画。
     */
    @Inject(
            method = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V",
            at = @At("HEAD"), cancellable = true)
    private void onSubmitHandsWithItems(float partialTick,
                                        PoseStack poseStack,
                                        SubmitNodeCollector collector,
                                        LocalPlayer player,
                                        int light,
                                        CallbackInfo ci) {
        if (VRArmHider.shouldHideVRArms()) {
            ci.cancel();
            return;
        }

        if (player == null) {
            return;
        }

        String selectedModel = PlayerModelSyncManager.getPlayerModel(player.getUUID(), player.getName().getString(), true);
        boolean hasMmdModel = selectedModel != null
            && !selectedModel.isEmpty()
            && !UIConstants.DEFAULT_MODEL_NAME.equals(selectedModel);
        boolean useVanillaModel = hasMmdModel
            && ("VanilaModel".equals(selectedModel)
            || "VanillaModel".equals(selectedModel)
            || "vanila".equalsIgnoreCase(selectedModel)
            || "vanilla".equalsIgnoreCase(selectedModel));

        if (YsmCompat.isYsmModelActive(player)) {
            if (YsmCompat.isDisableSelfHands()) {
                ci.cancel();
            }
            return;
        }

        var runtime = MmdClientRenderRuntime.currentIfInstalled().orElse(null);
        if (runtime != null && runtime.firstPerson().session().desktopActive()
                && hasMmdModel && !useVanillaModel) {
            ci.cancel();
        }
    }
}
