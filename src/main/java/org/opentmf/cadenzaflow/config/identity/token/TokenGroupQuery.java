package org.opentmf.cadenzaflow.config.identity.token;

import java.util.List;
import org.cadenzaflow.bpm.engine.identity.Group;
import org.cadenzaflow.bpm.engine.impl.GroupQueryImpl;
import org.cadenzaflow.bpm.engine.impl.Page;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandContext;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandExecutor;

/** Answers with the signed-in token's groups; see {@link TokenIdentityProviderSession}. */
public class TokenGroupQuery extends GroupQueryImpl {

  private static final long serialVersionUID = 1L;

  public TokenGroupQuery() {
    super();
  }

  public TokenGroupQuery(CommandExecutor commandExecutor) {
    super(commandExecutor);
  }

  @Override
  public long executeCount(CommandContext commandContext) {
    return session(commandContext).findGroups(this).size();
  }

  @Override
  public List<Group> executeList(CommandContext commandContext, Page page) {
    return session(commandContext).findGroups(this);
  }

  private static TokenIdentityProviderSession session(CommandContext commandContext) {
    return (TokenIdentityProviderSession) commandContext.getReadOnlyIdentityProvider();
  }
}
