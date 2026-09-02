package org.opentmf.cadenzaflow.extensions.config;

import java.util.ArrayList;
import java.util.List;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the estate-standard {@code opentmf.tmf630.paging} keys onto the toolkit's
 * {@link Tmf630PagingSettings}. Bound here by hand because the toolkit's own
 * autoconfigure module is Spring-MVC-only and is deliberately not on the classpath —
 * the property names stay the toolkit's, so a deployment configures this service like
 * any other TMF-630 service in the estate.
 *
 * @author Cezmi Aslan
 */
@Component
@ConfigurationProperties("opentmf.tmf630.paging")
public class Tmf630PagingProperties {

  private int defaultLimit = 50;
  private int maxLimit = 500;
  /** Strict mode answers 400 to non-numeric offset/limit instead of ignoring them. */
  private boolean strictMode = true;
  private boolean allowNestedSortProperties = false;
  /** Empty means: no sort field is accepted (the shipped list is in config-cadenzaflow.yml). */
  private List<String> sortAllowlist = new ArrayList<>();

  public Tmf630PagingSettings toSettings() {
    return new Tmf630PagingSettings(
        true, defaultLimit, maxLimit, strictMode, allowNestedSortProperties, sortAllowlist);
  }

  public int getDefaultLimit() {
    return defaultLimit;
  }

  public void setDefaultLimit(int defaultLimit) {
    this.defaultLimit = defaultLimit;
  }

  public int getMaxLimit() {
    return maxLimit;
  }

  public void setMaxLimit(int maxLimit) {
    this.maxLimit = maxLimit;
  }

  public boolean isStrictMode() {
    return strictMode;
  }

  public void setStrictMode(boolean strictMode) {
    this.strictMode = strictMode;
  }

  public boolean isAllowNestedSortProperties() {
    return allowNestedSortProperties;
  }

  public void setAllowNestedSortProperties(boolean allowNestedSortProperties) {
    this.allowNestedSortProperties = allowNestedSortProperties;
  }

  public List<String> getSortAllowlist() {
    return sortAllowlist;
  }

  public void setSortAllowlist(List<String> sortAllowlist) {
    this.sortAllowlist = sortAllowlist;
  }
}
