[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tokens=$null;$parseErrors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $root 'scripts/run-semantic-main-quality.ps1'),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Quality wrapper parse failed'}
$assignment=$ast.Find({param($node)
    $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
    $node.Left.Extent.Text -eq '$manifest'
},$true)
if($null -eq $assignment){throw 'Manifest assignment missing'}
# Evaluate only the actual local JSON read, never the paid wrapper or its services.
Invoke-Expression $assignment.Extent.Text
$culture=[Threading.Thread]::CurrentThread.CurrentCulture
try {
    foreach($name in @('zh-CN','en-US')){
        [Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo($name)
        foreach($field in @('priceValidFrom','priceValidUntil')){
            $value=$manifest.$field
            if($value -isnot [string] -or $value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$'){
                throw 'Reviewed price window lost its ISO string during environment transport'
            }
            [void][DateTimeOffset]::ParseExact($value,"yyyy-MM-dd'T'HH:mm:ss'Z'",
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AssumeUniversal)
        }
    }
} finally {[Threading.Thread]::CurrentThread.CurrentCulture=$culture}
Write-Output 'PASS frozen price dates remain ISO strings in zh-CN/en-US; no provider calls'
