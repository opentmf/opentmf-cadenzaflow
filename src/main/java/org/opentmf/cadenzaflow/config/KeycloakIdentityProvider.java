package org.opentmf.cadenzaflow.config;

import org.cadenzaflow.bpm.extension.keycloak.plugin.KeycloakIdentityProviderPlugin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Keycloak identity provider, active by default ({@code plugin.identity.provider=keycloak}
 * or unset). See {@link EntraIdentityProvider} for the Entra ID alternative.
 */
@Component
@ConditionalOnProperty(name = "plugin.identity.provider", havingValue = "keycloak",
    matchIfMissing = true)
@ConfigurationProperties(prefix = "plugin.identity.keycloak")
public class KeycloakIdentityProvider extends KeycloakIdentityProviderPlugin {
}
