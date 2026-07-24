package org.opentmf.cadenzaflow.config.sso;

import org.cadenzaflow.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * @author Abdullah Beker
 */
@ConfigurationProperties(OAuth2Properties.PREFIX)
public class OAuth2Properties {

  public static final String PREFIX = CamundaBpmProperties.PREFIX + ".oauth2";

  /** OAuth2 SSO logout properties. */
  @NestedConfigurationProperty
  private OAuth2SSOLogoutProperties ssoLogout = new OAuth2SSOLogoutProperties();

  public static class OAuth2SSOLogoutProperties {

    /** Enable SSO Logout. Default {@code false}. */
    private boolean enabled = false;

    /** URI the user is redirected after SSO logout from the provider. Default {@code {baseUrl}}. */
    private String postLogoutRedirectUri = "{baseUrl}";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getPostLogoutRedirectUri() {
      return postLogoutRedirectUri;
    }

    public void setPostLogoutRedirectUri(String postLogoutRedirectUri) {
      this.postLogoutRedirectUri = postLogoutRedirectUri;
    }
  }

  public OAuth2SSOLogoutProperties getSsoLogout() {
    return ssoLogout;
  }

  public void setSsoLogout(OAuth2SSOLogoutProperties ssoLogout) {
    this.ssoLogout = ssoLogout;
  }
}
