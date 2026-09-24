package ru.warndev.blockjournal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

public final class SpoolStore {
    private static final int MAGIC = 0x57424A31;
    private static final long FILE_LIMIT = 64L * 1024 * 1024;
    private final Path directory;
    private final long capacity;
    private long used;

    public SpoolStore(Path directory, long capacity) throws IOException {
        this.directory = directory;
        this.capacity = capacity;
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            for (Path path : files.toList()) {
                if (Files.isRegularFile(path)) {
                    used += Files.size(path);
                }
            }
        }
    }

    public Path append(List<BlockEntry> entries) throws IOException {
        if (entries.isEmpty() || entries.size() > 10000) {
            throw new IllegalArgumentException("Batch size must be 1..10000");
        }
        byte[] payload = encode(entries);
        CRC32 crc = new CRC32();
        crc.update(payload);
        long size = payload.length + 16L;
        if (size > FILE_LIMIT || size > capacity - used) {
            throw new IOException("Spool storage limit reached");
        }
        String name = String.format("%013d-%s", System.currentTimeMillis(), UUID.randomUUID());
        Path temporary = directory.resolve(name + ".tmp");
        Path destination = directory.resolve(name + ".batch");
        ByteBuffer data = ByteBuffer.allocate((int) size);
        data.putInt(MAGIC).putInt(payload.length).putLong(crc.getValue()).put(payload).flip();
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (data.hasRemaining()) {
                    channel.write(data);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, destination);
            }
            moved = true;
            used += size;
            return destination;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private byte[] encode(List<BlockEntry> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(entries.size());
            for (BlockEntry entry : entries) {
                writeUuid(out, entry.eventId());
                out.writeLong(entry.timestamp());
                out.writeBoolean(entry.actorId() != null);
                if (entry.actorId() != null) {
                    writeUuid(out, entry.actorId());
                }
                out.writeUTF(entry.actorName());
                writeUuid(out, entry.worldId());
                out.writeUTF(entry.worldName());
                out.writeInt(entry.x());
                out.writeInt(entry.y());
                out.writeInt(entry.z());
                out.writeUTF(entry.action().name());
                out.writeUTF(entry.before());
                out.writeUTF(entry.after());
            }
        }
        return buffer.toByteArray();
    }

    public List<BlockEntry> read(Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().getParent().equals(directory.toAbsolutePath().normalize())) {
            throw new IOException("Path outside spool directory");
        }
        long size = Files.size(path);
        if (size < 20 || size > FILE_LIMIT) {
            throw new IOException("Invalid spool file size");
        }
        byte[] bytes = Files.readAllBytes(path);
        try (DataInputStream file = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (file.readInt() != MAGIC) {
                throw new IOException("Unknown spool format");
            }
            int length = file.readInt();
            long expected = file.readLong();
            if (length != bytes.length - 16) {
                throw new IOException("Truncated spool file");
            }
            byte[] payload = file.readNBytes(length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expected) {
                throw new IOException("Spool checksum mismatch");
            }
            return decode(payload);
        }
    }

    private List<BlockEntry> decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readInt();
            if (count < 1 || count > 10000) {
                throw new IOException("Invalid spool batch count");
            }
            List<BlockEntry> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                UUID key = readUuid(in);
                long time = in.readLong();
                UUID actor = in.readBoolean() ? readUuid(in) : null;
                String name = in.readUTF();
                UUID world = readUuid(in);
                String worldName = in.readUTF();
                int x = in.readInt();
                int y = in.readInt();
                int z = in.readInt();
                Action action = Action.valueOf(in.readUTF());
                String before = in.readUTF();
                String after = in.readUTF();
                result.add(new BlockEntry(key, time, actor, name, world, worldName, x, y, z, action, before, after));
            }
            if (in.available() != 0) {
                throw new IOException("Trailing bytes in spool file");
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid spool record", error);
        }
    }

    public List<Path> pending(int limit) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".batch"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(limit)
                    .toList();
        }
    }

    public void remove(Path path) throws IOException {
        long size = Files.size(path);
        Files.delete(path);
        used = Math.max(0, used - size);
    }

    public void quarantine(Path path) throws IOException {
        Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt"));
    }

    public long bytesUsed() {
        return used;
    }

    private void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
