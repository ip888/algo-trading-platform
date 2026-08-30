package com.trading.ai;

import com.trading.autonomous.ConfigSelfHealer;
import com.trading.autonomous.ErrorDetector;
import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies ClaudeSessionReviewer routes a parsed config-diff suggestion through
 * ConfigSelfHealer.heal() correctly — this replaced "suggestion stored, nobody reads it"
 * with real autonomous application via the same validated pipeline exception-triggered
 * heals already use.
 *
 * Uses a real (throwaway, file-backed) TradeDatabase rather than a mock — this codebase's
 * TradeDatabase can't be Mockito-mocked in this environment (native SQLite init conflicts
 * with Java 25's inline-mock bytecode rewriting), and applySuggestionIfActionable never
 * touches the database anyway, so a real disposable instance is simpler and correct.
 */
class ClaudeSessionReviewerTest {

    private static TradeDatabase testDatabase(Path tempDir) {
        return new TradeDatabase(tempDir.resolve("test.db").toString());
    }

    @Test
    void routesValidSuggestionThroughConfigSelfHealer(@TempDir Path tempDir) {
        var healer = mock(ConfigSelfHealer.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var reviewer = new ClaudeSessionReviewer(testDatabase(tempDir), "fake-key", healer);

        String suggestion = """
            {"changes":[{"key":"FLAT_POSITION_HOURS","from":"1.5","to":"2.0","reason":"positions resolving slower this week"}]}
            """;
        reviewer.applySuggestionIfActionable(suggestion);

        ArgumentCaptor<ErrorDetector.ErrorAnalysis> captor = ArgumentCaptor.forClass(ErrorDetector.ErrorAnalysis.class);
        verify(healer, times(1)).heal(captor.capture());
        var analysis = captor.getValue();
        assertTrue(analysis.shouldHeal());
        assertEquals("CLAUDE_SESSION_REVIEW", analysis.pattern().name());
        assertEquals("2.0", analysis.pattern().configAdjustments().get("FLAT_POSITION_HOURS"));
    }

    @Test
    void emptyChangesArrayDoesNotCallHeal(@TempDir Path tempDir) {
        var healer = mock(ConfigSelfHealer.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var reviewer = new ClaudeSessionReviewer(testDatabase(tempDir), "fake-key", healer);

        reviewer.applySuggestionIfActionable("{\"changes\":[]}");

        verify(healer, never()).heal(any());
    }

    @Test
    void malformedJsonDoesNotThrowOrCallHeal(@TempDir Path tempDir) {
        var healer = mock(ConfigSelfHealer.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var reviewer = new ClaudeSessionReviewer(testDatabase(tempDir), "fake-key", healer);

        assertDoesNotThrow(() -> reviewer.applySuggestionIfActionable("not json at all"));
        verify(healer, never()).heal(any());
    }

    @Test
    void noHealerWiredInSkipsSilently(@TempDir Path tempDir) {
        var reviewer = new ClaudeSessionReviewer(testDatabase(tempDir), "fake-key"); // legacy ctor, no healer

        assertDoesNotThrow(() -> reviewer.applySuggestionIfActionable(
            "{\"changes\":[{\"key\":\"X\",\"from\":\"1\",\"to\":\"2\",\"reason\":\"r\"}]}"));
    }

    @Test
    void multipleChangesAreAllCaptured(@TempDir Path tempDir) {
        var healer = mock(ConfigSelfHealer.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var reviewer = new ClaudeSessionReviewer(testDatabase(tempDir), "fake-key", healer);

        String suggestion = """
            {"changes":[
              {"key":"FLAT_POSITION_HOURS","from":"1.5","to":"2.0","reason":"a"},
              {"key":"SCALP_MAX_DAILY_TRADES","from":"4","to":"6","reason":"b"}
            ]}
            """;
        reviewer.applySuggestionIfActionable(suggestion);

        ArgumentCaptor<ErrorDetector.ErrorAnalysis> captor = ArgumentCaptor.forClass(ErrorDetector.ErrorAnalysis.class);
        verify(healer).heal(captor.capture());
        assertEquals(2, captor.getValue().pattern().configAdjustments().size());
    }
}
