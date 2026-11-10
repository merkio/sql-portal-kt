<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light dark">
  <title>${title} · ${uiTitle}</title>
  <link rel="stylesheet" href="/static/app.css?v=${assetVersion}">
</head>
<body>
  <a class="skip-link" href="#main">Skip to content</a>
  <header class="top">
    <a class="brand" href="/">${uiTitle}</a>
    <#if nav??>
    <div class="who">
      <span class="user" title="Signed in">${nav.displayName}</span>
      <#if nav.logoutCsrf??>
      <form method="post" action="/logout">
        <input type="hidden" name="csrf" value="${nav.logoutCsrf}">
        <button class="ghost" type="submit">Sign out</button>
      </form>
      </#if>
    </div>
    </#if>
  </header>
  <main id="main" class="container">
    ${body?no_esc}
  </main>
  <#if withCharts><script src="${chartjsUrl}" defer></script></#if>
  <script src="/static/app.js?v=${assetVersion}" defer></script>
</body>
</html>
