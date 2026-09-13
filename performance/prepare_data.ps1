param(
    [Parameter(Mandatory = $true)]
    [string]$Schema,
    [Parameter(Mandatory = $true)]
    [string]$RunId,
    [int]$RegisteredUsers = 1000,
    [int]$ActiveUsers = 200,
    [int]$ProjectsPerActiveUser = 10,
    [int]$HistoricalTasksPerActiveUser = 50,
    [string]$MySqlExe = "D:\mysql-8.0.41-winx64\mysql-8.0.41-winx64\bin\mysql.exe"
)

$ErrorActionPreference = "Stop"

$baselineSchema = $Schema -match '^fctts_phase(11|13)_[a-z0-9_]+$'
$phase16Schema = $Schema -match '^fctts_phase16_[a-z0-9_]+$'
if ((-not $baselineSchema -and -not $phase16Schema) -or $Schema -eq 'zhiyunjiaos') {
    throw "Refusing to seed unsafe schema name: $Schema"
}
if ($RunId -notmatch '^[a-z0-9_-]{4,32}$') {
    throw "RunId must contain only lowercase letters, numbers, underscore, or hyphen"
}
if (-not $env:LOAD_TEST_PASSWORD) {
    throw "LOAD_TEST_PASSWORD must be set in the current process"
}
if ($RegisteredUsers -lt 1000 -or $ActiveUsers -lt 200 -or
        $ProjectsPerActiveUser -lt 10 -or $HistoricalTasksPerActiveUser -lt 50) {
    throw "Load-test data must satisfy the Scenario C baseline"
}
if (-not (Test-Path -LiteralPath $MySqlExe)) {
    throw "mysql.exe not found: $MySqlExe"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$localConfig = Join-Path $projectRoot "config\application-local.yml"
$phaseLabel = if ($Schema -match '^fctts_phase13_') { 'phase13' }
    elseif ($Schema -match '^fctts_phase16_') { 'phase16' }
    else { 'phase11' }
$targetDir = Join-Path $projectRoot "target\${phaseLabel}-seed"
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

function Read-LocalScalar([string]$Pattern, [string]$Description) {
    $raw = Get-Content -Raw -LiteralPath $localConfig
    $match = [regex]::Match($raw, $Pattern)
    if (-not $match.Success) {
        throw "Missing $Description in ignored config/application-local.yml"
    }
    return $match.Groups[1].Value.Trim()
}

function New-BcryptHash {
    $cryptoJar = Get-ChildItem -Path "C:\Users\65374\.m2\repository\org\springframework\security\spring-security-crypto" `
        -Recurse -Filter "spring-security-crypto-*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object FullName -Descending | Select-Object -First 1
    $loggingJar = Get-ChildItem -Path "C:\Users\65374\.m2\repository\org\springframework\spring-jcl" `
        -Recurse -Filter "spring-jcl-*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $cryptoJar -or -not $loggingJar) {
        throw "Spring Security crypto runtime jars are missing; run Maven package first"
    }
    $source = Join-Path $targetDir "Phase11PasswordHash.java"
    $classes = Join-Path $targetDir "classes"
    $lib = Join-Path $targetDir "lib"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    New-Item -ItemType Directory -Force -Path $lib | Out-Null
    $localCryptoJar = Join-Path $lib $cryptoJar.Name
    $localLoggingJar = Join-Path $lib $loggingJar.Name
    Copy-Item -LiteralPath $cryptoJar.FullName -Destination $localCryptoJar -Force
    Copy-Item -LiteralPath $loggingJar.FullName -Destination $localLoggingJar -Force
    [System.IO.File]::WriteAllText($source, @'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public final class Phase11PasswordHash {
    public static void main(String[] args) {
        String password = System.getenv("LOAD_TEST_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("missing password");
        System.out.print(new BCryptPasswordEncoder(12).encode(password));
    }
}
'@, [System.Text.UTF8Encoding]::new($false))
    $classPath = "$localCryptoJar;$localLoggingJar"
    & javac -cp $classPath -d $classes $source
    if ($LASTEXITCODE -ne 0) { throw "Unable to compile BCrypt seed helper" }
    $hash = & java -cp "$classes;$classPath" Phase11PasswordHash
    if ($LASTEXITCODE -ne 0 -or -not $hash.StartsWith('$2')) {
        throw "Unable to generate BCrypt seed hash"
    }
    return $hash
}

$dbPassword = Read-LocalScalar '(?ms)^spring:\s*.*?datasource:\s*.*?password:\s*["'']?([^"''\r\n]*)' 'database password'
$passwordHash = New-BcryptHash
$sqlPath = Join-Path $targetDir "$RunId-seed.sql"
$writer = [System.IO.StreamWriter]::new($sqlPath, $false, [System.Text.UTF8Encoding]::new($false))

try {
    $writer.WriteLine("USE ``$Schema``;")
    $writer.WriteLine("SET FOREIGN_KEY_CHECKS=0;")

    for ($start = 1; $start -le $RegisteredUsers; $start += 200) {
        $end = [Math]::Min($start + 199, $RegisteredUsers)
        $values = for ($index = $start; $index -le $end; $index++) {
            $username = "${phaseLabel}_${RunId}_$($index.ToString('0000'))"
            "('$username','$passwordHash',0)"
        }
        $writer.WriteLine("INSERT INTO user(username,password,permission) VALUES $($values -join ',');")
    }

    for ($user = 1; $user -le $ActiveUsers; $user++) {
        $username = "${phaseLabel}_${RunId}_$($user.ToString('0000'))"
        $projectValues = for ($project = 1; $project -le $ProjectsPerActiveUser; $project++) {
            $number = $user * 100 + $project
            $projectId = "00000000-0000-4000-8000-$($number.ToString('000000000000'))"
            "('$projectId','$username','${phaseLabel}-project-$project','READY','scalability/$RunId/source-$number.pptx','scalability/$RunId/project-$number','source-$number.pptx','$phaseLabel seeded script',1,'longxiao',1.00,1.00,1.00,NOW(6),NOW(6),0)"
        }
        $writer.WriteLine("INSERT INTO courseware_project(project_id,owner_username,project_name,status,source_path,output_path,file_name,script,revision,voice,speed,pitch,rhythm,created_at,updated_at,lock_version) VALUES $($projectValues -join ',');")

        for ($startTask = 1; $startTask -le $HistoricalTasksPerActiveUser; $startTask += 25) {
            $endTask = [Math]::Min($startTask + 24, $HistoricalTasksPerActiveUser)
            $taskValues = for ($task = $startTask; $task -le $endTask; $task++) {
                $number = $user * 1000 + $task
                $taskId = "00000000-0000-4000-9000-$($number.ToString('000000000000'))"
                "('$taskId','$username','COURSEWARE_OPTIMIZE','{}','SUCCESS',100,1,1,NOW(6),'seed-$RunId-$number',NOW(6),NOW(6))"
            }
            $writer.WriteLine("INSERT INTO async_task(task_id,owner_username,task_type,payload_json,status,progress,attempts,max_attempts,available_at,deduplication_key,created_at,finished_at) VALUES $($taskValues -join ',');")
        }
    }

    $voiceValues = for ($voice = 1; $voice -le 200; $voice++) {
        "('${phaseLabel}-voice-$($voice.ToString('000'))','capacity-test','scalability/$RunId/voice-$voice.wav','audio/wav',1,'${phaseLabel}-seed')"
    }
    $writer.WriteLine("INSERT INTO voice(voice_name,application_scene,file_path,mime_type,public_visible,owner_username) VALUES $($voiceValues -join ',');")
    $writer.WriteLine("SET FOREIGN_KEY_CHECKS=1;")
} finally {
    $writer.Dispose()
}

$previousMySqlPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $dbPassword
    $mysqlSourcePath = $sqlPath.Replace('\', '/')
    & $MySqlExe -h 127.0.0.1 -P 3306 -u root --protocol=tcp --default-character-set=utf8mb4 --batch `
        -e "source $mysqlSourcePath"
    if ($LASTEXITCODE -ne 0) { throw "MySQL load-test seed failed" }
    & $MySqlExe -h 127.0.0.1 -P 3306 -u root --protocol=tcp --batch --skip-column-names `
        -e "SELECT CONCAT((SELECT COUNT(*) FROM ``$Schema``.user),',',(SELECT COUNT(*) FROM ``$Schema``.courseware_project),',',(SELECT COUNT(*) FROM ``$Schema``.async_task),',',(SELECT COUNT(*) FROM ``$Schema``.voice));"
    if ($LASTEXITCODE -ne 0) { throw "MySQL load-test seed verification failed" }
} finally {
    $env:MYSQL_PWD = $previousMySqlPassword
}
