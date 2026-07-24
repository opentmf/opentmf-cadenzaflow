package org.opentmf.cadenzaflow.config.sso.plugin.welcome;

import java.util.Set;
import org.cadenzaflow.bpm.welcome.plugin.spi.impl.AbstractWelcomePlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.SsoLogoutPluginConstants;

/**
 * @author Abdullah Beker
 */
public class SsoLogoutWelcomePlugin extends AbstractWelcomePlugin {

  @Override
  public Set<Class<?>> getResourceClasses() {
    return Set.of(SsoLogoutWelcomePluginRootResource.class);
  }

  @Override
  public String getId() {
    return SsoLogoutPluginConstants.ID;
  }
}
