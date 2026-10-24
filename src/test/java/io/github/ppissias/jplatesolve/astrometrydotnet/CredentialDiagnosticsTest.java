package io.github.ppissias.jplatesolve.astrometrydotnet;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

/** Keeps diagnostic redaction separate from authentication payload serialization. */
public class CredentialDiagnosticsTest {
    /** Login diagnostics hide the token while the JSON response retains its authentication value. */
    @Test
    public void loginResponseRedactsItsSessionOnlyInDiagnostics() {
        LoginResponse response = new LoginResponse();
        response.setStatus("success");
        response.setSession("synthetic-session-token");
        assertFalse(response.toString().contains(response.getSession()));
        assertTrue(response.toString().contains("session=<redacted>"));
        assertTrue(response.toString().contains("status=success"));
        assertEquals(response.getSession(), JsonParser.parseString(new Gson().toJson(response))
                .getAsJsonObject().get("session").getAsString());
    }

    /** Upload diagnostics hide the token while authenticated requests still serialize it. */
    @Test
    public void uploadRequestRedactsItsSessionOnlyInDiagnostics() {
        SubmitFileRequest request = SubmitFileRequest.builder()
                .withSession("synthetic-upload-token").withPublicly_visible("n").build();
        assertFalse(request.toString().contains(request.getSession()));
        assertTrue(request.toString().contains("session=<redacted>"));
        assertTrue(request.toString().contains("publicly_visible=n"));
        assertEquals(request.getSession(), JsonParser.parseString(new Gson().toJson(request))
                .getAsJsonObject().get("session").getAsString());
    }
}
