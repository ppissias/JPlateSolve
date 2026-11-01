package io.github.ppissias.jplatesolve;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PlateSolveResultTest {

    @Test
    public void solveInformationIsDefensivelyCopiedAndUnmodifiable() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("key1", "val1");
        mutable.put("source", "astrometry.net");

        PlateSolveResult result = new PlateSolveResult(true, null, null, mutable);

        // Mutating original map does not affect result
        mutable.put("key1", "mutated");
        mutable.put("new_key", "new_val");
        assertEquals("val1", result.getSolveInformation().get("key1"));
        assertNull(result.getSolveInformation().get("new_key"));

        // Attempting to mutate result's map throws UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class, () -> result.getSolveInformation().put("fail", "val"));
    }

    @Test
    public void nullSolveInformationBecomesEmptyUnmodifiableMap() {
        PlateSolveResult result = new PlateSolveResult(true, null, null, null);
        assertNotNull(result.getSolveInformation());
        assertTrue(result.getSolveInformation().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.getSolveInformation().put("fail", "val"));
    }

    @Test
    public void blankFailureReasonAndWarningAreNormalizedToNull() {
        PlateSolveResult result = new PlateSolveResult(true, "", "   ", Map.of());
        assertTrue(result.isSuccess());
        assertNull(result.getFailureReason());
        assertNull(result.getWarning());
    }

    @Test
    public void explicitFailureReasonAndWarningArePreserved() {
        PlateSolveResult result = new PlateSolveResult(false, "Solve failed", "Low confidence", Map.of("source", "astap"));
        assertFalse(result.isSuccess());
        assertEquals("Solve failed", result.getFailureReason());
        assertEquals("Low confidence", result.getWarning());
        assertEquals("astap", result.getSolveInformation().get("source"));
    }
}
