package me.cortex.voxy.server;

import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public final class VoxyServerInstance extends VoxyInstance {
    private final MinecraftServer server;
    private final Path basePath;
    private final SectionStorageConfig storageConfig;

    public VoxyServerInstance(MinecraftServer server) {
        this.server = server;
        this.basePath = server.getWorldPath(LevelResource.ROOT)
                .resolve("voxy")
                .resolve("server");
        this.storageConfig = StorageConfigUtil.getCreateStorageConfig(
                StorageDefinition.class,
                definition -> definition.version == 1 && definition.sectionStorageConfig != null,
                VoxyServerInstance::defaultStorageDefinition,
                this.basePath).sectionStorageConfig;
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        this.setNumThreads(VoxyServerConfig.serviceThreads());
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var context = new ConfigBuildCtx();
        context.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        context.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        context.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.storageConfig.build(context);
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

    private static StorageDefinition defaultStorageDefinition() {
        var definition = new StorageDefinition();
        definition.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        return definition;
    }

    private static final class StorageDefinition {
        int version = 1;
        SectionStorageConfig sectionStorageConfig;
    }
}
