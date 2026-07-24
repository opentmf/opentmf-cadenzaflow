package org.opentmf.cadenzaflow.config.sso.plugin.admin;

import jakarta.ws.rs.Path;
import org.cadenzaflow.bpm.admin.resource.AbstractAdminPluginRootResource;
import org.opentmf.cadenzaflow.config.sso.plugin.SsoLogoutPluginConstants;

/**
 * @author Abdullah Beker
 */
@Path("plugin/" + SsoLogoutPluginConstants.ID)
public class SsoLogoutAdminPluginRootResource extends AbstractAdminPluginRootResource {

  public SsoLogoutAdminPluginRootResource() {
    super(SsoLogoutPluginConstants.ID);
  }
}
