package io.github.javaside.springai.codetui.agent.thinking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThinkingConfigTest {

    @Test
    void defaultAndDisabledCannotCarryStrength() {
        assertThrows(IllegalArgumentException.class,
                () -> new ThinkingConfig(ThinkingMode.DEFAULT, "high", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ThinkingConfig(ThinkingMode.DISABLED, null, 1024));
    }

    @Test
    void effortCapabilitiesRejectBudgetAndUnknownEffort() {
        ThinkingCapabilities caps = ThinkingCapabilities.effort(true, List.of("low", "high"));
        assertDoesNotThrow(() -> caps.validate(ThinkingConfig.enabledEffort("high")));
        assertThrows(IllegalArgumentException.class,
                () -> caps.validate(ThinkingConfig.enabledEffort("medium")));
        assertThrows(IllegalArgumentException.class,
                () -> caps.validate(ThinkingConfig.enabledBudget(1024)));
    }

    @Test
    void budgetRequiresPositiveInteger() {
        ThinkingCapabilities caps = ThinkingCapabilities.tokenBudget(true, 1, null);
        assertThrows(IllegalArgumentException.class,
                () -> caps.validate(ThinkingConfig.enabledBudget(0)));
    }

    @Test
    void summaryUsesNativeStrength() {
        ThinkingCapabilities effort = ThinkingCapabilities.effort(true, List.of("low", "high"));
        assertEquals("high", effort.summary(ThinkingConfig.enabledEffort("high")));
        assertEquals("默认", effort.summary(ThinkingConfig.defaults()));
        assertEquals("关闭", effort.summary(ThinkingConfig.disabled()));
    }
}
