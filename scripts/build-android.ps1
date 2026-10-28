param([switch]$TestApk, [string]$ToolRoot = 'D:\others\KF-Note\.tools')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskJava = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\dev\jdk-21' }
$taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $ToolRoot 'android-sdk' }
$taskGradle = Join-Path $ToolRoot 'gradle-8.13/gradle-8.13/bin/gradle.bat'
if (!(Test-Path -LiteralPath $taskGradle)) { throw '请通过 -ToolRoot 指定包含 gradle-8.13、android-sdk 的工具目录。' }
$taskOldJava = $env:JAVA_HOME
$taskOldSdk = $env:ANDROID_HOME
$taskOldCache = $env:GRADLE_USER_HOME
$taskOldOpts = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_HOME = $taskJava
    $env:ANDROID_HOME = $taskSdk
    $env:GRADLE_USER_HOME = Join-Path $ToolRoot 'gradle-cache'
    New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot '.tools/jvm-tmp'),(Join-Path $taskRoot 'artifacts') | Out-Null
    $taskTmp = Join-Path $taskRoot '.tools/jvm-tmp'
    $env:JAVA_TOOL_OPTIONS = "$taskOldOpts `"-Djava.io.tmpdir=$taskTmp`" `"-Djdk.net.unixdomain.tmpdir=$taskTmp`"".Trim()
    [IO.File]::WriteAllText((Join-Path $taskRoot 'android/local.properties'), "sdk.dir=$($taskSdk.Replace('\','/'))`n")
    $taskKey = Join-Path $taskRoot '.tools/debug.keystore'
    if (!(Test-Path -LiteralPath $taskKey)) {
        & (Join-Path $taskJava 'bin/keytool.exe') -genkeypair -keystore $taskKey -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=CaiDan Debug,O=CaiDan,C=CN'
        if ($LASTEXITCODE -ne 0) { throw '生成调试签名失败' }
    }
    $taskArgs = @('-p', (Join-Path $taskRoot 'android'), '--no-daemon', '--console=plain', 'assembleDebug')
    if ($TestApk) { $taskArgs += 'assembleDebugAndroidTest' }
    & $taskGradle @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败' }
    Copy-Item -LiteralPath (Join-Path $taskRoot 'android/app/build/outputs/apk/debug/app-debug.apk') -Destination (Join-Path $taskRoot 'artifacts/CaiDan-0.1.3-debug.apk') -Force
    Write-Output '已生成 artifacts/CaiDan-0.1.3-debug.apk'
} finally {
    $env:JAVA_HOME = $taskOldJava
    $env:ANDROID_HOME = $taskOldSdk
    $env:GRADLE_USER_HOME = $taskOldCache
    $env:JAVA_TOOL_OPTIONS = $taskOldOpts
}
