package com.shiroha.mmdskin.mixin.forge;

import com.shiroha.mmdskin.player.ragdoll.RagdollManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 死亡界面劫持：透明死亡屏（只留一行提示）+ 单击自由观战 + 双击左键复活。
 * 困难模式完全放行原版行为。
 */
@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin extends Screen {

    /** 双击判定窗口（毫秒）。 */
    @Unique
    private static final long MMD$DOUBLE_CLICK_WINDOW_MS = 350L;

    @Unique
    private long mmd$lastClickMs;
    @Unique
    private double mmd$lastMouseX = -1.0;
    @Unique
    private double mmd$lastMouseY = -1.0;

    protected DeathScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private boolean mmd$hijackActive() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null
                && minecraft.player != null
                && !minecraft.level.getLevelData().isHardcore()
                && RagdollManager.getInstance().isDeathScreenHijackActive();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void mmd$onRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!mmd$hijackActive()) {
            return;
        }
        // 跳过原版红色渐晕 / 标题 / 按钮，只画一行操作提示
        ci.cancel();

        RagdollManager ragdoll = RagdollManager.getInstance();
        String key = ragdoll.isDeathCamObserve()
                ? "gui.mmdskin.death_screen_hint_observe"
                : "gui.mmdskin.death_screen_hint";
        graphics.drawCenteredString(this.font, Component.translatable(key),
                this.width / 2, this.height / 4 + 40, 0xFFFFFFFF);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void mmd$onMouseClicked(double mouseX, double mouseY, int button,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || !mmd$hijackActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long now = Util.getMillis();
        if (now - mmd$lastClickMs <= MMD$DOUBLE_CLICK_WINDOW_MS) {
            // 双击：复活
            minecraft.player.respawn();
            minecraft.setScreen(null);
            mmd$lastClickMs = 0L;
        } else {
            // 单击：切换自由观战（鼠标环视 / 滚轮缩放）
            RagdollManager.getInstance().toggleDeathCamObserve();
            mmd$lastClickMs = now;
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseMoved", at = @At("HEAD"))
    private void mmd$onMouseMoved(double mouseX, double mouseY, CallbackInfo ci) {
        if (mmd$lastMouseX < 0.0) {
            mmd$lastMouseX = mouseX;
            mmd$lastMouseY = mouseY;
            return;
        }
        if (RagdollManager.getInstance().isDeathCamObserve()) {
            RagdollManager.getInstance().deathCamMouseMove(
                    mouseX - mmd$lastMouseX, mouseY - mmd$lastMouseY);
        }
        mmd$lastMouseX = mouseX;
        mmd$lastMouseY = mouseY;
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void mmd$onMouseScrolled(double mouseX, double mouseY, double delta,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!RagdollManager.getInstance().isDeathCamObserve()) {
            return;
        }
        RagdollManager.getInstance().deathCamScroll(delta);
        cir.setReturnValue(true);
    }
}
