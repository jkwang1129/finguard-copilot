$ErrorActionPreference = 'Stop'

function Assert-LastExitCode {
    param([string]$Step)

    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE."
    }
}

& .\mvnw.cmd -B -ntp clean test
Assert-LastExitCode 'Java verification'

Push-Location tools\agent-evaluation
try {
    uv sync --locked --python 3.12
    Assert-LastExitCode 'Python dependency sync'
    uv run --python 3.12 pytest -q
    Assert-LastExitCode 'Python tests'
}
finally {
    Pop-Location
}

Push-Location frontend
try {
    npm ci
    Assert-LastExitCode 'Frontend dependency install'
    npm run lint
    Assert-LastExitCode 'Frontend lint'
    npm run test -- --run
    Assert-LastExitCode 'Frontend tests'
    npm run build
    Assert-LastExitCode 'Frontend build'
}
finally {
    Pop-Location
}

Push-Location tools\mcp-bridge
try {
    uv sync --locked --python 3.12
    Assert-LastExitCode 'MCP bridge dependency sync'
    uv run --python 3.12 pytest -q
    Assert-LastExitCode 'MCP STDIO bridge tests'
}
finally {
    Pop-Location
}

git diff --check
Assert-LastExitCode 'Git whitespace check'
