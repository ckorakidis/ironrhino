<div class="control-group<#if parameters.name?? && fieldErrors?? && fieldErrors[parameters.name]??> error</#if>"<#if parameters.dynamicAttributes['data-group']?has_content> data-group="${parameters.dynamicAttributes['data-group']}"</#if>>
<#if parameters.label??>
<label class="control-label"<#if parameters.id??> for="${parameters.id?html}"</#if>>${parameters.label?html}</label>
</#if>
<div class="controls">