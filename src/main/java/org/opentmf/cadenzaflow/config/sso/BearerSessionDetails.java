package org.opentmf.cadenzaflow.config.sso;

import jakarta.annotation.Nullable;
import java.io.Serializable;
import java.time.Instant;

/**
 * The details of a webapp session opened by {@code POST /app/login-with-token}
 * (CF-SHARED-CALLBACK-3), kept on its authentication so they survive in the session and
 * reach the logout.
 *
 * @param idToken the ID token the page sent in {@code X-ID-Token}, validated at login; used
 *     ONLY as the logout's {@code id_token_hint}. {@code null} when none was sent.
 * @param openedAt when the session was opened; it ends at
 *     {@code openedAt + cadenzaflow.sso.bearer-session-max}
 * @author Cezmi Aslan
 */
public record BearerSessionDetails(@Nullable String idToken, Instant openedAt)
    implements Serializable {
}
