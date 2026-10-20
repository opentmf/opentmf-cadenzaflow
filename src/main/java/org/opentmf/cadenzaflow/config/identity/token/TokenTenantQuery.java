package org.opentmf.cadenzaflow.config.identity.token;

import java.util.Collections;
import java.util.List;
import org.cadenzaflow.bpm.engine.identity.Tenant;
import org.cadenzaflow.bpm.engine.impl.Page;
import org.cadenzaflow.bpm.engine.impl.TenantQueryImpl;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandContext;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandExecutor;

/** Tokens carry no tenants; the query is always empty. */
public class TokenTenantQuery extends TenantQueryImpl {

  private static final long serialVersionUID = 1L;

  public TokenTenantQuery() {
    super();
  }

  public TokenTenantQuery(CommandExecutor commandExecutor) {
    super(commandExecutor);
  }

  @Override
  public long executeCount(CommandContext commandContext) {
    return 0;
  }

  @Override
  public List<Tenant> executeList(CommandContext commandContext, Page page) {
    return Collections.emptyList();
  }
}
