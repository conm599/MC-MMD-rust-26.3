package com.shiroha.mmdskin.forge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.shiroha.mmdskin.MmdSkin;
import com.shiroha.mmdskin.config.GameDirectoryProvider;
import com.shiroha.mmdskin.forge.register.MmdSkinRegisterCommon;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MmdSkin.MOD_ID)
public class MmdSkinForge {
    public static final Logger logger = LogManager.getLogger();

    public MmdSkinForge() {
        // 游戏目录注入：房主（客户端兼服务端）与服主（专用服务器）都从这里拿，
        // 专用服务器上 Minecraft.getInstance() 是 null，不能用客户端那套取法。
        GameDirectoryProvider.setProvider(() -> net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toFile());
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::preInit);
    }

    public void preInit(FMLCommonSetupEvent event) {
        MmdSkinRegisterCommon.Register();
    }
}
