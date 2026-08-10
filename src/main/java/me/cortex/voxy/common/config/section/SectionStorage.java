package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

public abstract class SectionStorage implements IMappingStorage {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);

    /** Removes a persisted section so a remote provider can repopulate it. */
    public void deleteSection(long key) {
    }

    /** Called once after the owning world engine has constructed its Mapper. */
    public void setMapper(Mapper mapper) {
    }
}
