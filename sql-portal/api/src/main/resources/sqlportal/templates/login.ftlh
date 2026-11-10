<div class="login-wrap">
  <div class="card login">
    <h1>Sign in</h1>
    <#if error??><p class="alert" role="alert">${error}</p></#if>
    <#if !ldap && !oidc>
    <p class="lede">No sign-in method is configured.</p>
    </#if>
    <#if ldap>
    <form method="post" action="/login" class="stack">
      <input type="hidden" name="csrf" value="${csrf}">
      <div class="field">
        <label for="username">Username</label>
        <input id="username" name="username" type="text" autocomplete="username" autofocus required>
      </div>
      <div class="field">
        <label for="password">Password</label>
        <input id="password" name="password" type="password" autocomplete="current-password" required>
      </div>
      <button type="submit">Sign in with directory account</button>
    </form>
    </#if>
    <#if ldap && oidc><div class="divider"><span>or</span></div></#if>
    <#if oidc>
    <a class="button secondary wide" href="/login/oidc">Sign in with single sign-on</a>
    </#if>
  </div>
</div>
