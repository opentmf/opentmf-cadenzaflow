package org.opentmf.cadenzaflow.config.sso.plugin.admin;

import java.util.Set;
import org.cadenzaflow.bpm.admin.plugin.spi.impl.AbstractAdminPlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.SsoLogoutPluginConstants;

/**
 * @author Abdullah Beker
 */
public class SsoLogoutAdminPlugin extends AbstractAdminPlugin {

  @Override
  public Set<Class<?>> getResourceClasses() {
    return Set.of(SsoLogoutAdminPluginRootResource.class);
  }

  @Override
  public String getId() {
    return SsoLogoutPluginConstants.ID;
  }
}
