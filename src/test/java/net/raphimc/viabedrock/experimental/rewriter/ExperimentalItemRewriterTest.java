package net.raphimc.viabedrock.experimental.rewriter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentalItemRewriterTest {

    @Test
    void rejectsNullIdentifierWhenEnabled() {
        assertFalse(assertDoesNotThrow(() ->
                ExperimentalItemRewriter.shouldApplySwordBlockingAnimation(true, null)));
    }

    @Test
    void acceptsVanillaSwordWhenEnabled() {
        assertTrue(ExperimentalItemRewriter.shouldApplySwordBlockingAnimation(true, "minecraft:diamond_sword"));
    }

    @Test
    void rejectsCustomAndNonSwordIdentifiersWhenEnabled() {
        assertFalse(ExperimentalItemRewriter.shouldApplySwordBlockingAnimation(true, "easecation:custom_sword"));
        assertFalse(ExperimentalItemRewriter.shouldApplySwordBlockingAnimation(true, "minecraft:diamond_pickaxe"));
    }

    @Test
    void rejectsVanillaSwordWhenDisabled() {
        assertFalse(ExperimentalItemRewriter.shouldApplySwordBlockingAnimation(false, "minecraft:diamond_sword"));
    }
}
