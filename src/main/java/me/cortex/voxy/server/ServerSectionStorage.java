package me.cortex.voxy.server;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldSection;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongConsumer;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Dedicated-server persistence that deliberately has no LWJGL dependency. */
final class ServerSectionStorage extends SectionStorage {
    private static final int MAGIC = 0x56585331; // VXS1
    private static final int HEADER_SIZE = Integer.BYTES + Long.BYTES + Integer.BYTES;
    private static final int SECTION_SIZE = HEADER_SIZE + WorldSection.SECTION_VOLUME * Long.BYTES;
    private static final byte SECTION_KEY = 1;
    private static final byte MAPPING_KEY = 2;

    private final Options options;
    private final ReadOptions readOptions;
    private final WriteOptions writeOptions;
    private final RocksDB db;

    ServerSectionStorage(Path path) {
        try {
            Files.createDirectories(path);
        } catch (Exception error) {
            throw new RuntimeException("Unable to create server Voxy storage at " + path, error);
        }

        RocksDB.loadLibrary();
        this.options = new Options()
                .setCreateIfMissing(true)
                .setAvoidUnnecessaryBlockingIO(true)
                .setIncreaseParallelism(2);
        this.readOptions = new ReadOptions();
        this.writeOptions = new WriteOptions();
        try {
            this.db = RocksDB.open(this.options, path.toString());
        } catch (RocksDBException error) {
            this.writeOptions.close();
            this.readOptions.close();
            this.options.close();
            throw new RuntimeException("Unable to open server Voxy storage at " + path, error);
        }
    }

    @Override
    public int loadSection(WorldSection into) {
        try {
            byte[] compressed = this.db.get(this.readOptions, sectionKey(into.key));
            if (compressed == null) return 1;
            byte[] encoded;
            try {
                encoded = decompress(compressed);
            } catch (IOException error) {
                return discardInvalid(into, "invalid compressed data");
            }

            ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
            int magic = input.getInt();
            long storedKey = input.getLong();
            byte nonEmptyChildren = input.get();
            input.position(HEADER_SIZE);
            if (magic != MAGIC || storedKey != into.key) return discardInvalid(into, "invalid header");

            long[] data = into._unsafeGetRawDataArray();
            for (int i = 0; i < data.length; i++) data[i] = input.getLong();
            into.applyRemoteData(data, nonEmptyChildren);
            return 0;
        } catch (RocksDBException error) {
            throw new RuntimeException(error);
        }
    }

    @Override
    public void saveSection(WorldSection section) {
        ByteBuffer output = ByteBuffer.allocate(SECTION_SIZE).order(ByteOrder.BIG_ENDIAN);
        output.putInt(MAGIC);
        output.putLong(section.key);
        output.put(section.getNonEmptyChildren());
        output.position(HEADER_SIZE);
        for (long value : section._unsafeGetRawDataArray()) output.putLong(value);
        try {
            this.db.put(this.writeOptions, sectionKey(section.key), compress(output.array()));
        } catch (RocksDBException error) {
            throw new RuntimeException(error);
        }
    }

    @Override
    public void deleteSection(long key) {
        try {
            this.db.delete(this.writeOptions, sectionKey(key));
        } catch (RocksDBException error) {
            throw new RuntimeException(error);
        }
    }

    @Override
    public void iterateStoredSectionPositions(LongConsumer consumer) {
        try (var iterator = this.db.newIterator(this.readOptions)) {
            iterator.seek(new byte[]{SECTION_KEY});
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length != 1 + Long.BYTES || key[0] != SECTION_KEY) break;
                consumer.accept(ByteBuffer.wrap(key, 1, Long.BYTES).order(ByteOrder.BIG_ENDIAN).getLong());
                iterator.next();
            }
        }
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        byte[] encoded = new byte[data.remaining()];
        data.duplicate().get(encoded);
        try {
            this.db.put(this.writeOptions, mappingKey(id), encoded);
        } catch (RocksDBException error) {
            throw new RuntimeException(error);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        var mappings = new Int2ObjectOpenHashMap<byte[]>();
        try (var iterator = this.db.newIterator(this.readOptions)) {
            iterator.seek(new byte[]{MAPPING_KEY});
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (key.length != 1 + Integer.BYTES || key[0] != MAPPING_KEY) break;
                mappings.put(ByteBuffer.wrap(key, 1, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt(), iterator.value());
                iterator.next();
            }
        }
        return mappings;
    }

    @Override
    public void flush() {
        try {
            this.db.flushWal(true);
        } catch (RocksDBException error) {
            throw new RuntimeException(error);
        }
    }

    @Override
    public void close() {
        this.flush();
        this.db.close();
        this.writeOptions.close();
        this.readOptions.close();
        this.options.close();
    }

    private int discardInvalid(WorldSection section, String reason) {
        Logger.error("Removing invalid dedicated-server section " + section.key + ": " + reason);
        this.deleteSection(section.key);
        return -1;
    }

    private static byte[] sectionKey(long key) {
        return ByteBuffer.allocate(1 + Long.BYTES).order(ByteOrder.BIG_ENDIAN)
                .put(SECTION_KEY).putLong(key).array();
    }

    private static byte[] mappingKey(int id) {
        return ByteBuffer.allocate(1 + Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
                .put(MAPPING_KEY).putInt(id).array();
    }

    private static byte[] compress(byte[] input) {
        var output = new ByteArrayOutputStream(input.length / 4);
        Deflater deflater = new Deflater(3);
        try (var compressor = new DeflaterOutputStream(output, deflater)) {
            compressor.write(input);
        } catch (IOException error) {
            throw new RuntimeException(error);
        } finally {
            deflater.end();
        }
        return output.toByteArray();
    }

    private static byte[] decompress(byte[] input) throws IOException {
        try (var decompressor = new InflaterInputStream(new ByteArrayInputStream(input))) {
            byte[] output = decompressor.readNBytes(SECTION_SIZE + 1);
            if (output.length != SECTION_SIZE) {
                throw new IOException("Unexpected section size " + output.length);
            }
            return output;
        }
    }
}
