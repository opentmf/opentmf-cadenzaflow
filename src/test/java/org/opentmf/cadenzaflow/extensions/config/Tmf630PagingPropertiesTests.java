package org.opentmf.cadenzaflow.extensions.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;

/**
 * @author Cezmi Aslan
 */
class Tmf630PagingPropertiesTests {

  @Test
  void defaultsMatchTheToolkitContract() {
    Tmf630PagingProperties properties = new Tmf630PagingProperties();
    assertThat(properties.isStrictMode()).isTrue();
    assertThat(properties.isAllowNestedSortProperties()).isFalse();
    Tmf630PagingSettings settings = properties.toSettings();
    assertThat(settings.defaultLimit()).isEqualTo(50);
    assertThat(settings.maxLimit()).isEqualTo(500);
    assertThat(settings.strictMode()).isTrue();
    assertThat(settings.allowNestedSortProperties()).isFalse();
    assertThat(settings.sortAllowlist()).isEmpty();
  }

  @Test
  void everyKeyBindsThroughToTheSettings() {
    Tmf630PagingProperties properties = new Tmf630PagingProperties();
    properties.setDefaultLimit(10);
    properties.setMaxLimit(100);
    properties.setStrictMode(false);
    properties.setAllowNestedSortProperties(true);
    properties.setSortAllowlist(List.of("id"));

    Tmf630PagingSettings settings = properties.toSettings();
    assertThat(settings.defaultLimit()).isEqualTo(10);
    assertThat(settings.maxLimit()).isEqualTo(100);
    assertThat(settings.strictMode()).isFalse();
    assertThat(settings.allowNestedSortProperties()).isTrue();
    assertThat(settings.sortAllowlist()).containsExactly("id");
    assertThat(properties.getDefaultLimit()).isEqualTo(10);
    assertThat(properties.getMaxLimit()).isEqualTo(100);
    assertThat(properties.isStrictMode()).isFalse();
    assertThat(properties.isAllowNestedSortProperties()).isTrue();
    assertThat(properties.getSortAllowlist()).containsExactly("id");
  }
}
