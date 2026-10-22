package com.shiroha.mmdskin.forge.register;

import com.shiroha.mmdskin.forge.network.MmdSkinNetworkPack;
import com.shiroha.mmdskin.forge.stage.ForgeStageSessionRegistry;
import com.shiroha.mmdskin.distrib.ModelDistributionService;
import com.shiroha.mmdskin.player.sync.ServerModelRegistry;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge 服务端网络注册
 */
public class MmdSkinRegisterCommon {
    public static SimpleChannel channel;
    static String networkVersion = "1";

    public static void Register() {
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation("3d-skin", "network_pack"),
                () -> networkVersion,
                NetworkRegistry.acceptMissingOr(networkVersion),
                version -> version.equals(networkVersion));

        channel.registerMessage(0, MmdSkinNetworkPack.class,
                MmdSkinNetworkPack::pack, MmdSkinNetworkPack::new, MmdSkinNetworkPack::handle);

        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            ServerModelRegistry.onPlayerLeave(event.getEntity().getUUID());
            ModelDistributionService.onPlayerLeave(event.getEntity().getUUID());
            if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
                ForgeStageSessionRegistry.getInstance().onPlayerDisconnect(player.getServer(), player);
            }
        });

        // 服务端 tick：按房主配置的速率把排队中的模型分块推给客户端；
        // 房主改了配置就实时把新清单推给在线玩家（不用重连）
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            ModelDistributionService.tickServer();
            if (ModelDistributionService.pollConfigChange()) {
                byte[] payload = ModelDistributionService.buildManifestList();
                if (payload == null || payload.length == 0) {
                    return;
                }
                for (ServerPlayer online : event.getServer().getPlayerList().getPlayers()) {
                    channel.send(PacketDistributor.PLAYER.with(() -> online),
                            new MmdSkinNetworkPack(NetworkOpCode.DISTRIB_MANIFEST, online.getUUID(), payload));
                }
            }
        });

        // 玩家进服：推送可分发模型清单
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                sendManifestTo(player);
            }
        });

        ModelDistributionService.setSender(new ModelDistributionService.Sender() {
            @Override
            public void sendManifest(Object handle, byte[] payload) {
                sendTo((ServerPlayer) handle, NetworkOpCode.DISTRIB_MANIFEST, payload);
            }

            @Override
            public void sendOffer(Object handle, byte[] payload) {
                sendTo((ServerPlayer) handle, NetworkOpCode.DISTRIB_OFFER, payload);
            }

            @Override
            public void sendChunk(Object handle, byte[] payload) {
                sendTo((ServerPlayer) handle, NetworkOpCode.DISTRIB_CHUNK, payload);
            }

            @Override
            public void sendComplete(Object handle, String modelName) {
                ServerPlayer player = (ServerPlayer) handle;
                if (player == null) {
                    return;
                }
                channel.send(PacketDistributor.PLAYER.with(() -> player),
                        new MmdSkinNetworkPack(NetworkOpCode.DISTRIB_COMPLETE, player.getUUID(),
                                modelName == null ? "" : modelName));
            }

            private void sendTo(ServerPlayer player, int opCode, byte[] payload) {
                if (player == null) {
                    return;
                }
                channel.send(PacketDistributor.PLAYER.with(() -> player),
                        new MmdSkinNetworkPack(opCode, player.getUUID(), payload));
            }
        });
    }

    /** 推送可分发模型清单给玩家。 */
    public static void sendManifestTo(ServerPlayer player) {
        if (player == null || !ModelDistributionService.isEnabled()) {
            return;
        }
        byte[] payload = ModelDistributionService.buildManifestList();
        if (payload == null || payload.length == 0) {
            return;
        }
        channel.send(PacketDistributor.PLAYER.with(() -> player),
                new MmdSkinNetworkPack(NetworkOpCode.DISTRIB_MANIFEST, player.getUUID(), payload));
    }
}

