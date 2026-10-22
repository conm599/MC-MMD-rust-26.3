package com.shiroha.mmdskin.forge.network;

import com.shiroha.mmdskin.forge.register.MmdSkinRegisterCommon;
import com.shiroha.mmdskin.forge.stage.ForgeStageSessionRegistry;
import com.shiroha.mmdskin.compat.maid.runtime.MaidMMDModelManager;
import com.shiroha.mmdskin.player.animation.PendingAnimSignalCache;
import com.shiroha.mmdskin.distrib.ModelDistributionClient;
import com.shiroha.mmdskin.distrib.ModelDistributionService;
import com.shiroha.mmdskin.player.sync.ClientNetworkBindings;
import com.shiroha.mmdskin.player.sync.PlayerModelSyncService;
import com.shiroha.mmdskin.player.runtime.MmdSkinRendererPlayerHelper;
import com.shiroha.mmdskin.player.sync.MorphSyncHelper;
import com.shiroha.mmdskin.player.sync.ServerModelRegistry;
import com.shiroha.mmdskin.ui.network.NetworkOpCode;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Forge 网络包序列化与处理
 */
public class MmdSkinNetworkPack {
    private static final Logger logger = LogManager.getLogger();

    public int opCode;
    public UUID playerUUID;
    public String animId;
    public int arg0;
    public byte[] binaryPayload;

    public static int toOpCode(ClientNetworkBindings.NetworkMessageType messageType) {
        return switch (messageType) {
            case CUSTOM_ANIM -> NetworkOpCode.CUSTOM_ANIM;
            case RESET_PHYSICS -> NetworkOpCode.RESET_PHYSICS;
            case MODEL_SELECT -> NetworkOpCode.MODEL_SELECT;
            case MORPH_SYNC -> NetworkOpCode.MORPH_SYNC;
            case STAGE_MULTI -> NetworkOpCode.STAGE_MULTI;
            case BONE_SYNC -> NetworkOpCode.BONE_SYNC;
            case DISTRIB_REQUEST -> NetworkOpCode.DISTRIB_REQUEST;
        };
    }

    public MmdSkinNetworkPack(int opCode, UUID playerUUID, String animId) {
        this.opCode = opCode;
        this.playerUUID = playerUUID;
        this.animId = animId;
        this.arg0 = 0;
        this.binaryPayload = new byte[0];
    }

    public MmdSkinNetworkPack(int opCode, UUID playerUUID, int arg0) {
        this.opCode = opCode;
        this.playerUUID = playerUUID;
        this.animId = "";
        this.arg0 = arg0;
        this.binaryPayload = new byte[0];
    }

    public MmdSkinNetworkPack(int opCode, UUID playerUUID, int entityId, String modelName) {
        this.opCode = opCode;
        this.playerUUID = playerUUID;
        this.animId = modelName;
        this.arg0 = entityId;
        this.binaryPayload = new byte[0];
    }

    public MmdSkinNetworkPack(int opCode, UUID playerUUID, byte[] binaryPayload) {
        this.opCode = opCode;
        this.playerUUID = playerUUID;
        this.animId = "";
        this.arg0 = 0;
        this.binaryPayload = binaryPayload != null ? binaryPayload : new byte[0];
    }

    public MmdSkinNetworkPack(FriendlyByteBuf buffer) {
        opCode = buffer.readInt();
        playerUUID = buffer.readUUID();

        if (opCode == NetworkOpCode.BONE_SYNC || NetworkOpCode.isDistributionBinaryPayload(opCode)) {
            animId = "";
            arg0 = 0;
            binaryPayload = buffer.readByteArray();
        } else if (NetworkOpCode.isStringPayload(opCode)) {
            animId = buffer.readUtf();
            arg0 = 0;
            binaryPayload = new byte[0];
        } else if (NetworkOpCode.isEntityStringPayload(opCode)) {
            arg0 = buffer.readInt();
            animId = buffer.readUtf();
            binaryPayload = new byte[0];
        } else {
            animId = "";
            arg0 = buffer.readInt();
            binaryPayload = new byte[0];
        }
    }

    public void pack(FriendlyByteBuf buffer) {
        buffer.writeInt(opCode);
        buffer.writeUUID(playerUUID);

        if (opCode == NetworkOpCode.BONE_SYNC || NetworkOpCode.isDistributionBinaryPayload(opCode)) {
            buffer.writeByteArray(binaryPayload != null ? binaryPayload : new byte[0]);
        } else if (NetworkOpCode.isStringPayload(opCode)) {
            buffer.writeUtf(animId);
        } else if (NetworkOpCode.isEntityStringPayload(opCode)) {
            buffer.writeInt(arg0);
            buffer.writeUtf(animId);
        } else {
            buffer.writeInt(arg0);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide() == net.minecraftforge.fml.LogicalSide.CLIENT) {
                doInClient();
            } else {
                handleOnServer(ctx.get());
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer sender = ctx.getSender();
        if (sender == null) return;

        if (!sender.getUUID().equals(playerUUID)) {
            logger.warn("UUID 不匹配，丢弃数据包: claimed={}, real={}", playerUUID, sender.getUUID());
            return;
        }

        if (opCode == NetworkOpCode.MODEL_SELECT) {
            ServerModelRegistry.updateModel(playerUUID, animId);
        }

        if (opCode == NetworkOpCode.REQUEST_ALL_MODELS) {
            ServerModelRegistry.sendAllTo((modelOwnerUUID, modelName) ->
                MmdSkinRegisterCommon.channel.send(
                    PacketDistributor.PLAYER.with(() -> sender),
                    new MmdSkinNetworkPack(NetworkOpCode.MODEL_SELECT, modelOwnerUUID, modelName)));
            return;
        }

    if (opCode == NetworkOpCode.DISTRIB_REQUEST) {
        int separator = animId.lastIndexOf('|');
        String modelName = separator > 0 ? animId.substring(0, separator) : animId;
        String firstIncompletePath = separator > 0 ? animId.substring(separator + 1) : "";
        ModelDistributionService.enqueueModel(sender, sender.getUUID(), modelName, firstIncompletePath);
        return;
    }

        if (opCode == NetworkOpCode.STAGE_MULTI) {
            if (sender.getServer() != null) {
                ForgeStageSessionRegistry.getInstance().handlePacket(sender.getServer(), sender, animId);
            }
            return;
        }

        MmdSkinRegisterCommon.channel.send(PacketDistributor.ALL.noArg(), this);
    }

    /** 模型分发的客户端处理（清单 / 详细清单 / 分块 / 完成）。 */
    private void handleDistribution() {
        if (opCode == NetworkOpCode.DISTRIB_CHUNK) {
            ModelDistributionClient.onChunk(binaryPayload);
            return;
        }
        if (opCode == NetworkOpCode.DISTRIB_COMPLETE) {
            ModelDistributionClient.onComplete(animId);
            return;
        }
        String payload = binaryPayload != null
                ? new String(binaryPayload, java.nio.charset.StandardCharsets.UTF_8)
                : "";
        if (opCode == NetworkOpCode.DISTRIB_MANIFEST) {
            ModelDistributionClient.onManifest(payload);
        } else if (opCode == NetworkOpCode.DISTRIB_OFFER) {
            ModelDistributionClient.onOfferPage(binaryPayload);
        }
    }

    private void doInClient() {
        // 分发包要在 "mc.player == null" 早退之前处理：
        // 玩家实体还没建好时收到的完成包，丢了模型就装不上了。
        if (NetworkOpCode.isDistribution(opCode)) {
            handleDistribution();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (opCode == NetworkOpCode.STAGE_MULTI) {
            com.shiroha.mmdskin.stage.client.StageClientPacketHandler.getInstance().handle(playerUUID, animId);
            return;
        }
        if (playerUUID.equals(mc.player.getUUID())) return;
        if (mc.level == null) return;

        Player target = mc.level.getPlayerByUUID(playerUUID);

        switch (opCode) {
            case NetworkOpCode.CUSTOM_ANIM -> {
                if (target != null) MmdSkinRendererPlayerHelper.CustomAnim(target, animId);
            }
            case NetworkOpCode.RESET_PHYSICS -> {
                if (target != null) {
                    MmdSkinRendererPlayerHelper.ResetPhysics(target);
                } else {
                    PendingAnimSignalCache.put(playerUUID, PendingAnimSignalCache.SignalType.RESET);
                }
            }
            case NetworkOpCode.MODEL_SELECT -> {
                PlayerModelSyncService.onRemotePlayerModelReceived(playerUUID, animId);
            }
            case NetworkOpCode.MAID_MODEL -> {
                Entity maidEntity = mc.level.getEntity(arg0);
                if (maidEntity != null) MaidMMDModelManager.bindModel(maidEntity.getUUID(), animId);
            }
            case NetworkOpCode.MAID_ACTION -> {
                Entity maidEntity = mc.level.getEntity(arg0);
                if (maidEntity != null) MaidMMDModelManager.playAnimation(maidEntity.getUUID(), animId);
            }
            case NetworkOpCode.MORPH_SYNC -> {
                if (target != null) MorphSyncHelper.applyRemoteMorph(target, animId);
            }
            default -> {}
        }
    }
}

