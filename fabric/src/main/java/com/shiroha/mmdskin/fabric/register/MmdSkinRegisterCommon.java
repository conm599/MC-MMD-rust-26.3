package com.shiroha.mmdskin.fabric.register;

import com.shiroha.mmdskin.distrib.ModelDistributionService;
import com.shiroha.mmdskin.fabric.stage.FabricStageSessionRegistry;
import com.shiroha.mmdskin.player.sync.ServerModelRegistry;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/**
 * Fabric 服务端网络注册
 */
public class MmdSkinRegisterCommon {
    private static final Logger logger = LogManager.getLogger();

    public static ResourceLocation SKIN_C2S = new ResourceLocation("3d-skin", "network_c2s");
    public static ResourceLocation SKIN_S2C = new ResourceLocation("3d-skin", "network_s2c");

    /** 推送可分发模型清单给玩家。 */
    public static void sendManifestTo(ServerPlayer player) {
        if (player == null || !ModelDistributionService.isEnabled()) {
            return;
        }
        byte[] payload = ModelDistributionService.buildManifestList();
        if (payload == null || payload.length == 0) {
            return;
        }
        FriendlyByteBuf buffer = PacketByteBufs.create();
        buffer.writeInt(NetworkOpCode.DISTRIB_MANIFEST);
        buffer.writeUUID(player.getUUID());
        buffer.writeByteArray(payload);
        ServerPlayNetworking.send(player, SKIN_S2C, buffer);
    }

    public static void Register() {
        ServerPlayNetworking.registerGlobalReceiver(SKIN_C2S, (server, player, handler, buf, responseSender) -> {
            int opCode = buf.readInt();
            UUID claimedUUID = buf.readUUID();

            UUID realUUID = player.getUUID();
            if (!realUUID.equals(claimedUUID)) {
                logger.warn("UUID 不匹配，丢弃数据包: claimed={}, real={}", claimedUUID, realUUID);
                return;
            }

            String strData = null;
            byte[] binaryData = null;
            int entityId = 0;
            int intArg = 0;

            if (opCode == NetworkOpCode.BONE_SYNC) {
                binaryData = buf.readByteArray();
            } else if (NetworkOpCode.isStringPayload(opCode)) {
                strData = buf.readUtf();
            } else if (NetworkOpCode.isEntityStringPayload(opCode)) {
                entityId = buf.readInt();
                strData = buf.readUtf();
            } else {
                intArg = buf.readInt();
            }

            if (opCode == NetworkOpCode.MODEL_SELECT && strData != null) {
                ServerModelRegistry.updateModel(realUUID, strData);
            }

            if (opCode == NetworkOpCode.REQUEST_ALL_MODELS) {
                server.execute(() -> {
                    ServerModelRegistry.sendAllTo((modelOwnerUUID, modelName) -> {
                        FriendlyByteBuf replyBuf = PacketByteBufs.create();
                        replyBuf.writeInt(NetworkOpCode.MODEL_SELECT);
                        replyBuf.writeUUID(modelOwnerUUID);
                        replyBuf.writeUtf(modelName);
                        ServerPlayNetworking.send(player, SKIN_S2C, replyBuf);
                    });
                });
                return;
            }

            if (opCode == NetworkOpCode.DISTRIB_REQUEST && strData != null) {
                final String request = strData;
                server.execute(() -> {
                    int separator = request.lastIndexOf('|');
                    String modelName = separator > 0 ? request.substring(0, separator) : request;
                    String firstIncompletePath = separator > 0 ? request.substring(separator + 1) : "";
                    ModelDistributionService.enqueueModel(player, player.getUUID(), modelName, firstIncompletePath);
                });
                return;
            }

            if (opCode == NetworkOpCode.STAGE_MULTI && strData != null) {
                final String stagePayload = strData;
                server.execute(() -> FabricStageSessionRegistry.getInstance().handlePacket(server, player, stagePayload));
                return;
            }

            final FriendlyByteBuf packetBuf = PacketByteBufs.create();
            packetBuf.writeInt(opCode);
            packetBuf.writeUUID(realUUID);

            if (opCode == NetworkOpCode.BONE_SYNC) {
                packetBuf.writeByteArray(binaryData != null ? binaryData : new byte[0]);
            } else if (NetworkOpCode.isStringPayload(opCode)) {
                packetBuf.writeUtf(strData);
            } else if (NetworkOpCode.isEntityStringPayload(opCode)) {
                packetBuf.writeInt(entityId);
                packetBuf.writeUtf(strData);
            } else {
                packetBuf.writeInt(intArg);
            }

            server.execute(() -> {
                for (ServerPlayer serverPlayer : PlayerLookup.all(server)) {
                    if (!serverPlayer.equals(player)) {
                        FriendlyByteBuf copyBuf = PacketByteBufs.copy(packetBuf);
                        ServerPlayNetworking.send(serverPlayer, SKIN_S2C, copyBuf);
                    }
                }
            });
        });

        // 玩家进服：推送可分发模型清单
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> sendManifestTo(handler.getPlayer()));

        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {
                    ServerModelRegistry.onPlayerLeave(handler.getPlayer().getUUID());
                    ModelDistributionService.onPlayerLeave(handler.getPlayer().getUUID());
                    FabricStageSessionRegistry.getInstance().onPlayerDisconnect(server, handler.getPlayer());
                });

        // 服务端 tick：按房主配置的速率把排队中的模型分块推给客户端；
        // 房主改了配置就实时把新清单推给在线玩家（不用重连）
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    ModelDistributionService.tickServer();
                    if (ModelDistributionService.pollConfigChange()) {
                        byte[] payload = ModelDistributionService.buildManifestList();
                        if (payload != null && payload.length > 0) {
                            for (ServerPlayer online : PlayerLookup.all(server)) {
                                FriendlyByteBuf buffer = PacketByteBufs.create();
                                buffer.writeInt(NetworkOpCode.DISTRIB_MANIFEST);
                                buffer.writeUUID(online.getUUID());
                                buffer.writeByteArray(payload);
                                ServerPlayNetworking.send(online, SKIN_S2C, buffer);
                            }
                        }
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
                FriendlyByteBuf buffer = PacketByteBufs.create();
                buffer.writeInt(NetworkOpCode.DISTRIB_COMPLETE);
                buffer.writeUUID(player.getUUID());
                buffer.writeUtf(modelName == null ? "" : modelName);
                ServerPlayNetworking.send(player, SKIN_S2C, buffer);
            }

            private void sendTo(ServerPlayer player, int opCode, byte[] payload) {
                if (player == null) {
                    return;
                }
                FriendlyByteBuf buffer = PacketByteBufs.create();
                buffer.writeInt(opCode);
                buffer.writeUUID(player.getUUID());
                buffer.writeByteArray(payload == null ? new byte[0] : payload);
                ServerPlayNetworking.send(player, SKIN_S2C, buffer);
            }
        });
    }
}

