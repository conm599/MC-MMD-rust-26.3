package com.shiroha.mmdskin.render.backend;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * GL 资源销毁的线程调度。
 *
 * <p>OpenGL 上下文是线程绑定的：在非渲染线程调用 glDelete* 时，LWJGL 会抛
 * "No context is current" 并直接 abort 整个 JVM（不会抛 Java 异常，也没有崩溃报告，
 * 世界也存不下来）。断线/退出时模型清理链路跑在 Netty IO 线程上，
 * 因此所有 GL 销毁动作都必须经由这里交回渲染线程。
 */
public final class GlDisposal {

    private GlDisposal() {
    }

    /**
     * 把 GL 销毁动作调度到渲染线程。
     *
     * <p>已经在渲染线程上就就地执行（保持「帧间隙立即释放」的原有语义），
     * 否则排进渲染任务队列，等渲染线程执行。
     *
     * <p>注意：1.20.1 Mojmap 的 {@code RenderSystem.recordRenderCall} 收的是
     * {@code com.mojang.blaze3d.pipeline.RenderCall}（只有一个 execute() 方法的函数式接口），
     * <b>不是</b> {@link Runnable}——直接传 Runnable 变量编译不过，必须用方法引用转一下。
     */
    public static void onRenderThread(Runnable action) {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            action.run();
        } else {
            RenderSystem.recordRenderCall(action::run);
        }
    }
}
