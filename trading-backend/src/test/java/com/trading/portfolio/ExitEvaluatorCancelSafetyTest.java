package com.trading.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExitEvaluatorCancelSafetyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void onlyTerminalZeroFillCancellationPermitsNewSell() throws Exception {
        assertTrue(ExitEvaluator.isTerminalCancelWithoutFill(JSON.readTree("""
            {"status":"canceled","filled_qty":"0"}
            """)));
        assertFalse(ExitEvaluator.isTerminalCancelWithoutFill(JSON.readTree("""
            {"status":"pending_cancel","filled_qty":"0"}
            """)));
        assertFalse(ExitEvaluator.isTerminalCancelWithoutFill(JSON.readTree("""
            {"status":"filled","filled_qty":"2"}
            """)));
        assertFalse(ExitEvaluator.isTerminalCancelWithoutFill(JSON.readTree("""
            {"status":"canceled","filled_qty":"0.5"}
            """)));
        assertFalse(ExitEvaluator.isTerminalCancelWithoutFill(JSON.readTree("""
            {"status":"canceled"}
            """)));
        assertFalse(ExitEvaluator.isTerminalCancelWithoutFill(null));
    }
}
