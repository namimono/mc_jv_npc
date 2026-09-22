package dev.jev.npc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public final class ConfigStore {
    public static final String SETTINGS_FILE = "jev-npc.json";
    public static final String SECRET_FILE = "jev-npc.secret.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path secretPath(Path settingsPath) {
        Path parent = settingsPath.getParent();
        return parent == null ? Path.of(SECRET_FILE) : parent.resolve(SECRET_FILE);
    }

    public static NpcConfig load(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        if (!Files.exists(path)) writeSettings(path, new NpcConfig());
        JsonObject document = readObject(path, "Invalid JSON configuration; check commas, quotes and field types");
        boolean hadLegacyKey = document.has("apiKey");
        String legacyKey = hadLegacyKey ? stringField(document, "Invalid JSON configuration; apiKey must be a string") : "";
        NpcConfig config = GSON.fromJson(document, NpcConfig.class);
        if (config == null) throw new IOException("Configuration must be a JSON object");
        config.apiKey = loadSecret(secretPath(path), legacyKey);
        config.validate();
        if (hadLegacyKey) writeSettings(path, config);
        return config;
    }

    private static String loadSecret(Path path, String legacyKey) throws IOException {
        String migrated = legacyKey == null ? "" : legacyKey.trim();
        if (!Files.exists(path)) {
            writeSecret(path, migrated);
            return migrated;
        }
        restrictToOwner(path);
        JsonObject document = readObject(path, "Invalid API key file; check commas, quotes and field types");
        String stored = stringField(document, "Invalid API key file; apiKey must be a string").trim();
        if (stored.isBlank() && !migrated.isBlank()) {
            writeSecret(path, migrated);
            return migrated;
        }
        return stored;
    }

    private static JsonObject readObject(Path path, String invalidMessage) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) throw new IOException(invalidMessage);
            return parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException exception) {
            throw new IOException(invalidMessage);
        }
    }

    private static String stringField(JsonObject document, String invalidMessage) throws IOException {
        if (!document.has("apiKey") || document.get("apiKey").isJsonNull()) return "";
        var value = document.get("apiKey");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException(invalidMessage);
        return value.getAsString();
    }

    private static void writeSettings(Path path, NpcConfig config) throws IOException {
        Files.writeString(path, GSON.toJson(config) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeSecret(Path path, String apiKey) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        var file = new SecretFile();
        file.apiKey = apiKey == null ? "" : apiKey.trim();
        Path directory = path.getParent() == null ? Path.of(".") : path.getParent();
        Path temporary = Files.createTempFile(directory, ".jev-npc-secret-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(file) + "\n", StandardCharsets.UTF_8);
            restrictToOwner(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        restrictToOwner(path);
    }

    private static void restrictToOwner(Path path) {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems cannot express mode 600. Loading the key still succeeds.
        }
    }

    private static final class SecretFile {
        String apiKey = "";
    }

    private ConfigStore() {}
}
