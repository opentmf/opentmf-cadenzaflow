package org.opentmf.cadenzaflow.config.identity.token;

import java.util.List;
import org.cadenzaflow.bpm.engine.identity.User;
import org.cadenzaflow.bpm.engine.impl.Page;
import org.cadenzaflow.bpm.engine.impl.UserQueryImpl;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandContext;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandExecutor;

/** Answers with at most the signed-in user; see {@link TokenIdentityProviderSession}. */
public class TokenUserQuery extends UserQueryImpl {

  private static final long serialVersionUID = 1L;

  public TokenUserQuery() {
    super();
  }

  public TokenUserQuery(CommandExecutor commandExecutor) {
    super(commandExecutor);
  }

  @Override
  public long executeCount(CommandContext commandContext) {
    return session(commandContext).findUsers(this).size();
  }

  @Override
  public List<User> executeList(CommandContext commandContext, Page page) {
    return session(commandContext).findUsers(this);
  }

  private static TokenIdentityProviderSession session(CommandContext commandContext) {
    return (TokenIdentityProviderSession) commandContext.getReadOnlyIdentityProvider();
  }
}
