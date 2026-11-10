<section class="page-head">
  <h1>Scripts</h1>
  <#if total gt 0>
  <p class="lede">Pick a script, fill in its parameters and run it.</p>
  </#if>
</section>
<#if total gt 0 || locked gt 0>
<div class="searchbar">
  <#if total gt 0>
  <label class="visually-hidden" for="script-filter">Search scripts</label>
  <input id="script-filter" class="search" type="search" placeholder="Search by name, description, group or permission  (press /)" autocomplete="off" data-filter-scripts>
  <span class="count" data-filter-count aria-live="polite">${total} shown</span>
  </#if>
  <#if locked gt 0>
  <a class="button secondary small show-toggle" href="<#if showAll>/<#else>/?all=1</#if>" data-show-all aria-pressed="${showAll?c}">
    <#if showAll>Hide locked scripts<#else>Show all scripts <span class="count">${locked} locked</span></#if>
  </a>
  </#if>
</div>
</#if>
<#if total gt 0>
<div class="script-groups">
  <#list groups as group>
  <section class="script-group" data-group>
    <#if group.name??>
    <h2 class="group-title">${group.name} <span class="count" data-group-count>${group.scripts?size}</span></h2>
    </#if>
    <ul class="script-grid">
      <#list group.scripts as s>
      <#if s.accessible>
      <li class="script-card" data-filter-text="${s.title} ${s.name} ${s.description} ${group.name!''}">
        <a href="/scripts/${s.name}">
          <span class="title">${s.title}</span>
          <span class="desc">${s.description}</span>
          <span class="go" aria-hidden="true">Open →</span>
        </a>
      </li>
      <#else>
      <li class="script-card locked" data-filter-text="${s.title} ${s.name} ${s.description} ${group.name!''} ${s.requiredPermissions?join(' ')}">
        <div class="locked-body" aria-disabled="true">
          <div class="locked-head">
            <span class="title">${s.title}</span>
            <span class="lock-badge">Locked</span>
          </div>
          <span class="desc">${s.description}</span>
          <span class="perms-label">Requires all of</span>
          <ul class="perms">
            <#list s.requiredPermissions as p><li><code>${p}</code></li></#list>
          </ul>
        </div>
      </li>
      </#if>
      </#list>
    </ul>
  </section>
  </#list>
</div>
<p class="no-match" data-filter-empty hidden>No scripts match your search.</p>
<#else>
<div class="empty-state">
  <p class="lede">You have no scripts you can run.</p>
  <#if locked gt 0>
  <p>${locked} scripts are locked for your account. Show them to see what they require.</p>
  <#else>
  <p>Ask an administrator to grant you access.</p>
  </#if>
</div>
</#if>
