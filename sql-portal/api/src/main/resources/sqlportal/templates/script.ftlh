<#import "fields.ftlh" as ui>
<nav class="crumbs" aria-label="Breadcrumb"><a href="/">All scripts</a><span aria-hidden="true">/</span><span>${title}</span></nav>
<section class="page-head">
  <h1>${title}</h1>
  <#if description??><p class="lede">${description}</p></#if>
</section>
<#if error??><p class="alert" role="alert">${error}</p></#if>

<#if hasParams>
<form class="params card" method="get" action="/scripts/${name}" data-run-form>
  <input type="hidden" name="_run" value="1">
  <div class="fields">
    <#list fields as f><@ui.field f=f/></#list>
  </div>
  <div class="actions">
    <button type="submit">Run <kbd>Ctrl</kbd>+<kbd>Enter</kbd></button>
    <button type="button" class="secondary" data-reset>Reset to defaults</button>
    <a class="button ghost" href="/">All scripts</a>
  </div>
</form>
</#if>

<div class="share-bar">
  <a class="button secondary small" href="${shareHref}" data-share-link title="Copy a link to this page with these parameters">Share link</a>
</div>
<div class="share-output" data-share-output hidden>
  <label for="share-url">Link to this page with these parameters</label>
  <div class="share-row">
    <input id="share-url" type="text" readonly data-share-url>
    <span class="share-note" role="status" data-share-note></span>
  </div>
</div>

<section id="results" class="results" aria-live="polite">
<#if run??>
  <div class="run-summary">
    <p class="meta">${run.meta}<#if run.notice??> · <span class="badge warn">${run.notice}</span></#if></p>
  </div>

  <#if run.charts?has_content>
  <div class="charts">
    <#list run.charts as c>
    <section class="card chart-card">
      <#if c.error??>
      <p class="alert" role="alert">Chart unavailable: ${c.error}</p>
      <#else>
      <h2 class="chart-title">${c.title}</h2>
      <div class="chart"><canvas data-chart="chart-data-${c.index}" role="img" aria-label="${c.title}"></canvas></div>
      <noscript><p class="help">Enable JavaScript to see this chart.</p></noscript>
      <p class="chart-fallback" data-chart-fallback hidden>The chart library could not be loaded. The data is in the table below.</p>
      <script type="application/json" id="chart-data-${c.index}">${c.json?no_esc}</script>
      </#if>
    </section>
    </#list>
  </div>
  </#if>

  <#if run.showTable>
    <#if run.empty>
    <p class="empty">The query returned no rows.</p>
    <#else>
    <div class="table-tools">
      <label class="visually-hidden" for="table-filter">Filter rows</label>
      <input id="table-filter" class="search" type="search" placeholder="Filter rows…" autocomplete="off" data-table-filter>
      <span class="count" data-table-count aria-live="polite">${run.rows?size} rows</span>
      <button type="button" class="secondary small" data-export-csv title="Exports the rows shown on this page">Export page as CSV</button>
      <#if run.pager??>
      <form class="page-size" method="get" action="/scripts/${name}" data-page-size-form>
        <#list run.pager.hiddenFields as hidden><input type="hidden" name="${hidden.name}" value="${hidden.value}"></#list>
        <input type="hidden" name="_run" value="1">
        <input type="hidden" name="_page" value="1">
        <label for="page-size">Rows per page</label>
        <select id="page-size" name="_size" data-auto-submit>
          <#list run.pager.sizeOptions as option><option value="${option.value}"<#if option.selected> selected</#if>>${option.value}</option></#list>
        </select>
        <noscript><button type="submit" class="secondary small">Apply</button></noscript>
      </form>
      </#if>
    </div>
    <div class="table-wrap">
      <table class="data" data-sortable>
        <thead><tr>
          <#list run.columns as c><th scope="col" tabindex="0" aria-sort="none">${c}</th></#list>
        </tr></thead>
        <tbody>
          <#list run.rows as row>
          <tr><#list row as cell><#if cell.numeric><td class="num">${cell.text}</td><#else><td>${cell.text}</td></#if></#list></tr>
          </#list>
        </tbody>
      </table>
      <p class="no-match" data-table-empty hidden>No rows match the filter.</p>
    </div>
    <#if run.pager?? && run.pager.pages gt 1>
    <nav class="pager" aria-label="Pagination">
      <p class="pager-summary">Page ${run.pager.current} of ${run.pager.pages}</p>
      <ol class="pager-bar">
        <li>
          <#if run.pager.prev??><a class="pager-arrow" rel="prev" href="${run.pager.prev}" aria-label="Previous page">&#8249;</a>
          <#else><span class="pager-arrow" aria-disabled="true" aria-label="No previous page">&#8249;</span></#if>
        </li>
        <#list run.pager.links as link>
          <#if !link.number??><li class="pager-far"><span class="pager-gap" aria-hidden="true">&#8230;</span></li>
          <#elseif link.current><li><span class="pager-page current" aria-current="page">${link.number}</span></li>
          <#else><li<#if link.far> class="pager-far"</#if>><a class="pager-page" href="${link.href}" aria-label="Page ${link.number}">${link.number}</a></li></#if>
        </#list>
        <li>
          <#if run.pager.next??><a class="pager-arrow" rel="next" href="${run.pager.next}" aria-label="Next page">&#8250;</a>
          <#else><span class="pager-arrow" aria-disabled="true" aria-label="No next page">&#8250;</span></#if>
        </li>
      </ol>
    </nav>
    </#if>
    </#if>
  </#if>
</#if>
</section>
