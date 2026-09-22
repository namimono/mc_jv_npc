package dev.jev.npc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static NpcConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(new NpcConfig()) + "\n", StandardCharsets.UTF_8);
        }
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            NpcConfig config = GSON.fromJson(reader, NpcConfig.class);
            if (config == null) throw new IOException("Configuration must be a JSON object");
            config.validate();
            return config;
        } catch (JsonParseException | IllegalStateException exception) {
            // Do not expose parser text: it can contain the API key from a malformed document.
            throw new IOException("Invalid JSON configuration; check commas, quotes and field types");
        }
    }

    private ConfigStore() {}
}
