package org.opentmf.cadenzaflow.config.sso.plugin.tasklist;

import jakarta.ws.rs.Path;
import org.cadenzaflow.bpm.tasklist.resource.AbstractTasklistPluginRootResource;
import org.opentmf.cadenzaflow.config.sso.plugin.SsoLogoutPluginConstants;

/**
 * @author Abdullah Beker
 */
@Path("plugin/" + SsoLogoutPluginConstants.ID)
public class SsoLogoutTasklistPluginRootResource extends AbstractTasklistPluginRootResource {

  public SsoLogoutTasklistPluginRootResource() {
    super(SsoLogoutPluginConstants.ID);
  }
}
