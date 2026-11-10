<#-- One parameter of the script form. -->
<#macro field f>
<#if f.kind == "boolean">
<div class="field check<#if f.invalid> invalid</#if>">
  <label for="${f.id}">
    <input id="${f.id}" type="checkbox" name="${f.name}" value="true"<#if f.checked> checked</#if>>
    <span>${f.label}</span>
  </label>
<#else>
<div class="field<#if f.invalid> invalid</#if>">
  <label for="${f.id}">${f.label}<#if f.required> <span class="req" aria-hidden="true">*</span></#if></label>
  <#if f.kind == "text">
  <input id="${f.id}" type="text" name="${f.name}" value="${f.value}" maxlength="${f.maxLength}"<#if f.required> required</#if>>
  <#elseif f.kind == "integer">
  <input id="${f.id}" type="number" step="1" name="${f.name}" value="${f.value}"<#if f.min??> min="${f.min}"</#if><#if f.max??> max="${f.max}"</#if><#if f.required> required</#if>>
  <#elseif f.kind == "number">
  <input id="${f.id}" type="number" step="any" name="${f.name}" value="${f.value}"<#if f.min??> min="${f.min}"</#if><#if f.max??> max="${f.max}"</#if><#if f.required> required</#if>>
  <#elseif f.kind == "date">
  <input id="${f.id}" type="date" name="${f.name}" value="${f.value}"<#if f.required> required</#if>>
  <#elseif f.kind == "select">
  <select id="${f.id}" name="${f.name}"<#if f.required> required</#if>>
    <#list f.options as o>
    <option value="${o.value}"<#if o.selected> selected</#if>>${o.label}</option>
    </#list>
  </select>
  </#if>
</#if>
  <#if f.help??><div class="help">${f.help}</div></#if>
  <#if f.error??><div class="err" role="alert">${f.error}</div></#if>
</div>
</#macro>
