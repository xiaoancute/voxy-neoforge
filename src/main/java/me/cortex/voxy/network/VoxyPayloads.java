package me.cortex.voxy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/** Wire records shared by the physical client and dedicated server. */
public final class VoxyPayloads {
    public static final int PROTOCOL_VERSION = 2;
    public static final int MAX_REQUEST_SECTIONS = 8;
    public static final int MAX_INVALIDATION_SECTIONS = 64;
    public static final int MAX_RESPONSE_BYTES = 900_000;
    public static final int MAX_MAPPING_BYTES = 64_000;
    public static final ResourceLocation HELLO_ID = ResourceLocation.fromNamespaceAndPath("voxy", "lod_hello");
    public static final ResourceLocation REQUEST_ID = ResourceLocation.fromNamespaceAndPath("voxy", "lod_request");
    public static final ResourceLocation HELLO_RESPONSE_ID = ResourceLocation.fromNamespaceAndPath("voxy", "lod_hello_response");
    public static final ResourceLocation RESPONSE_ID = ResourceLocation.fromNamespaceAndPath("voxy", "lod_response");

    private VoxyPayloads() {}

    public record Hello(int protocol, boolean remoteLod) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(HELLO_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeVarInt(value.protocol); buf.writeBoolean(value.remoteLod); },
                buf -> new Hello(buf.readVarInt(), buf.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record HelloResponse(int protocol, boolean enabled, int maxSections, int maxRequestsPerSecond) implements CustomPacketPayload {
        public static final Type<HelloResponse> TYPE = new Type<>(HELLO_RESPONSE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloResponse> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarInt(value.protocol);
                    buf.writeBoolean(value.enabled);
                    buf.writeVarInt(value.maxSections);
                    buf.writeVarInt(value.maxRequestsPerSecond);
                },
                buf -> new HelloResponse(buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Request(String dimension, long[] keys) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> {
                    writeString(buf, value.dimension, 128);
                    if (value.keys.length > MAX_REQUEST_SECTIONS) throw new IllegalArgumentException("Too many sections");
                    buf.writeVarInt(value.keys.length);
                    for (long key : value.keys) buf.writeLong(key);
                },
                buf -> {
                    String dimension = readString(buf, 128);
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_REQUEST_SECTIONS) throw new IllegalArgumentException("Too many sections");
                    long[] keys = new long[count];
                    for (int i = 0; i < count; i++) keys[i] = buf.readLong();
                    return new Request(dimension, keys);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Invalidate(String dimension, long[] keys) implements CustomPacketPayload {
        public static final Type<Invalidate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "lod_invalidate"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Invalidate> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> {
                    writeString(buf, value.dimension, 128);
                    if (value.keys.length > MAX_INVALIDATION_SECTIONS) throw new IllegalArgumentException("Too many invalidations");
                    buf.writeVarInt(value.keys.length);
                    for (long key : value.keys) buf.writeLong(key);
                },
                buf -> {
                    String dimension = readString(buf, 128);
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_INVALIDATION_SECTIONS) throw new IllegalArgumentException("Too many invalidations");
                    long[] keys = new long[count];
                    for (int i = 0; i < count; i++) keys[i] = buf.readLong();
                    return new Invalidate(dimension, keys);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Mapping(int type, int id, byte[] data) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Mapping> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.type);
                    buf.writeVarInt(value.id);
                    writeBytes(buf, value.data, MAX_MAPPING_BYTES);
                },
                buf -> new Mapping(buf.readUnsignedByte(), buf.readVarInt(), readBytes(buf, MAX_MAPPING_BYTES)));
    }

    public record Response(String dimension, long key, boolean found, byte nonEmptyChildren, long[] data, List<Mapping> mappings)
            implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(RESPONSE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> {
                    writeString(buf, value.dimension, 128);
                    buf.writeLong(value.key);
                    buf.writeBoolean(value.found);
                    if (!value.found) return;
                    if (value.data.length != 32 * 32 * 32) throw new IllegalArgumentException("Invalid section size");
                    buf.writeByte(value.nonEmptyChildren);
                    buf.writeVarInt(value.data.length);
                    for (long item : value.data) buf.writeLong(item);
                    if (value.mappings.size() > 65_536) throw new IllegalArgumentException("Too many mappings");
                    buf.writeVarInt(value.mappings.size());
                    for (Mapping mapping : value.mappings) Mapping.STREAM_CODEC.encode(buf, mapping);
                },
                buf -> {
                    String dimension = readString(buf, 128);
                    long key = buf.readLong();
                    if (!buf.readBoolean()) return new Response(dimension, key, false, (byte) 0, new long[0], List.of());
                    byte children = buf.readByte();
                    int length = buf.readVarInt();
                    if (length != 32 * 32 * 32) throw new IllegalArgumentException("Invalid section size");
                    long[] data = new long[length];
                    for (int i = 0; i < length; i++) data[i] = buf.readLong();
                    int mappingCount = buf.readVarInt();
                    if (mappingCount < 0 || mappingCount > 65_536) throw new IllegalArgumentException("Too many mappings");
                    List<Mapping> mappings = new ArrayList<>(mappingCount);
                    for (int i = 0; i < mappingCount; i++) mappings.add(Mapping.STREAM_CODEC.decode(buf));
                    return new Response(dimension, key, true, children, data, mappings);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static void writeString(RegistryFriendlyByteBuf buf, String value, int maxBytes) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("String too long");
        buf.writeVarInt(bytes.length).writeBytes(bytes);
    }

    private static String readString(RegistryFriendlyByteBuf buf, int maxBytes) {
        int length = buf.readVarInt();
        if (length < 0 || length > maxBytes || length > buf.readableBytes()) throw new IllegalArgumentException("String too long");
        return buf.readCharSequence(length, java.nio.charset.StandardCharsets.UTF_8).toString();
    }

    private static void writeBytes(RegistryFriendlyByteBuf buf, byte[] value, int maxBytes) {
        if (value.length > maxBytes) throw new IllegalArgumentException("Mapping too large");
        buf.writeVarInt(value.length).writeBytes(value);
    }

    private static byte[] readBytes(RegistryFriendlyByteBuf buf, int maxBytes) {
        int length = buf.readVarInt();
        if (length < 0 || length > maxBytes || length > buf.readableBytes()) throw new IllegalArgumentException("Mapping too large");
        byte[] value = new byte[length];
        buf.readBytes(value);
        return value;
    }
}
