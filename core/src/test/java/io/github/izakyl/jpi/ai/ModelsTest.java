package io.github.izakyl.jpi.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelsTest {

    @Test
    void catalogLoadsFromResources() {
        assertFalse(Providers.all().isEmpty());
        assertFalse(Models.of("openai").isEmpty());
        var model = Models.of("deepseek").get(0);
        assertTrue(Models.get("deepseek", model.id()).isPresent());
        assertTrue(model.contextWindow() > 0);
        assertTrue(model.maxTokens() > 0);
        assertTrue(model.input().contains("text"));
        assertTrue(Models.of("missing").isEmpty());
    }

    @Test
    void customModelCanDisableImages() {
        var model = Model.builder("test", "http://localhost").input(List.of("text")).build();
        assertFalse(model.input().contains("image"));
    }
}
