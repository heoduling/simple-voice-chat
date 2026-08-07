package de.maxhenkel.voicechat.plugins.impl;

import de.maxhenkel.voicechat.api.ServerLevel;
import org.bukkit.World;

import java.util.UUID;

public class ServerLevelImpl implements ServerLevel {

    private final World serverLevel;
    private final UUID worldId;

    public ServerLevelImpl(World serverLevel) {
        this.serverLevel = serverLevel;
        this.worldId = serverLevel.getUID();
    }

    @Override
    public Object getServerLevel() {
        return serverLevel;
    }

    public UUID getWorldId() {
        return worldId;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        ServerLevelImpl that = (ServerLevelImpl) object;
        return worldId.equals(that.worldId);
    }

    @Override
    public int hashCode() {
        return worldId.hashCode();
    }
}
