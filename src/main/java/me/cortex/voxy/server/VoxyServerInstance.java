package me.cortex.voxy.server;

import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public final class VoxyServerInstance extends VoxyInstance {
    private final MinecraftServer server;
    private final Path basePath;

    public VoxyServerInstance(MinecraftServer server) {
        this.server = server;
        this.basePath = server.getWorldPath(LevelResource.ROOT)
                .resolve("voxy")
                .resolve("server");
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        this.setNumThreads(VoxyServerConfig.serviceThreads());
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        return new ServerSectionStorage(this.basePath
                .resolve(identifier.getWorldId())
                .resolve("storage-v1"));
    }

    @Override
    protected void onWorldCreated(WorldIdentifier identifier, WorldEngine world) {
        String dimension = identifier.key.location().toString();
        world.setDirtyCallback((section, updateFlags, neighborMask) -> {
            if ((updateFlags & (WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT)) != 0) {
                VoxyServerNetwork.queueInvalidation(this.server, dimension, section.key);
            }
        });
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return VoxyServerConfig.isEnabled();
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    public String getStatus() {
        return "storage=" + this.basePath
                + ",ingestQueue=" + this.getIngestService().getTaskCount()
                + ",threads=" + VoxyServerConfig.serviceThreads();
    }

}
