package de.maxhenkel.voicechat.voice.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.VoicechatClient;
import de.maxhenkel.voicechat.gui.onboarding.OnboardingManager;
import de.maxhenkel.voicechat.intercompatibility.ClientCompatibilityManager;
import de.maxhenkel.voicechat.plugins.ClientPluginManager;
import de.maxhenkel.voicechat.voice.common.AudioUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

public class RenderEvents {

    private static final Identifier MICROPHONE_ICON = Identifier.fromNamespaceAndPath(Voicechat.MODID, "icons/microphone");
    private static final Identifier WHISPER_MICROPHONE_ICON = Identifier.fromNamespaceAndPath(Voicechat.MODID, "icons/microphone_whisper");
    private static final Identifier MICROPHONE_OFF_ICON = Identifier.fromNamespaceAndPath(Voicechat.MODID, "icons/microphone_off");
    private static final Identifier SPEAKER_ICON = Identifier.fromNamespaceAndPath(Voicechat.MODID, "icons/speaker");
    private static final Identifier WHISPER_SPEAKER_ICON = Identifier.fromNamespaceAndPath(Voicechat.MODID, "icons/speaker_whisper");
    private static final Identifier SPEAKER_OFF_ICON = Identifier.fromNamespaceAndPath(Voicechat.MODID, "icons/speaker_off");
    private static final Identifier DISCONNECT_ICON = Identifier.fromNamespaceAndPath(Voicechat.MODID, "icons/disconnected");
    private static final Identifier GROUP_ICON = Identifier.fromNamespaceAndPath(Voicechat.MODID, "icons/group");

    private final Minecraft minecraft;

    public RenderEvents() {
        minecraft = Minecraft.getInstance();
        ClientCompatibilityManager.INSTANCE.onRenderNamePlate(this::onRenderName);
        ClientCompatibilityManager.INSTANCE.onRenderHUD(this::onRenderHUD);
    }

    private void onRenderHUD(GuiGraphicsExtractor guiGraphics, float tickDelta) {
        if (!shouldShowIcons()) {
            return;
        }
        if (VoicechatClient.CLIENT_CONFIG.hideIcons.get()) {
            return;
        }
        if (!VoicechatClient.CLIENT_CONFIG.showHudIcons.get()) {
            return;
        }

        ClientPlayerStateManager manager = ClientManager.getPlayerStateManager();
        ClientVoicechat client = ClientManager.getClient();

        if (manager.isDisconnected() && isStartup()) {
            return;
        }

        if (manager.isDisconnected()) {
            renderIcon(guiGraphics, DISCONNECT_ICON);
        } else if (manager.isDisabled()) {
            renderIcon(guiGraphics, SPEAKER_OFF_ICON);
        } else if (manager.isMuted() && VoicechatClient.CLIENT_CONFIG.microphoneActivationType.get().equals(MicrophoneActivationType.VOICE)) {
            renderIcon(guiGraphics, MICROPHONE_OFF_ICON);
        } else if (client != null && client.getMicThread() != null) {
            if (client.getMicThread().isWhispering()) {
                renderIcon(guiGraphics, WHISPER_MICROPHONE_ICON);
            } else if (client.getMicThread().isTalking()) {
                renderIcon(guiGraphics, MICROPHONE_ICON);
            }
        }

        if (manager.getGroupID() != null && VoicechatClient.CLIENT_CONFIG.showGroupHud.get()) {
            GroupChatManager.renderIcons(guiGraphics);
        }
    }

    private boolean isStartup() {
        ClientVoicechat client = ClientManager.getClient();
        return client != null && (System.currentTimeMillis() - client.getStartTime()) < 5000;
    }

    private void renderIcon(GuiGraphicsExtractor guiGraphics, Identifier texture) {
        guiGraphics.pose().pushMatrix();
        int posX = VoicechatClient.CLIENT_CONFIG.hudIconPosX.get();
        int posY = VoicechatClient.CLIENT_CONFIG.hudIconPosY.get();
        if (posX < 0) {
            guiGraphics.pose().translate(minecraft.getWindow().getGuiScaledWidth(), 0F);
        }
        if (posY < 0) {
            guiGraphics.pose().translate(0F, minecraft.getWindow().getGuiScaledHeight());
        }
        guiGraphics.pose().translate(posX, posY);
        float scale = VoicechatClient.CLIENT_CONFIG.hudIconScale.get().floatValue();
        guiGraphics.pose().scale(scale, scale);

        guiGraphics.blitSprite(RenderPipelines.GUI_TEXTURED, texture, posX < 0 ? -16 : 0, posY < 0 ? -16 : 0, 16, 16);
        guiGraphics.pose().popMatrix();
    }

    private void onRenderName(EntityRenderState s, CameraRenderState cameraRenderState, PoseStack stack, SubmitNodeCollector collector) {
        if (!(s instanceof AvatarRenderState state)) {
            return;
        }
        Component nameTag = s.nameTag;
        if (nameTag == null) {
            return;
        }
        if (s.nameTagAttachment == null) {
            return;
        }
        if (!shouldShowIcons()) {
            return;
        }
        if (VoicechatClient.CLIENT_CONFIG.hideIcons.get()) {
            return;
        }
        if (!VoicechatClient.CLIENT_CONFIG.showNametagIcons.get()) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(state.id);
        if (entity == null || entity.equals(minecraft.player)) {
            return;
        }
        if (minecraft.gui.hud.isHidden()) {
            return;
        }
        ClientPlayerStateManager manager = ClientManager.getPlayerStateManager();
        ClientVoicechat client = ClientManager.getClient();
        UUID entityId = entity.getUUID();
        UUID groupId = manager.getGroup(entityId);

        stack.pushPose();
        stack.translate(state.nameTagAttachment);
        stack.translate(0D, 0.5D, 0D);
        stack.mulPose(cameraRenderState.orientation);
        stack.scale(0.025F, -0.025F, 0.025F);

        if (client != null && client.getTalkCache().isWhispering(entityId)) {
            renderPlayerIcon(entityId, state.isDiscrete, nameTag, WHISPER_SPEAKER_ICON, true, stack, collector, state.lightCoords);
        } else if (client != null && client.getTalkCache().isTalking(entityId)) {
            renderPlayerIcon(entityId, state.isDiscrete, nameTag, SPEAKER_ICON, true, stack, collector, state.lightCoords);
        } else if (manager.isPlayerDisconnected(entityId)) {
            renderPlayerIcon(entityId, state.isDiscrete, nameTag, DISCONNECT_ICON, false, stack, collector, state.lightCoords);
        } else if (groupId != null && !groupId.equals(manager.getGroupID())) {
            renderPlayerIcon(entityId, state.isDiscrete, nameTag, GROUP_ICON, false, stack, collector, state.lightCoords);
        } else if (manager.isPlayerDisabled(entityId)) {
            renderPlayerIcon(entityId, state.isDiscrete, nameTag, SPEAKER_OFF_ICON, false, stack, collector, state.lightCoords);
        }

        stack.popPose();
    }

    private void renderPlayerIcon(UUID entityId, boolean discrete, Component component, Identifier texture, boolean talking, PoseStack stack, SubmitNodeCollector collector, int light) {
        if (!ClientPluginManager.instance().shouldRenderPlayerIcons(entityId)) {
            return;
        }
        float offsetX = (float) (minecraft.font.width(component) / 2 + 2);
        int alpha = 127;
        float offsetY = -1F;
        float left = offsetX;
        float right = offsetX + 10F;
        float top = offsetY;
        float bottom = offsetY + 10F;
        if (talking) {
            ClientVoicechat client = ClientManager.getClient();
            double audioLevel = client == null ? AudioUtils.LOWEST_DB : client.getTalkCache().getPlayerAudioLevel(entityId);
            float volume = (float) AudioUtils.dbToPerc(audioLevel);
            double phase = System.currentTimeMillis() * 0.012D + entityId.hashCode();
            float scale = 1F + volume * 0.15F + ((float) Math.sin(phase) + 1F) * 0.01F;
            float jitterX = (float) Math.sin(phase * 1.7D) * 0.3F;
            float jitterY = (float) Math.cos(phase * 1.3D) * 0.2F;
            float centerX = offsetX + 5F;
            float centerY = offsetY + 5F;
            left = centerX + (offsetX - centerX) * scale + jitterX;
            right = centerX + (offsetX + 10F - centerX) * scale + jitterX;
            top = centerY + (offsetY - centerY) * scale + jitterY;
            bottom = centerY + (offsetY + 10F - centerY) * scale + jitterY;
        }
        final float iconLeft = left;
        final float iconRight = right;
        final float iconTop = top;
        final float iconBottom = bottom;
        TextureAtlas guiAtlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.GUI);
        TextureAtlasSprite sprite = guiAtlas.getSprite(texture);
        collector.submitCustomGeometry(stack, RenderTypes.text(sprite.atlasLocation()), (pose, c) -> {
            if (discrete) {
                vertex(c, pose, iconLeft, iconBottom, 0F, sprite.getU0(), sprite.getV1(), alpha, light);
                vertex(c, pose, iconRight, iconBottom, 0F, sprite.getU1(), sprite.getV1(), alpha, light);
                vertex(c, pose, iconRight, iconTop, 0F, sprite.getU1(), sprite.getV0(), alpha, light);
                vertex(c, pose, iconLeft, iconTop, 0F, sprite.getU0(), sprite.getV0(), alpha, light);
            } else {
                vertex(c, pose, iconLeft, iconBottom, 0F, sprite.getU0(), sprite.getV1(), light);
                vertex(c, pose, iconRight, iconBottom, 0F, sprite.getU1(), sprite.getV1(), light);
                vertex(c, pose, iconRight, iconTop, 0F, sprite.getU1(), sprite.getV0(), light);
                vertex(c, pose, iconLeft, iconTop, 0F, sprite.getU0(), sprite.getV0(), light);
            }
        });
        if (!discrete) {
            collector.submitCustomGeometry(stack, RenderTypes.textSeeThrough(sprite.atlasLocation()), (pose, c) -> {
                vertex(c, pose, iconLeft, iconBottom, 0F, sprite.getU0(), sprite.getV1(), alpha, light);
                vertex(c, pose, iconRight, iconBottom, 0F, sprite.getU1(), sprite.getV1(), alpha, light);
                vertex(c, pose, iconRight, iconTop, 0F, sprite.getU1(), sprite.getV0(), alpha, light);
                vertex(c, pose, iconLeft, iconTop, 0F, sprite.getU0(), sprite.getV0(), alpha, light);
            });
        }
    }

    private boolean shouldShowIcons() {
        if (OnboardingManager.isOnboarding()) {
            return false;
        }
        if (ClientManager.getClient() != null && ClientManager.getClient().getConnection() != null && ClientManager.getClient().getConnection().isInitialized()) {
            return true;
        }
        return minecraft.getSingleplayerServer() == null || minecraft.getSingleplayerServer().isPublished();
    }

    private static void vertex(VertexConsumer builder, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light) {
        vertex(builder, pose, x, y, z, u, v, 255, light);
    }

    private static void vertex(VertexConsumer builder, PoseStack.Pose pose, float x, float y, float z, float u, float v, int alpha, int light) {
        builder.addVertex(pose.pose(), x, y, z)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0F, 0F, -1F);
    }

}
