package com.grinderwolf.swm.plugin.loaders;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.DoubleTag;
import com.flowpowered.nbt.IntArrayTag;
import com.flowpowered.nbt.IntTag;
import com.flowpowered.nbt.ListTag;
import com.flowpowered.nbt.TagType;
import com.flowpowered.nbt.stream.NBTInputStream;
import com.github.luben.zstd.Zstd;
import com.grinderwolf.swm.api.exceptions.CorruptedWorldException;
import com.grinderwolf.swm.api.exceptions.NewerFormatException;
import com.grinderwolf.swm.api.loaders.SlimeLoader;
import com.grinderwolf.swm.api.utils.NibbleArray;
import com.grinderwolf.swm.api.utils.SlimeFormat;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeChunkSection;
import com.grinderwolf.swm.api.world.SlimeWorld;
import com.grinderwolf.swm.nms.CraftSlimeChunk;
import com.grinderwolf.swm.nms.CraftSlimeChunkSection;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import com.grinderwolf.swm.plugin.config.ConfigManager;
import com.grinderwolf.swm.plugin.config.DatasourcesConfig;
import com.grinderwolf.swm.plugin.log.Logging;
import com.mongodb.MongoException;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoaderUtils {

    private static Map<String, SlimeLoader> loaderMap = new HashMap<>();

    public static void registerLoaders() {
        DatasourcesConfig config = ConfigManager.getDatasourcesConfig();

        // File loader
        DatasourcesConfig.FileConfig fileConfig = config.getFileConfig();
        registerLoader("file", new FileLoader(new File(fileConfig.getPath())));

        // Mysql loader
        DatasourcesConfig.MysqlConfig mysqlConfig = config.getMysqlConfig();

        if (mysqlConfig.isEnabled()) {
            try {
                registerLoader("mysql", new MysqlLoader(mysqlConfig));
            } catch (SQLException ex) {
                Logging.error("Failed to establish connection to the MySQL server:");
                ex.printStackTrace();
            }
        }

        // MongoDB loader
        DatasourcesConfig.MongoDBConfig mongoConfig = config.getMongoDbConfig();

        if (mongoConfig.isEnabled()) {
            try {
                registerLoader("mongodb", new MongoLoader(mongoConfig));
            } catch (MongoException ex) {
                Logging.error("Failed to establish connection to the MongoDB server:");
                ex.printStackTrace();
            }
        }
    }

    public static SlimeLoader getLoader(String dataSource) {
        return loaderMap.get(dataSource);
    }

    public static void registerLoader(String dataSource, SlimeLoader loader) {
        if (loaderMap.containsKey(dataSource)) {
            throw new IllegalArgumentException("Data source " + dataSource + " already has a declared loader!");
        }

        loaderMap.put(dataSource, loader);
    }

    public static CraftSlimeWorld deserializeWorld(SlimeLoader loader, String worldName, byte[] serializedWorld, SlimeWorld.SlimeProperties properties)
            throws IOException, CorruptedWorldException, NewerFormatException {
        DataInputStream dataStream = new DataInputStream(new ByteArrayInputStream(serializedWorld));

        try {
            byte[] fileHeader = new byte[SlimeFormat.SLIME_HEADER.length];
            dataStream.read(fileHeader);

            if (!Arrays.equals(SlimeFormat.SLIME_HEADER, fileHeader)) {
                throw new CorruptedWorldException(worldName);
            }

            // File version
            byte version = dataStream.readByte();

            if (version > SlimeFormat.SLIME_VERSION) {
                throw new NewerFormatException(version);
            }

            // World version
            byte worldVersion;

            if (version >= 6) {
                worldVersion = dataStream.readByte();
            } else if (version >= 4) { // In v4 there's just a boolean indicating whether the world is pre-1.13 or post-1.13
                worldVersion = (byte) (dataStream.readBoolean() ? 0x04 : 0x01);
            } else {
                worldVersion = 0; // We'll try to automatically detect it later
            }

            // Chunk
            short minX = dataStream.readShort();
            short minZ = dataStream.readShort();
            int width = dataStream.readShort();
            int depth = dataStream.readShort();

            if (width <= 0 || depth <= 0) {
                throw new CorruptedWorldException(worldName);
            }

            int bitmaskSize = (int) Math.ceil((width * depth) / 8.0D);
            byte[] chunkBitmask = new byte[bitmaskSize];
            dataStream.read(chunkBitmask);
            BitSet chunkBitset = BitSet.valueOf(chunkBitmask);

            int compressedChunkDataLength = dataStream.readInt();
            int chunkDataLength = dataStream.readInt();
            byte[] compressedChunkData = new byte[compressedChunkDataLength];
            byte[] chunkData = new byte[chunkDataLength];

            dataStream.read(compressedChunkData);

            // Tile Entities
            int compressedTileEntitiesLength = dataStream.readInt();
            int tileEntitiesLength = dataStream.readInt();
            byte[] compressedTileEntities = new byte[compressedTileEntitiesLength];
            byte[] tileEntities = new byte[tileEntitiesLength];

            dataStream.read(compressedTileEntities);

            // Entities
            byte[] compressedEntities = new byte[0];
            byte[] entities = new byte[0];

            if (version >= 3) {
                boolean hasEntities = dataStream.readBoolean();

                if (hasEntities) {
                    int compressedEntitiesLength = dataStream.readInt();
                    int entitiesLength = dataStream.readInt();
                    compressedEntities = new byte[compressedEntitiesLength];
                    entities = new byte[entitiesLength];

                    dataStream.read(compressedEntities);
                }
            }

            // Extra NBT tag
            byte[] compressedExtraTag = new byte[0];
            byte[] extraTag = new byte[0];

            if (version >= 2) {
                int compressedExtraTagLength = dataStream.readInt();
                int extraTagLength = dataStream.readInt();
                compressedExtraTag = new byte[compressedExtraTagLength];
                extraTag = new byte[extraTagLength];

                dataStream.read(compressedExtraTag);
            }

            if (dataStream.read() != -1) {
                throw new CorruptedWorldException(worldName);
            }

            // Data decompression
            Zstd.decompress(chunkData, compressedChunkData);
            Zstd.decompress(tileEntities, compressedTileEntities);
            Zstd.decompress(entities, compressedEntities);
            Zstd.decompress(extraTag, compressedExtraTag);

            // Chunk deserialization
            Map<Long, SlimeChunk> chunks = readChunks(worldVersion, version, worldName, minX, minZ, width, depth, chunkBitset, chunkData);

            // Entity deserialization
            CompoundTag entitiesCompound = readCompoundTag(entities);

            if (entitiesCompound != null) {
                ListTag<CompoundTag> tileEntitiesList = (ListTag<CompoundTag>) entitiesCompound.getValue().get("entities");

                for (CompoundTag entityCompound : tileEntitiesList.getValue()) {
                    CompoundMap map = entityCompound.getValue();
                    ListTag<DoubleTag> listTag = (ListTag<DoubleTag>) map.get("Pos");

                    int chunkX = floor(listTag.getValue().get(0).getValue()) >> 4;
                    int chunkZ = floor(listTag.getValue().get(2).getValue()) >> 4;
                    long chunkKey = ((long) chunkZ) * Integer.MAX_VALUE + ((long) chunkX);
                    SlimeChunk chunk = chunks.get(chunkKey);

                    if (chunk == null) {
                        throw new CorruptedWorldException(worldName);
                    }

                    chunk.getEntities().add(entityCompound);
                }
            }

            // Tile Entity deserialization
            CompoundTag tileEntitiesCompound = readCompoundTag(tileEntities);

            if (tileEntitiesCompound != null) {
                ListTag<CompoundTag> tileEntitiesList = (ListTag<CompoundTag>) tileEntitiesCompound.getValue().get("tiles");

                for (CompoundTag tileEntityCompound : tileEntitiesList.getValue()) {
                    int chunkX = ((IntTag) tileEntityCompound.getValue().get("x")).getValue() >> 4;
                    int chunkZ = ((IntTag) tileEntityCompound.getValue().get("z")).getValue() >> 4;
                    long chunkKey = ((long) chunkZ) * Integer.MAX_VALUE + ((long) chunkX);
                    SlimeChunk chunk = chunks.get(chunkKey);


                    if (chunk == null) {
                        throw new CorruptedWorldException(worldName);
                    }

                    chunk.getTileEntities().add(tileEntityCompound);
                }
            }

            // Extra Data
            CompoundTag extraCompound = readCompoundTag(extraTag);

            if (extraCompound == null) {
                extraCompound = new CompoundTag("", new CompoundMap());
            }

            // v1_13 world format detection for old versions
            if (worldVersion == 0) {
                mainLoop:
                for (SlimeChunk chunk : chunks.values()) {
                    for (SlimeChunkSection section : chunk.getSections()) {
                        if (section != null) {
                            worldVersion = (byte) (section.getBlocks() == null ? 0x04 : 0x01);

                            break mainLoop;
                        }
                    }
                }
            }

            return new CraftSlimeWorld(loader, worldName, chunks, extraCompound, worldVersion, properties);
        } catch (EOFException ex) {
            throw new CorruptedWorldException(worldName);
        }
    }

    private static int floor(double num) {
        final int floor = (int) num;
        return floor == num ? floor : floor - (int) (Double.doubleToRawLongBits(num) >>> 63);
    }

    private static Map<Long, SlimeChunk> readChunks(byte worldVersion, int version, String worldName, int minX, int minZ, int width, int depth, BitSet chunkBitset, byte[] chunkData) throws IOException {
        DataInputStream dataStream = new DataInputStream(new ByteArrayInputStream(chunkData));
        Map<Long, SlimeChunk> chunkMap = new HashMap<>();

        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                int bitsetIndex = z * width + x;

                if (chunkBitset.get(bitsetIndex)) {
                    // Height Maps
                    CompoundTag heightMaps;

                    if (worldVersion >= 0x04) {
                        int heightMapsLength = dataStream.readInt();
                        byte[] heightMapsArray = new byte[heightMapsLength];
                        dataStream.read(heightMapsArray);
                        heightMaps = readCompoundTag(heightMapsArray);
                    } else {
                        int[] heightMap = new int[256];

                        for (int i = 0; i < 256; i++) {
                            heightMap[i] = dataStream.readInt();
                        }

                        CompoundMap map = new CompoundMap();
                        map.put("heightMap", new IntArrayTag("heightMap", heightMap));

                        heightMaps = new CompoundTag("", map);
                    }

                    // Biome array
                    int[] biomes;

                    if (worldVersion >= 0x04) {
                        biomes = new int[256];

                        for (int i = 0; i < biomes.length; i++) {
                            biomes[i] = dataStream.readInt();
                        }
                    } else {
                        byte[] byteBiomes = new byte[256];
                        dataStream.read(byteBiomes);
                        biomes = toIntArray(byteBiomes);
                    }

                    // Chunk Sections
                    SlimeChunkSection[] sections = readChunkSections(dataStream, worldVersion, version);

                    chunkMap.put(((long) minZ + z) * Integer.MAX_VALUE + ((long) minX + x), new CraftSlimeChunk(worldName,minX + x, minZ + z,
                            sections, heightMaps, biomes, new ArrayList<>(), new ArrayList<>()));
                }
            }
        }

        return chunkMap;
    }

    private static int[] toIntArray(byte[] buf) {
        ByteBuffer buffer = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int[] ret = new int[buf.length / 4];

        buffer.asIntBuffer().get(ret);

        return ret;
    }

    private static SlimeChunkSection[] readChunkSections(DataInputStream dataStream, byte worldVersion, int version) throws IOException {
        SlimeChunkSection[] chunkSectionArray = new SlimeChunkSection[16];
        byte[] sectionBitmask = new byte[2];
        dataStream.read(sectionBitmask);
        BitSet sectionBitset = BitSet.valueOf(sectionBitmask);

        for (int i = 0; i < 16; i++) {
            if (sectionBitset.get(i)) {
                // Block Light Nibble Array
                NibbleArray blockLightArray;

                if (version < 5 || dataStream.readBoolean()) {
                    byte[] blockLightByteArray = new byte[2048];
                    dataStream.read(blockLightByteArray);
                    blockLightArray = new NibbleArray((blockLightByteArray));
                } else {
                    blockLightArray = null;
                }

                // Block data
                byte[] blockArray;
                NibbleArray dataArray;

                ListTag<CompoundTag> paletteTag;
                long[] blockStatesArray;

                // Post 1.13 block format
                if (worldVersion >= 0x04) {
                    // Palette
                    int paletteLength = dataStream.readInt();
                    List<CompoundTag> paletteList = new ArrayList<>(paletteLength);

                    for (int index = 0; index < paletteLength; index++) {
                        int tagLength = dataStream.readInt();
                        byte[] serializedTag = new byte[tagLength];
                        dataStream.read(serializedTag);

                        paletteList.add(readCompoundTag(serializedTag));
                    }

                    paletteTag = new ListTag<>("", TagType.TAG_COMPOUND, paletteList);

                    // Block states
                    int blockStatesArrayLength = dataStream.readInt();
                    blockStatesArray = new long[blockStatesArrayLength];

                    for (int index = 0; index < blockStatesArrayLength; index++) {
                        blockStatesArray[index] = dataStream.readLong();
                    }

                    blockArray = null;
                    dataArray = null;
                } else {
                    blockArray = new byte[4096];
                    dataStream.read(blockArray);

                    // Block Data Nibble Array
                    byte[] dataByteArray = new byte[2048];
                    dataStream.read(dataByteArray);
                    dataArray = new NibbleArray((dataByteArray));

                    paletteTag = null;
                    blockStatesArray = null;
                }

                // Sky Light Nibble Array
                NibbleArray skyLightArray;

                if (version < 5 || dataStream.readBoolean()) {
                    byte[] skyLightByteArray = new byte[2048];
                    dataStream.read(skyLightByteArray);
                    skyLightArray = new NibbleArray((skyLightByteArray));
                } else {
                    skyLightArray = null;
                }

                // HypixelBlocks 3
                if (version < 4) {
                    short hypixelBlocksLength = dataStream.readShort();
                    dataStream.skip(hypixelBlocksLength);
                }

                chunkSectionArray[i] = new CraftSlimeChunkSection(blockArray, dataArray, paletteTag, blockStatesArray, blockLightArray, skyLightArray);
            }
        }

        return chunkSectionArray;
    }

    private static CompoundTag readCompoundTag(byte[] serializedCompound) throws IOException {
        if (serializedCompound.length == 0) {
            return null;
        }

        NBTInputStream stream = new NBTInputStream(new ByteArrayInputStream(serializedCompound), NBTInputStream.NO_COMPRESSION, ByteOrder.BIG_ENDIAN);

        return (CompoundTag) stream.readTag();
    }
}
