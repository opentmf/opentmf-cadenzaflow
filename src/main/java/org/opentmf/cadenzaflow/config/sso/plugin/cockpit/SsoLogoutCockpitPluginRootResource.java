package org.opentmf.cadenzaflow.config.sso.plugin.cockpit;

import jakarta.ws.rs.Path;
import org.cadenzaflow.bpm.cockpit.plugin.resource.AbstractCockpitPluginRootResource;
import org.opentmf.cadenzaflow.config.sso.plugin.SsoLogoutPluginConstants;

/**
 * @author Abdullah Beker
 */
@Path("plugin/" + SsoLogoutPluginConstants.ID)
public class SsoLogoutCockpitPluginRootResource extends AbstractCockpitPluginRootResource {

  public SsoLogoutCockpitPluginRootResource() {
    super(SsoLogoutPluginConstants.ID);
  }
}
