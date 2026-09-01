package com.codex.mnote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CaptureSyncPreferencesTest {
    @Test
    public void everySupportedAiPolicyIsPreserved() {
        assertEquals(
                CaptureSyncPreferences.AI_DENY,
                CaptureSyncPreferences.cleanAiAccess(CaptureSyncPreferences.AI_DENY)
        );
        assertEquals(
                CaptureSyncPreferences.AI_LOCAL_ONLY,
                CaptureSyncPreferences.cleanAiAccess(CaptureSyncPreferences.AI_LOCAL_ONLY)
        );
        assertEquals(
                CaptureSyncPreferences.AI_REMOTE_NO_MEMORY,
                CaptureSyncPreferences.cleanAiAccess(
                        CaptureSyncPreferences.AI_REMOTE_NO_MEMORY
                )
        );
        assertEquals(
                CaptureSyncPreferences.AI_REMOTE_MEMORY,
                CaptureSyncPreferences.cleanAiAccess(CaptureSyncPreferences.AI_REMOTE_MEMORY)
        );
    }

    @Test
    public void absentOrUnknownAiPolicyFailsClosedToLocalOnly() {
        assertEquals(
                CaptureSyncPreferences.AI_LOCAL_ONLY,
                CaptureSyncPreferences.cleanAiAccess(null)
        );
        assertEquals(
                CaptureSyncPreferences.AI_LOCAL_ONLY,
                CaptureSyncPreferences.cleanAiAccess("")
        );
        assertEquals(
                CaptureSyncPreferences.AI_LOCAL_ONLY,
                CaptureSyncPreferences.cleanAiAccess("public")
        );
    }

    @Test
    public void baseUrlAcceptsHttpsAndLiteralPrivateHttp() {
        assertEquals(
                "https://capture.example.com/api",
                CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "  https://capture.example.com/api///  "
                )
        );
        assertEquals(
                "http://127.0.0.1:8787",
                CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "http://127.0.0.1:8787/"
                )
        );
        assertEquals(
                "http://192.168.10.4:8787",
                CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "http://192.168.10.4:8787"
                )
        );
        assertEquals(
                "http://[::1]:8787",
                CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "http://[::1]:8787/"
                )
        );
        assertTrue(CaptureSyncPreferences.isPrivateOrLoopbackLiteral("::1"));
        assertTrue(CaptureSyncPreferences.isPrivateOrLoopbackLiteral("fd00::1234"));
        assertFalse(CaptureSyncPreferences.isPrivateOrLoopbackLiteral("2001:4860:4860::8888"));
    }

    @Test
    public void baseUrlRejectsUnsafeOrAmbiguousHttpTargets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "http://capture.example.com:8787"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "http://8.8.8.8:8787"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "https://user:password@capture.example.com"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "https://capture.example.com?token=secret"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> CaptureSyncPreferences.validateAndNormalizeBaseUrl(
                        "ftp://capture.example.com"
                )
        );
    }

    @Test
    public void tokenValidationRejectsWeakShapeWithoutEchoingToken() {
        CaptureSyncPreferences.validateToken("write-token-123456789");
        IllegalArgumentException shortError = assertThrows(
                IllegalArgumentException.class,
                () -> CaptureSyncPreferences.validateToken("short")
        );
        assertFalse(shortError.getMessage().contains("short"));
        IllegalArgumentException whitespaceError = assertThrows(
                IllegalArgumentException.class,
                () -> CaptureSyncPreferences.validateToken("secret token")
        );
        assertFalse(whitespaceError.getMessage().contains("secret token"));
    }
}
