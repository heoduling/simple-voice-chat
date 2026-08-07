package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.Key;
import de.maxhenkel.voicechat.voice.server.Server;
import de.maxhenkel.voicechat.voice.server.ServerVoiceEvents;
import org.bukkit.entity.Player;

public class UpdateStatePacket implements Packet<UpdateStatePacket> {

    public static final Key PLAYER_STATE = Voicechat.compatibility.createNamespacedKey("update_state");

    private boolean disabled;

    public UpdateStatePacket() {

    }

    public UpdateStatePacket(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isDisabled() {
        return disabled;
    }

    @Override
    public Key getID() {
        return PLAYER_STATE;
    }

    @Override
    public void onPacket(Player player) {
        ServerVoiceEvents events = Voicechat.SERVER;
        Server server = events == null ? null : events.getServer();
        if (server != null) {
            server.getPlayerStateManager().onUpdateStatePacket(player, this);
        }
    }

    @Override
    public UpdateStatePacket fromBytes(FriendlyByteBuf buf) {
        disabled = buf.readBoolean();
        return this;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBoolean(disabled);
    }

}
