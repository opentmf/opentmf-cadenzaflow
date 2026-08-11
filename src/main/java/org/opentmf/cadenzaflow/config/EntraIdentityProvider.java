package org.opentmf.cadenzaflow.config;

import org.cadenzaflow.bpm.extension.entra.plugin.EntraIdentityProviderPlugin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Entra ID (Azure AD) identity provider, active when {@code plugin.identity.provider=entra}.
 * The engine mounts exactly one identity provider; the switch makes Keycloak and Entra
 * mutually exclusive by construction.
 */
@Component
@ConditionalOnProperty(name = "plugin.identity.provider", havingValue = "entra")
@ConfigurationProperties(prefix = "plugin.identity.entra")
public class EntraIdentityProvider extends EntraIdentityProviderPlugin {
}
