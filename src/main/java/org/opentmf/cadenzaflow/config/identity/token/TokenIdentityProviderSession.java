package org.opentmf.cadenzaflow.config.identity.token;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.cadenzaflow.bpm.engine.identity.Group;
import org.cadenzaflow.bpm.engine.identity.GroupQuery;
import org.cadenzaflow.bpm.engine.identity.NativeUserQuery;
import org.cadenzaflow.bpm.engine.identity.Tenant;
import org.cadenzaflow.bpm.engine.identity.TenantQuery;
import org.cadenzaflow.bpm.engine.identity.User;
import org.cadenzaflow.bpm.engine.identity.UserQuery;
import org.cadenzaflow.bpm.engine.impl.GroupQueryImpl;
import org.cadenzaflow.bpm.engine.impl.UserQueryImpl;
import org.cadenzaflow.bpm.engine.impl.context.Context;
import org.cadenzaflow.bpm.engine.impl.identity.ReadOnlyIdentityProvider;
import org.cadenzaflow.bpm.engine.impl.interceptor.CommandContext;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.GroupEntity;
import org.cadenzaflow.bpm.engine.impl.persistence.entity.UserEntity;
import org.opentmf.cadenzaflow.config.sso.BearerSessionAuthentication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/**
 * The token-backed identity session: the one known user is the current request's Spring
 * Security principal, groups are its authorities. Stateless - every answer is computed
 * from the thread's security context, so threads without a request (job executor,
 * history cleanup) see no user and empty lists, never an error.
 */
public class TokenIdentityProviderSession implements ReadOnlyIdentityProvider {

  private final TokenIdentityProviderPlugin configuration;

  TokenIdentityProviderSession(TokenIdentityProviderPlugin configuration) {
    this.configuration = configuration;
  }

  // -------------------------------------------------------------------------
  // Users
  // -------------------------------------------------------------------------

  @Override
  public User findUserById(String userId) {
    User self = currentUser();
    return self != null && self.getId().equals(userId) ? self : null;
  }

  @Override
  public UserQuery createUserQuery() {
    return new TokenUserQuery(
        Context.getProcessEngineConfiguration().getCommandExecutorTxRequired());
  }

  @Override
  public UserQuery createUserQuery(CommandContext commandContext) {
    return new TokenUserQuery();
  }

  @Override
  public NativeUserQuery createNativeUserQuery() {
    throw new UnsupportedOperationException(
        "The token identity provider does not support native user queries");
  }

  @Override
  public boolean checkPassword(String userId, String password) {
    // There is no credential store to check against; login happens at the IdP.
    return false;
  }

  /** The signed-in user, or every user there is - which is the same list. */
  List<User> findUsers(UserQueryImpl query) {
    User self = currentUser();
    if (self == null) {
      return Collections.emptyList();
    }
    if (query.getId() != null && !query.getId().equals(self.getId())) {
      return Collections.emptyList();
    }
    if (query.getIds() != null && !List.of(query.getIds()).contains(self.getId())) {
      return Collections.emptyList();
    }
    if (query.getGroupId() != null && !groupIds().contains(query.getGroupId())) {
      return Collections.emptyList();
    }
    if (query.getEmail() != null && !Objects.equals(query.getEmail(), self.getEmail())) {
      return Collections.emptyList();
    }
    return List.of(self);
  }

  // -------------------------------------------------------------------------
  // Groups
  // -------------------------------------------------------------------------

  @Override
  public Group findGroupById(String groupId) {
    if (groupId == null) {
      return null;
    }
    if (groupId.equals(configuration.getAdministratorGroupName())
        || groupIds().contains(groupId)) {
      return group(groupId);
    }
    return null;
  }

  @Override
  public GroupQuery createGroupQuery() {
    return new TokenGroupQuery(
        Context.getProcessEngineConfiguration().getCommandExecutorTxRequired());
  }

  @Override
  public GroupQuery createGroupQuery(CommandContext commandContext) {
    return new TokenGroupQuery();
  }

  /** The current token's groups, filtered by the query's criteria. */
  List<Group> findGroups(GroupQueryImpl query) {
    if (query.getUserId() != null) {
      User self = currentUser();
      if (self == null || !query.getUserId().equals(self.getId())) {
        return Collections.emptyList();
      }
    }
    return groupIds().stream()
        .filter(id -> query.getId() == null || query.getId().equals(id))
        .filter(id -> query.getIds() == null || List.of(query.getIds()).contains(id))
        .map(TokenIdentityProviderSession::group)
        .map(Group.class::cast)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Tenants: none
  // -------------------------------------------------------------------------

  @Override
  public Tenant findTenantById(String tenantId) {
    return null;
  }

  @Override
  public TenantQuery createTenantQuery() {
    return new TokenTenantQuery(
        Context.getProcessEngineConfiguration().getCommandExecutorTxRequired());
  }

  @Override
  public TenantQuery createTenantQuery(CommandContext commandContext) {
    return new TokenTenantQuery();
  }

  // -------------------------------------------------------------------------
  // The token
  // -------------------------------------------------------------------------

  /** The signed-in principal as the engine's one user, or null off-request. */
  User currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication.getName() == null) {
      return null;
    }
    UserEntity user = new UserEntity();
    user.setId(authentication.getName());
    user.setEmail(authentication.getName());
    // Cockpit's header shows "firstName lastName" and renders a missing name as "null"
    // (ENGINE-COCKPIT-USER-LABEL-NULL-1): the token's names, or a readable fallback.
    Map<String, Object> claims = claims(authentication);
    String firstName = claim(claims, "given_name");
    String lastName = claim(claims, "family_name");
    if (firstName == null && lastName == null) {
      firstName = Stream.of("preferred_username", "email", "sub")
          .map(name -> claim(claims, name))
          .filter(Objects::nonNull)
          .findFirst()
          .orElse(authentication.getName());
    }
    user.setFirstName(firstName == null ? "" : firstName);
    user.setLastName(lastName == null ? "" : lastName);
    return user;
  }

  /** The identity claims behind the authentication: the login token's, or none. */
  private static Map<String, Object> claims(Authentication authentication) {
    if (authentication instanceof BearerSessionAuthentication session) {
      return session.getToken().getClaims();
    }
    if (authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal) {
      return principal.getAttributes();
    }
    return Collections.emptyMap();
  }

  /** The claim as a non-blank string, or null. */
  private static String claim(Map<String, Object> claims, String name) {
    Object value = claims.get(name);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  /** The token's authorities as engine group ids, the configured prefix stripped. */
  List<String> groupIds() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Collections.emptyList();
    }
    String prefix = configuration.getAuthorityPrefix();
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(Objects::nonNull)
        .map(a -> !prefix.isEmpty() && a.startsWith(prefix) ? a.substring(prefix.length()) : a)
        .distinct()
        .toList();
  }

  private static GroupEntity group(String groupId) {
    GroupEntity group = new GroupEntity(groupId);
    group.setName(groupId);
    group.setType("TOKEN");
    return group;
  }

  // -------------------------------------------------------------------------
  // Session lifecycle: stateless
  // -------------------------------------------------------------------------

  @Override
  public void flush() {
    // Stateless: nothing to flush.
  }

  @Override
  public void close() {
    // Stateless: nothing to close.
  }
}
