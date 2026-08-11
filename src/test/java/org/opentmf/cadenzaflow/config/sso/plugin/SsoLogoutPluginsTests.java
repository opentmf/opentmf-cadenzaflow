package org.opentmf.cadenzaflow.config.sso.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentmf.cadenzaflow.config.sso.plugin.admin.SsoLogoutAdminPlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.admin.SsoLogoutAdminPluginRootResource;
import org.opentmf.cadenzaflow.config.sso.plugin.cockpit.SsoLogoutCockpitPlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.cockpit.SsoLogoutCockpitPluginRootResource;
import org.opentmf.cadenzaflow.config.sso.plugin.tasklist.SsoLogoutTasklistPlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.tasklist.SsoLogoutTasklistPluginRootResource;
import org.opentmf.cadenzaflow.config.sso.plugin.welcome.SsoLogoutWelcomePlugin;
import org.opentmf.cadenzaflow.config.sso.plugin.welcome.SsoLogoutWelcomePluginRootResource;

class SsoLogoutPluginsTests {

  @Test
  void adminPluginExposesItsRootResource() {
    SsoLogoutAdminPlugin plugin = new SsoLogoutAdminPlugin();
    assertEquals(SsoLogoutPluginConstants.ID, plugin.getId());
    assertEquals(Set.of(SsoLogoutAdminPluginRootResource.class), plugin.getResourceClasses());
    assertNotNull(new SsoLogoutAdminPluginRootResource());
  }

  @Test
  void cockpitPluginExposesItsRootResource() {
    SsoLogoutCockpitPlugin plugin = new SsoLogoutCockpitPlugin();
    assertEquals(SsoLogoutPluginConstants.ID, plugin.getId());
    assertEquals(Set.of(SsoLogoutCockpitPluginRootResource.class), plugin.getResourceClasses());
    assertNotNull(new SsoLogoutCockpitPluginRootResource());
  }

  @Test
  void tasklistPluginExposesItsRootResource() {
    SsoLogoutTasklistPlugin plugin = new SsoLogoutTasklistPlugin();
    assertEquals(SsoLogoutPluginConstants.ID, plugin.getId());
    assertEquals(Set.of(SsoLogoutTasklistPluginRootResource.class), plugin.getResourceClasses());
    assertNotNull(new SsoLogoutTasklistPluginRootResource());
  }

  @Test
  void welcomePluginExposesItsRootResource() {
    SsoLogoutWelcomePlugin plugin = new SsoLogoutWelcomePlugin();
    assertEquals(SsoLogoutPluginConstants.ID, plugin.getId());
    assertEquals(Set.of(SsoLogoutWelcomePluginRootResource.class), plugin.getResourceClasses());
    assertNotNull(new SsoLogoutWelcomePluginRootResource());
  }

  @Test
  void allPluginsShareTheSamePluginId() {
    assertTrue(SsoLogoutPluginConstants.ID.startsWith("sso-logout"));
  }
}
