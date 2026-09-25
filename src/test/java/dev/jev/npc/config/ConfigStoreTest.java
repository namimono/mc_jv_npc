package dev.jev.npc.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir Path directory;

    @Test void createsSettingsWithoutKeyAndAnEmptySecret() throws Exception {
        NpcConfig config = ConfigStore.load(directory.resolve("jev-npc.json"));
        String settings = Files.readString(directory.resolve("jev-npc.json"));
        String secret = Files.readString(directory.resolve("jev-npc.secret.json"));
        assertFalse(settings.contains("apiKey"));
        assertTrue(settings.contains("\"model\": \"jev-1.13.0\""));
        assertEquals("{\n  \"apiKey\": \"\",\n  \"deepseekApiKey\": \"\"\n}\n", secret);
        assertTrue(config.apiKey.isBlank());
        assertTrue(config.llmApiKey.isBlank());
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(directory.resolve("jev-npc.secret.json")));
    }

    @Test void readsKeyOnlyFromSecretFile() throws Exception {
        Files.writeString(directory.resolve("jev-npc.json"), """
            {"enabled":true,"model":"jev-1.13.0","requestTimeoutMs":2500,"decisionCooldownTicks":40,
            "eventDebounceTicks":8,"idleDecisionTicks":600,"maxResultAgeMs":4000,"maxRequestsPerMinute":60,
            "minimumConfidence":0.35,"allowBlockChanges":true,"debugToOwner":false}
            """, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("jev-npc.secret.json"), "{\"apiKey\":\" local-test-key \"}", StandardCharsets.UTF_8);
        NpcConfig config = ConfigStore.load(directory.resolve("jev-npc.json"));
        assertEquals("local-test-key", config.apiKey);
        assertFalse(config.debugToOwner);
        assertFalse(Files.readString(directory.resolve("jev-npc.json")).contains("local-test-key"));
    }

    @Test void readsDeepSeekKeyBesideJevKeyAndNeverWritesItToSettings() throws Exception {
        Files.writeString(directory.resolve("jev-npc.json"), "{\"model\":\"jev-1.13.0\",\"llmModel\":\"deepseek-v4-pro\"}", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("jev-npc.secret.json"), "{\"apiKey\":\"jev\",\"deepseekApiKey\":\" ds-test \"}", StandardCharsets.UTF_8);
        NpcConfig config = ConfigStore.load(directory.resolve("jev-npc.json"));
        assertEquals("ds-test", config.llmApiKey);
        assertEquals("deepseek-v4-pro", config.llmModel);
        Files.writeString(directory.resolve("jev-npc.json"), "{\"apiKey\":\"legacy\"}", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("jev-npc.secret.json"), "{\"apiKey\":\"\",\"deepseekApiKey\":\"ds-test\"}", StandardCharsets.UTF_8);
        NpcConfig migrated = ConfigStore.load(directory.resolve("jev-npc.json"));
        assertEquals("legacy", migrated.apiKey);
        assertFalse(Files.readString(directory.resolve("jev-npc.json")).contains("ds-test"));
        String secret = Files.readString(directory.resolve("jev-npc.secret.json"));
        assertTrue(secret.contains("legacy") && secret.contains("ds-test"), "migrating the Jev key keeps the DeepSeek key");
    }

    @Test void movesLegacyKeyOutOfShareableSettings() throws Exception {
        Files.writeString(directory.resolve("jev-npc.json"), """
            {"enabled":false,"apiKey":"legacy-key","model":"jev-1.13.0","minimumConfidence":0.5}
            """, StandardCharsets.UTF_8);
        NpcConfig config = ConfigStore.load(directory.resolve("jev-npc.json"));
        String settings = Files.readString(directory.resolve("jev-npc.json"));
        assertEquals("legacy-key", config.apiKey);
        assertFalse(config.enabled);
        assertFalse(settings.contains("apiKey"));
        assertFalse(settings.contains("legacy-key"));
        assertTrue(Files.readString(directory.resolve("jev-npc.secret.json")).contains("legacy-key"));
    }

    @Test void keepsExistingSecretWhenLegacySettingsAlsoContainAKey() throws Exception {
        Files.writeString(directory.resolve("jev-npc.json"), "{\"apiKey\":\"old-key\",\"model\":\"jev-1.13.0\"}", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("jev-npc.secret.json"), "{\"apiKey\":\"current-key\"}", StandardCharsets.UTF_8);
        NpcConfig config = ConfigStore.load(directory.resolve("jev-npc.json"));
        assertEquals("current-key", config.apiKey);
        assertFalse(Files.readString(directory.resolve("jev-npc.json")).contains("apiKey"));
        assertTrue(Files.readString(directory.resolve("jev-npc.secret.json")).contains("current-key"));
        assertFalse(Files.readString(directory.resolve("jev-npc.secret.json")).contains("old-key"));
    }

    @Test void rejectsMalformedSecretWithoutEchoingIt() throws Exception {
        Files.writeString(directory.resolve("jev-npc.json"), "{\"model\":\"jev-1.13.0\"}", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("jev-npc.secret.json"), "{\"apiKey\":\"do-not-echo\"", StandardCharsets.UTF_8);
        IOException error = assertThrows(IOException.class, () -> ConfigStore.load(directory.resolve("jev-npc.json")));
        assertFalse(error.getMessage().contains("do-not-echo"));
        assertNull(error.getCause());
    }
}
