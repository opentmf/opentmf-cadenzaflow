package org.opentmf.cadenzaflow.config.sso;

import java.io.Serial;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authentication of a webapp session opened by {@code POST /app/login-with-token}
 * (CF-SHARED-CALLBACK-3).
 *
 * <p>Why not keep Spring's {@link JwtAuthenticationToken}: it is {@code @Transient}, and
 * {@code HttpSessionSecurityContextRepository} silently refuses to store a transient
 * authentication. A session holding one would be empty on the next request. This copy
 * carries the same name, authorities and token, and is stored like any login.</p>
 *
 * @author Cezmi Aslan
 */
public class BearerSessionAuthentication extends AbstractAuthenticationToken {

  @Serial
  private static final long serialVersionUID = 1L;

  private final Jwt token;
  private final String name;

  public BearerSessionAuthentication(
      Jwt token, String name, Collection<? extends GrantedAuthority> authorities,
      BearerSessionDetails details) {
    super(authorities);
    this.token = token;
    this.name = name;
    setDetails(details);
    setAuthenticated(true);
  }

  /** A session's authentication from the token the resource server validated. */
  public static BearerSessionAuthentication of(
      JwtAuthenticationToken validated, BearerSessionDetails details) {
    return new BearerSessionAuthentication(
        validated.getToken(), validated.getName(), validated.getAuthorities(), details);
  }

  public Jwt getToken() {
    return token;
  }

  @Override
  public BearerSessionDetails getDetails() {
    return (BearerSessionDetails) super.getDetails();
  }

  @Override
  public Object getCredentials() {
    // Not kept: the token proved the identity at login and is not re-presented.
    return "";
  }

  @Override
  public Object getPrincipal() {
    return name;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof BearerSessionAuthentication that
        && super.equals(that) && token.getTokenValue().equals(that.token.getTokenValue());
  }

  @Override
  public int hashCode() {
    return 31 * super.hashCode() + token.getTokenValue().hashCode();
  }
}
