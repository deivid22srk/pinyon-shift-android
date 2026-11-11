[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$WorkRoot,
    [Parameter(Mandatory)] [string]$RenderTestScript,
    [string]$GameRoot,
    [string]$RuntimeConfig,
    [string]$BuildDirectory,
    [string]$SeedShaderCacheRoot,
    # Pack misses recorded by earlier runs (<state>/cache/fh1-shader-misses):
    # title-generated shaders the empty-profile route does not reach.
    [string]$ShaderMissDir,
    [switch]$Hidden,
    [switch]$AllowPipelineDiscovery,
    # Graphics setup: shader variants the compiler-free route reaches but the
    # producer route did not are a warning, not a failure. The route runs on
    # wall-clock time, so on a slower machine the producer run (which
    # translates shaders as it goes) and the compiler-free run reach different
    # screens. The game drops such a draw and records the miss, and the next
    # launch prepares the pack again with it.
    [switch]$AllowShaderMisses,
    # Hang guard for each route launch. It includes startup, the disc corpus
    # translation and pipeline prewarming, which take minutes on low-end CPUs.
    [ValidateRange(60, 86400)] [int]$RouteTimeoutSeconds = 1800,
    [ValidateRange(1, 4)] [int]$Scale = 1,
    [switch]$IncludeOpeningMovies,
    [switch]$JsonEvents
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-common.ps1')
$root = Get-PinyonRepoRoot
$work = Resolve-PinyonLocalPath -RelativePath $WorkRoot

# Runs a native command with its output in $Log and returns its exit code.
# Windows PowerShell, which the launcher uses, turns redirected stderr (a
# CMake or compiler warning) into a terminating error under 'Stop'.
function Invoke-LoggedNative([string]$Log, [scriptblock]$Command) {
    $ErrorActionPreference = 'Continue'
    # A command that never started leaves no exit code; count it as failed.
    $global:LASTEXITCODE = -1
    & $Command *> $Log
    $LASTEXITCODE
}

# The newest runtime log of a route launch's state, or $null.
function Get-RouteLog([string]$StateRoot) {
    $logs = Join-Path $StateRoot 'logs'
    if (-not (Test-Path -LiteralPath $logs)) { return $null }
    $found = @(Get-ChildItem -LiteralPath $logs -Filter 'runtime*.log' -File | Sort-Object LastWriteTimeUtc)
    if ($found.Count -eq 0) { return $null }
    $found[$found.Count - 1].FullName
}

# A route failure naming the launch result, exit code and runtime log.
# setup-preview.ps1 copies build_log, exit_code and the log's tail into
# .local/logs/setup-error.json.
function New-RouteFailure([string]$Message, [string]$StateRoot, $Launch, [string[]]$Excerpt) {
    $text = $Message
    $exitCode = $null
    if ($null -ne $Launch) {
        $exitCode = $Launch.exit_code
        $text += " Route result: $($Launch.result), exit code $exitCode."
        if ($Launch.PSObject.Properties['bundle'] -and $Launch.bundle) { $text += " Crash report: $($Launch.bundle)." }
    }
    $log = Get-RouteLog $StateRoot
    $text += if ($log) { " Runtime log: $log" } else { " No runtime log was written under $StateRoot." }
    $failure = [Exception]::new($text)
    $failure.Data['step'] = if ((Split-Path $StateRoot -Leaf) -eq 'producer-state') { 'offline shader producer' }
        else { 'compiler-free route check' }
    if ($log) { $failure.Data['build_log'] = $log }
    $failure.Data['exit_code'] = $exitCode
    if ($Excerpt) { $failure.Data['error_excerpt'] = $Excerpt }
    $failure
}

# Checks the compiler-free route's state and returns its execution counters
# and shader misses, or throws a failure that says what went wrong.
function Get-CompilerFreeRouteVerdict([string]$StateRoot, $Launch, [int64]$PackEntryCount, [bool]$AllowMisses) {
    if ($Launch.result -ne 'normal-exit') { throw (New-RouteFailure 'The compiler-free route failed.' $StateRoot $Launch) }
    $logs = Join-Path $StateRoot 'logs'
    $routeEvents = @(Get-ChildItem -LiteralPath $logs -Filter '*.jsonl' -File -ErrorAction SilentlyContinue |
        ForEach-Object { Get-Content -LiteralPath $_.FullName } | ForEach-Object { $_ | ConvertFrom-Json })
    $log = @(Get-ChildItem -LiteralPath $logs -Filter 'runtime*.log' -File -ErrorAction SilentlyContinue |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
    if (@($routeEvents | Where-Object event -eq 'fh1.render_test.complete').Count -ne 1) {
        $reasons = @($routeEvents | Where-Object event -eq 'fh1.render_test.failure' | ForEach-Object { $_.reason })
        throw (New-RouteFailure ('The compiler-free route did not complete' +
            $(if ($reasons.Count) { " ($($reasons -join ', '))" }) + '.') $StateRoot $Launch)
    }
    # The native renderer draws through the pack's pipelines directly; its
    # executor must have rendered the route.
    if (-not ($log -match 'FH1 native executor enabled: native')) {
        throw (New-RouteFailure 'The native renderer was not enabled for the compiler-free route.' $StateRoot $Launch)
    }
    $stats = [regex]::Matches($log, 'FH1 native executor frame=(\d+) draws=(\d+) resolves=(\d+)')
    if ($stats.Count -eq 0 -or [int64]$stats[$stats.Count - 1].Groups[2].Value -eq 0) {
        throw (New-RouteFailure 'The native renderer did not execute the route.' $StateRoot $Launch)
    }
    $last = $stats[$stats.Count - 1]
    $execution = [ordered]@{ renderer = 'native'; frames = [int64]$last.Groups[1].Value
        draws = [int64]$last.Groups[2].Value; resolves = [int64]$last.Groups[3].Value }
    # A pack that did not load is fatal: the game would drop every draw.
    if (-not ($log -match "Loaded $PackEntryCount FH1 precompiled shaders")) {
        $loaded = [regex]::Match($log, 'Loaded (\d+) FH1 precompiled shaders')
        $ignored = [regex]::Match($log, 'Ignoring FH1 precompiled shader pack [^\r\n]*')
        $detail = if ($ignored.Success) { $ignored.Value.Trim() }
            elseif ($loaded.Success) { "it loaded $($loaded.Groups[1].Value) of $PackEntryCount shaders" }
            else { 'the runtime did not find the staged pack' }
        throw (New-RouteFailure "The compiler-free route did not load the produced shader pack: $detail." `
            $StateRoot $Launch @($detail))
    }
    # Shader variants the pack lacks. The runtime logs each one and records it
    # under the state's cache/fh1-shader-misses, named after its stage.
    $recordDirectory = Join-Path $StateRoot 'cache/fh1-shader-misses'
    $records = @(Get-ChildItem -LiteralPath $recordDirectory -Filter '*.bin' -File -ErrorAction SilentlyContinue)
    $kinds = [ordered]@{ vertex = 0; pixel = 0; geometry = 0 }
    foreach ($record in $records) {
        if ($record.Name -match '^(vertex|pixel|geometry)-') { $kinds[$Matches[1]]++ }
    }
    $logged = @([regex]::Matches($log, 'FH1 precompiled shader pack miss for ([^\r\n]+)') |
        ForEach-Object { $_.Groups[1].Value.Trim() } | Sort-Object -Unique)
    $count = [Math]::Max($records.Count, $logged.Count)
    $misses = [ordered]@{
        count = $count; vertex = $kinds.vertex; pixel = $kinds.pixel; geometry = $kinds.geometry
        examples = @($logged | Select-Object -First 8)
        records = if ($records.Count) { $recordDirectory } else { $null }
        tolerated = $count -gt 0 -and $AllowMisses
    }
    $warning = $null
    if ($count) {
        $text = "$count shader variants missing from the pack ($($kinds.vertex) vertex, $($kinds.pixel) pixel, " +
            "$($kinds.geometry) geometry recorded; first: $(@($logged | Select-Object -First 3) -join ', '))"
        if (-not $AllowMisses) {
            throw (New-RouteFailure "The compiler-free route reached $text." $StateRoot $Launch `
                @($logged | Select-Object -First 8 | ForEach-Object { "FH1 precompiled shader pack miss for $_" }))
        }
        $warning = "The compiler-free check reached $text. The game draws without them and records them, " +
            'and the next launch adds them to the pack.'
    }
    [ordered]@{ execution = $execution; shader_misses = $misses; warning = $warning
        runtime_log = Get-RouteLog $StateRoot; exit_code = $Launch.exit_code }
}

# Runs one route launch; a timeout or launch error names the state's log.
function Invoke-Route([hashtable]$Arguments, [string]$StateRoot, [string]$Name) {
    try { & (Join-Path $PSScriptRoot 'launch-preview.ps1') @Arguments -StateRoot $StateRoot | ConvertFrom-Json }
    catch { throw (New-RouteFailure "The $Name did not finish: $($_.Exception.Message)" $StateRoot $null) }
}

# ponytail: restart-only production; preserve failed work for diagnosis until
# receipt-based resume can prove the inputs and every intermediate unchanged.
if (Test-Path -LiteralPath $work) { throw 'Use a new .local work directory; existing production is never overwritten.' }
if (Get-Process pinyon_shift -ErrorAction SilentlyContinue) { throw 'Close the preview before producing renderer artifacts.' }
$game = if ($GameRoot) { (Resolve-Path -LiteralPath $GameRoot).Path } else { Join-Path $root '.local/game/base' }
$script = (Resolve-Path -LiteralPath $RenderTestScript).Path
$python = Get-PinyonPython
$supportedDumps = (Get-Content (Join-Path $root 'config/supported-dumps.json') -Raw | ConvertFrom-Json).dumps
# The game root may come from either supported dump; keep the one it matches.
$dump = $supportedDumps | Where-Object {
    @($_.executables | Where-Object {
        $path = Join-Path $game $_.guest_path
        (Get-Item -LiteralPath $path).Length -eq [int64]$_.size_bytes -and
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $_.sha256
    }).Count -eq $_.executables.Count
} | Select-Object -First 1
if ($null -eq $dump) {
    throw 'The extracted game executables do not match the supported FH1 revision.'
}
[void](New-Item -ItemType Directory -Path $work)
$build = if ($BuildDirectory) { (Resolve-Path -LiteralPath $BuildDirectory).Path } else { Join-Path $root 'out/build/win-amd64-release' }
$environment = Enter-PinyonBuildEnvironment
Write-PinyonEvent shaders 0 'Building the offline shader producer.' -JsonEvents:$JsonEvents
if (Invoke-LoggedNative (Join-Path $work 'build.log') {
    & $environment.CMake --build $build --config Release --target rexgpu-fh1-producer pinyon_shift_fh1_archive_extract
}) { throw "Producer build failed. See $work/build.log." }
Write-PinyonEvent shaders 10 'Extracting shader programs from the local game files.' -JsonEvents:$JsonEvents
if (Invoke-LoggedNative (Join-Path $work 'extract.log') {
    & $python (Join-Path $PSScriptRoot 'extract-fh1-shader-corpus.py') $game `
        --output (Join-Path $work 'corpus.json') --binary-dir (Join-Path $work 'corpus') `
        --archive-extractor (Join-Path $build 'pinyon_shift_fh1_archive_extract.exe')
}) { throw "Shader extraction failed. See $work/extract.log." }

# Each phase starts with its own empty state. Never copy a player's save or
# borrow shader caches from a developer installation.
$arguments = @("--draw_resolution_scale_x=$Scale", "--draw_resolution_scale_y=$Scale")
$launch = @{
    GameRoot = $game; BuildDirectory = $build; RenderTestScript = $script; RenderTestTimeoutSeconds = $RouteTimeoutSeconds
    RenderTestIncludeOpeningMovies = $IncludeOpeningMovies; CollectFh1PassInventory = $true
    GameArguments = $arguments; Json = $true
    Hidden = $Hidden
}
Write-PinyonEvent shaders 20 'Producing shaders and collecting startup pipelines.' -JsonEvents:$JsonEvents
$producerState = Join-Path $work 'producer-state'
if ($SeedShaderCacheRoot) {
    $seedDirectory = Join-Path $producerState 'cache/shaders/shareable'
    [void][IO.Directory]::CreateDirectory($seedDirectory)
    foreach ($name in @('4D5309C9.xsh', '4D5309C9.rtv.d3d12.xpso')) {
        Copy-Item -LiteralPath (Join-Path $SeedShaderCacheRoot $name) -Destination $seedDirectory
    }
}
if ($ShaderMissDir) {
    $missDirectory = Join-Path $producerState 'cache/fh1-shader-misses'
    [void][IO.Directory]::CreateDirectory($missDirectory)
    Get-ChildItem -LiteralPath $ShaderMissDir -Filter '*.bin' |
        Where-Object Name -Match '^(vertex|pixel|geometry)-[0-9A-F]{16}-[0-9A-F]{16}\.bin$' |
        Copy-Item -Destination $missDirectory
}
if ($RuntimeConfig -and (Test-Path -LiteralPath $RuntimeConfig)) {
    foreach ($phase in @('producer-state', 'strict-state')) {
        $configDirectory = Join-Path $work "$phase/config"
        [void][IO.Directory]::CreateDirectory($configDirectory)
        Copy-Item -LiteralPath $RuntimeConfig -Destination (Join-Path $configDirectory 'pinyon_shift.toml')
    }
}
$producer = Invoke-Route ($launch + @{
    DiscShaderCorpusDir = (Join-Path $work 'corpus'); ShaderCaptureDir = (Join-Path $work 'translation')
    RenderTestOutput = (Join-Path $work 'producer-output')
}) $producerState 'offline shader producer'
if ($producer.result -ne 'normal-exit') { throw (New-RouteFailure 'The offline producer did not exit normally.' $producerState $producer) }
$events = @(Get-Content (Join-Path $producerState 'logs/*.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$summary = @($events | Where-Object event -eq 'native_renderer.shader_capture.summary')
$complete = @($events | Where-Object event -eq 'fh1.render_test.complete')
if ($summary.Count -ne 1 -or $complete.Count -ne 1 -or
    [int]$summary[0].rejected_callbacks -ne 0 -or [int]$summary[0].entries -eq 0) {
    $evidence = "$($summary.Count) capture summaries, $($complete.Count) route completions"
    if ($summary.Count -eq 1) { $evidence += ", $($summary[0].entries) entries, $($summary[0].rejected_callbacks) rejected" }
    throw (New-RouteFailure "Shader production did not provide complete, error-free capture evidence ($evidence)." `
        $producerState $producer)
}
$runtimeLog = @(Get-Content (Join-Path $producerState 'logs/runtime*.log') -Raw) -join "`n"
if (-not ($runtimeLog -match 'FH1 disc corpus translated \d+ vertex and \d+ pixel shader variants with 0 failures')) {
    throw (New-RouteFailure 'The disc shader translation did not finish successfully.' $producerState $producer)
}
Write-PinyonEvent shaders 75 'Validating the shader pack and startup catalogs.' -JsonEvents:$JsonEvents
$packPath = Join-Path $work 'shaders.pnsp'
$packJson = & $python (Join-Path $PSScriptRoot 'native-shader-pack.py') build `
    (Join-Path $work 'translation/shader-manifest.json') --output $packPath
if ($LASTEXITCODE) { throw 'Shader pack validation failed.' }
$pack = $packJson | ConvertFrom-Json
if ($pack.entry_count -ne [int]$summary[0].entries -or
    $pack.draw_resolution_scale_x -ne $Scale -or $pack.draw_resolution_scale_y -ne $Scale) {
    throw 'The shader pack does not match the producer evidence or requested scale.'
}
$strictState = Join-Path $work 'strict-state'
if (Invoke-LoggedNative (Join-Path $work 'catalog.log') {
    & $python (Join-Path $PSScriptRoot 'build-fh1-gpu-prewarm.py') `
        (Join-Path $strictState 'cache/fh1-gpu-prewarm-v3.txt') --legacy-cache (Join-Path $producerState 'cache') `
        --all-stored-pipelines
}) { throw "Startup catalog construction failed. See $work/catalog.log." }
if (Invoke-LoggedNative (Join-Path $work 'stage.json') {
    & $python (Join-Path $PSScriptRoot 'native-shader-pack.py') stage $packPath --state-root $strictState --scale $Scale
}) { throw "Shader pack staging failed. See $work/stage.json." }
Write-PinyonEvent shaders 90 'Checking the captured route with the compiler-free renderer.' -JsonEvents:$JsonEvents
$strict = Invoke-Route ($launch + @{ RenderTestOutput = (Join-Path $work 'strict-output') }) `
    $strictState 'compiler-free route'
$verdict = Get-CompilerFreeRouteVerdict $strictState $strict ([int64]$pack.entry_count) ([bool]$AllowShaderMisses)
if ($verdict.warning) {
    Write-PinyonEvent shaders 95 "$($verdict.warning) Details: $(Join-Path $work 'production.json')" -JsonEvents:$JsonEvents
}
# Plugins carry the build type's postfix (PluginFileName in the SDK).
$buildType = (Select-String -LiteralPath (Join-Path $build 'CMakeCache.txt') `
    -Pattern '^CMAKE_BUILD_TYPE:STRING=(.+)$' | Select-Object -First 1).Matches[0].Groups[1].Value
$pluginPostfix = switch ($buildType) { 'Debug' { 'd' } 'RelWithDebInfo' { 'rd' } default { '' } }
$report = [ordered]@{
    schema_version = 1
    # route-validated still means zero pack misses (see shader_misses).
    result = if ($AllowPipelineDiscovery -or $verdict.shader_misses.count) { 'shaders-validated' } else { 'route-validated' }
    # New D3D12 pipeline states can use prepared bytecode without translating
    # game shaders. Record this distinction instead of claiming complete PSO coverage.
    pipeline_discovery_allowed = [bool]$AllowPipelineDiscovery
    gameplay_ready = $false
    dump_id = $dump.id; render_test_sha256 = (Get-FileHash -LiteralPath $script).Hash
    corpus_sha256 = (Get-FileHash -LiteralPath (Join-Path $work 'corpus.json')).Hash
    pack = $pack; producer_pid = $producer.process_id; strict_pid = $strict.process_id
    runtime_sha256 = (Get-FileHash -LiteralPath (Join-Path $build "rexgpu-fh1$pluginPostfix.dll")).Hash
    producer_sha256 = (Get-FileHash -LiteralPath (Join-Path $build "rexglue-artifacts/rexgpu-fh1-producer$pluginPostfix.dll")).Hash
    executable_sha256 = (Get-FileHash -LiteralPath (Join-Path $build 'pinyon_shift.exe')).Hash
    execution = $verdict.execution
    shader_misses = $verdict.shader_misses
    strict_exit_code = $verdict.exit_code; strict_runtime_log = $verdict.runtime_log
}
[IO.File]::WriteAllText((Join-Path $work 'production.json'), ($report | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-PinyonEvent shaders 100 'Graphics validation finished.' -JsonEvents:$JsonEvents
$report | ConvertTo-Json -Depth 6
