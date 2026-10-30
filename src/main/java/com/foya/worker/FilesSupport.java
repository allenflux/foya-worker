package com.foya.worker;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

public final class FilesSupport {
    private FilesSupport() {}
    public static void privateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) {}
    }
    public static void writeJson(Path target, Object value) throws IOException {
        privateDirectory(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".write-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) {}
            Files.write(temp, Json.MAPPER.writeValueAsBytes(value));
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
