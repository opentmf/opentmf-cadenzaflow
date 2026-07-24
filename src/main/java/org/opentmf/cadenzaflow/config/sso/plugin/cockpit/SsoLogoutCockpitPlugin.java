package org.opentmf.cadenzaflow.config.sso.plugin.cockpit;

import java.util.Set;
import org.cadenzaflow.bpm.cockpit.plugin.spi.impl.AbstractCockpitPlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.SsoLogoutPluginConstants;

/**
 * @author Abdullah Beker
 */
public class SsoLogoutCockpitPlugin extends AbstractCockpitPlugin {

  @Override
  public Set<Class<?>> getResourceClasses() {
    return Set.of(SsoLogoutCockpitPluginRootResource.class);
  }

  @Override
  public String getId() {
    return SsoLogoutPluginConstants.ID;
  }
}
