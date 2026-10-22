package com.shiroha.mmdskin.config;

import java.io.File;

/**
 * 文件职责：解析模组使用的游戏根目录（房主 / 服主两种部署都要能用）。
 *
 * <p><b>为什么不能直接用 {@code Minecraft.getInstance().gameDirectory}：</b>
 * 那个静态 instance 字段只有客户端 {@code Minecraft} 构造器才会赋值。
 * 专用服务器（服主）走的是 {@code net.minecraft.server.Main}，
 * <b>根本不创建客户端 Minecraft 实例</b>，所以 {@code Minecraft.getInstance()} 返回 null，
 * 直接取 gameDirectory 会 NPE。
 *
 * <p>所以游戏目录由平台层注入：
 * <ul>
 *   <li>房主（单人 / 局域网）：客户端兼服务端，注入的是客户端的 gameDir；</li>
 *   <li>服主（专用服务器）：纯服务端，注入的是服务器目录（loader 的 gameDir）。</li>
 * </ul>
 * 两者最终都指向"这台机器上装模组的那份数据目录"。
 */
public final class GameDirectoryProvider {

    /** 平台层注入的游戏目录提供者。 */
    public interface Provider {
        File gameDirectory();
    }

    private static volatile Provider provider;

    private GameDirectoryProvider() {
    }

    public static void setProvider(Provider value) {
        provider = value;
    }

    public static boolean hasProvider() {
        return provider != null;
    }

    /**
     * 取游戏根目录。
     *
     * <p><b>这里刻意不引用任何客户端类</b>：专用服务器（服主）的 jar 里没有
     * {@code net.minecraft.client.Minecraft}，一旦在这里碰到它就会 NoClassDefFoundError。
     *
     * <p>平台层必须在模组公共入口注入 provider（Fabric 的 {@code onInitialize()} /
     * Forge 的构造器），那是房主与服主都会走到的路径。万一没注入，回退到当前工作目录
     * —— 专用服务器通常就是以服务器目录为工作目录启动的。
     */
    public static File get() {
        Provider current = provider;
        if (current != null) {
            try {
                File dir = current.gameDirectory();
                if (dir != null) {
                    return dir;
                }
            } catch (Exception ignored) {
                // 平台层 provider 失败时继续走回退路径
            }
        }
        return new File(".");
    }
}
