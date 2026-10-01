/* 文件职责：平台 LevelRendererMixin 调用尸体渲染的统一入口，隔离渲染循环与布娃娃异常。 */
package com.shiroha.mmdskin.player.ragdoll;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 尸体渲染统一入口。
 *
 * <p>渲染在 LevelRenderer.renderLevel 的 TAIL 注入点执行——此时引擎实体已渲染完毕，
 * 任何异常都不允许波及主渲染循环，因此这里兜底 try-catch（只告警前几次，防刷屏）。
 */
public final class RagdollRenderEntrypoint {
    private static final Logger logger = LogManager.getLogger();
    private static final int MAX_FAILURE_LOGS = 5;
    private static int failureCount;

    private RagdollRenderEntrypoint() {
    }

    public static void renderCorpses(PoseStack poseStack, float tickDelta, Camera camera) {
        if (RagdollManager.getInstance().corpseCount() == 0) {
            return;
        }
        try {
            RagdollManager.getInstance().renderCorpses(poseStack, tickDelta, camera);
        } catch (Exception e) {
            if (failureCount < MAX_FAILURE_LOGS) {
                failureCount++;
                logger.warn("尸体渲染异常（累计 {} 次）", failureCount, e);
            }
        }
    }
}
