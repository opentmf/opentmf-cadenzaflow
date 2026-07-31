package org.opentmf.cadenzaflow.config.sso.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ServiceLoader;
import org.cadenzaflow.bpm.admin.plugin.spi.AdminPlugin;
import org.cadenzaflow.bpm.cockpit.plugin.spi.CockpitPlugin;
import org.cadenzaflow.bpm.tasklist.plugin.spi.TasklistPlugin;
import org.cadenzaflow.bpm.welcome.plugin.spi.WelcomePlugin;
import org.junit.jupiter.api.Test;
import org.opentmf.cadenzaflow.config.sso.plugin.admin.SsoLogoutAdminPlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.cockpit.SsoLogoutCockpitPlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.tasklist.SsoLogoutTasklistPlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.welcome.SsoLogoutWelcomePlugin;

/**
 * Loads each webapp plugin through {@link ServiceLoader}, exactly like the webapps'
 * DefaultAppPluginRegistry does at runtime. A stale provider FQCN in a
 * META-INF/services file is invisible to direct-instantiation tests and only blows up
 * on the first webapp page render (ServiceConfigurationError: Provider ... not found).
 */
class SsoLogoutPluginServiceLoaderTest {

  @Test
  void cockpitPluginIsDiscoverableViaServiceLoader() {
    assertTrue(ServiceLoader.load(CockpitPlugin.class).stream()
        .anyMatch(p -> p.type() == SsoLogoutCockpitPlugin.class));
  }

  @Test
  void tasklistPluginIsDiscoverableViaServiceLoader() {
    assertTrue(ServiceLoader.load(TasklistPlugin.class).stream()
        .anyMatch(p -> p.type() == SsoLogoutTasklistPlugin.class));
  }

  @Test
  void adminPluginIsDiscoverableViaServiceLoader() {
    assertTrue(ServiceLoader.load(AdminPlugin.class).stream()
        .anyMatch(p -> p.type() == SsoLogoutAdminPlugin.class));
  }

  @Test
  void welcomePluginIsDiscoverableViaServiceLoader() {
    assertTrue(ServiceLoader.load(WelcomePlugin.class).stream()
        .anyMatch(p -> p.type() == SsoLogoutWelcomePlugin.class));
  }
}
