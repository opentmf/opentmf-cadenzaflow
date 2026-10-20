package org.opentmf.cadenzaflow.config;

import org.opentmf.cadenzaflow.config.identity.token.TokenIdentityProviderPlugin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Token-based identity, active when {@code plugin.identity.provider=token}: the engine
 * knows only the signed-in principal and the groups its token carries - no directory
 * reads, no user enumeration. See {@link KeycloakIdentityProvider} and
 * {@link EntraIdentityProvider} for the directory-backed alternatives; the switch keeps
 * all three mutually exclusive by construction.
 */
@Component
@ConditionalOnProperty(name = "plugin.identity.provider", havingValue = "token")
@ConfigurationProperties(prefix = "plugin.identity.token")
public class TokenIdentityProvider extends TokenIdentityProviderPlugin {
}
