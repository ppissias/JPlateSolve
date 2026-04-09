package io.github.ppissias.jplatesolve.astap;

import io.github.ppissias.jplatesolve.PlateSolveResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ASTAPSolveResultsReaderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void parsesSuccessfulIniResults() throws Exception {
        File imageFile = temporaryFolder.newFile("sample.fit");
        File iniFile = new File(temporaryFolder.getRoot(), "sample.ini");
        Files.writeString(iniFile.toPath(),
                "PLTSOLVD=T\nWARNING=Minor warning\nCRVAL1=210.9\n",
                StandardCharsets.UTF_8);

        ASTAPSolveResultsReader reader =
                new ASTAPSolveResultsReader(imageFile.getAbsolutePath(), Duration.ofSeconds(1));

        PlateSolveResult result = reader.getSolveResult();

        assertTrue(result.isSuccess());
        assertEquals("Minor warning", result.getWarning());
        assertEquals("astap", result.getSolveInformation().get("source"));
        assertEquals(expectedPath(imageFile, "_annotated.jpg"), result.getSolveInformation().get("annotated_image_link"));
        assertEquals(expectedPath(imageFile, ".wcs"), result.getSolveInformation().get("wcs_link"));
    }

    @Test
    public void parsesFailedIniResults() throws Exception {
        File imageFile = temporaryFolder.newFile("sample.fit");
        File iniFile = new File(temporaryFolder.getRoot(), "sample.ini");
        Files.writeString(iniFile.toPath(),
                "PLTSOLVD=F\nWARNING=Low stars\nERROR=No solution found\n",
                StandardCharsets.UTF_8);

        ASTAPSolveResultsReader reader =
                new ASTAPSolveResultsReader(imageFile.getAbsolutePath(), Duration.ofSeconds(1));

        PlateSolveResult result = reader.getSolveResult();

        assertFalse(result.isSuccess());
        assertEquals("No solution found", result.getFailureReason());
        assertEquals("Low stars", result.getWarning());
        assertNotNull(result.getSolveInformation());
    }

    @Test(expected = IOException.class)
    public void timesOutWhenIniFileNeverArrives() throws Exception {
        File imageFile = temporaryFolder.newFile("missing.fit");

        ASTAPSolveResultsReader reader =
                new ASTAPSolveResultsReader(imageFile.getAbsolutePath(), Duration.ofMillis(150));

        reader.getSolveResult();
    }

    private static String expectedPath(File imageFile, String suffix) {
        String absolutePath = imageFile.getAbsolutePath();
        int extensionIndex = absolutePath.lastIndexOf('.');
        return absolutePath.substring(0, extensionIndex) + suffix;
    }
}
