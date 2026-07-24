package org.opentmf.cadenzaflow.config.sso.plugin.welcome;

import jakarta.ws.rs.Path;
import org.cadenzaflow.bpm.welcome.resource.AbstractWelcomePluginRootResource;
import org.opentmf.cadenzaflow.config.sso.plugin.SsoLogoutPluginConstants;

/**
 * @author Abdullah Beker
 */
@Path("plugin/" + SsoLogoutPluginConstants.ID)
public class SsoLogoutWelcomePluginRootResource extends AbstractWelcomePluginRootResource {

  public SsoLogoutWelcomePluginRootResource() {
    super(SsoLogoutPluginConstants.ID);
  }
}
