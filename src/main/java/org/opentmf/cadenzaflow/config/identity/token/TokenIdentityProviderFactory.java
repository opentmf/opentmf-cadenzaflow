package org.opentmf.cadenzaflow.config.identity.token;

import org.cadenzaflow.bpm.engine.impl.identity.ReadOnlyIdentityProvider;
import org.cadenzaflow.bpm.engine.impl.interceptor.Session;
import org.cadenzaflow.bpm.engine.impl.interceptor.SessionFactory;

/** Opens the stateless, token-backed identity session (pattern donor: the Keycloak plugin). */
public class TokenIdentityProviderFactory implements SessionFactory {

  private final TokenIdentityProviderPlugin configuration;

  public TokenIdentityProviderFactory(TokenIdentityProviderPlugin configuration) {
    this.configuration = configuration;
  }

  @Override
  public Class<?> getSessionType() {
    return ReadOnlyIdentityProvider.class;
  }

  @Override
  public Session openSession() {
    return new TokenIdentityProviderSession(configuration);
  }
}
