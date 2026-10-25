import os
from pathlib import Path
import shutil
import subprocess

import pytest


PWSH = shutil.which("pwsh")
pytestmark = pytest.mark.skipif(PWSH is None, reason="PowerShell-specific launcher boundary")
SCRIPT = Path(__file__).resolve().parents[3] / "scripts/import-model-environment.ps1"


def run_import(path, command):
    environment = {name: value for name, value in os.environ.items()
                   if name not in {"COPILOT_MODEL_PROVIDER", "COPILOT_MODEL_API_KEY"}}
    return subprocess.run(
        [PWSH, "-NoProfile", "-Command", f". '{SCRIPT}' -Path '{path}'; {command}"],
        text=True, encoding="utf-8", capture_output=True, env=environment,
    )


def test_local_model_loader_imports_key_without_printing_it(tmp_path):
    path = tmp_path / ".env.local"
    path.write_text('COPILOT_MODEL_PROVIDER=deepseek\nCOPILOT_MODEL_API_KEY="test-only-credential"\n')
    result = run_import(path, "if ($env:COPILOT_MODEL_API_KEY -ne 'test-only-credential') { exit 2 }; $env:COPILOT_MODEL_PROVIDER")
    assert result.returncode == 0
    assert result.stdout.strip() == "deepseek"
    assert "test-only-credential" not in result.stderr


def test_local_model_loader_never_executes_value_as_powershell(tmp_path):
    path = tmp_path / ".env.local"
    path.write_text("COPILOT_MODEL_API_KEY=$(Write-Output EXECUTED)\n")
    result = run_import(path, "if ($env:COPILOT_MODEL_API_KEY -ne '$(Write-Output EXECUTED)') { exit 2 }; 'literal-value'")
    assert result.returncode == 0
    assert result.stdout.strip() == "literal-value"


def test_local_model_loader_does_not_import_unrelated_environment_variables(tmp_path):
    path = tmp_path / ".env.local"
    path.write_text("COPILOT_MODEL_PROVIDER=deepseek\nQUALITY_UNRELATED_VAR=bad\n")
    result = run_import(path, "if ($env:QUALITY_UNRELATED_VAR) { exit 2 }; 'allowlist-only'")
    assert result.returncode == 0
    assert result.stdout.strip() == "allowlist-only"


def test_comparison_plan_is_offline_and_does_not_create_reports_or_print_keys(tmp_path):
    import json
    environment_file = tmp_path / ".env.local"
    environment_file.write_text(
        "COPILOT_MODEL_PROVIDER=deepseek\nCOPILOT_MODEL_ID=deepseek-flash\n"
        "COPILOT_MODEL_API_KEY=test-only-credential\n", encoding="utf-8")
    output = tmp_path / "reports"
    script = SCRIPT.with_name("run-quality-comparison.ps1")
    result = subprocess.run(
        [PWSH, "-NoProfile", "-File", str(script), "-PlanOnly", "-EnvironmentFile",
         str(environment_file), "-OutputDirectory", str(output)], text=True, encoding="utf-8", capture_output=True,
    )
    assert result.returncode == 0, result.stderr
    plan = json.loads(result.stdout)
    assert plan["maxProviderCalls"] == 12
    assert plan["mode"] == "independent-provider-evaluation"
    assert not output.exists()
    assert "test-only-credential" not in result.stdout + result.stderr


def test_single_model_plan_names_output_after_loading_provider_and_never_overwrites(tmp_path):
    import json
    environment_file = tmp_path / ".env.local"
    environment_file.write_text(
        "COPILOT_MODEL_PROVIDER=deepseek\nCOPILOT_MODEL_ID=deepseek-flash\n"
        "COPILOT_MODEL_API_KEY=test-only-credential\nCOPILOT_PRICE_TABLE_VERSION=unpriced\n"
        "COPILOT_INPUT_PRICE_PER_MILLION=0\nCOPILOT_OUTPUT_PRICE_PER_MILLION=0\n"
        "FINGUARD_COPILOT_BASE_URL=http://127.0.0.1:1\n", encoding="utf-8")
    script = SCRIPT.with_name("run-phase3-evaluation.ps1")
    environment = {name: value for name, value in os.environ.items()
                   if not name.startswith("COPILOT_") and name != "FINGUARD_COPILOT_BASE_URL"}
    arguments = [PWSH, "-NoProfile", "-File", str(script), "-PlanOnly", "-EnvironmentFile", str(environment_file)]
    result = subprocess.run(arguments, text=True, encoding="utf-8", capture_output=True, env=environment)
    assert result.returncode == 0, result.stderr
    plan = json.loads(result.stdout)
    assert Path(plan["output"]).name.startswith("phase3-deepseek-")
    second = subprocess.run(arguments, text=True, encoding="utf-8", capture_output=True, env=environment)
    assert json.loads(second.stdout)["output"] != plan["output"]
    existing = tmp_path / "existing.json"
    existing.write_text("do-not-overwrite", encoding="utf-8")
    rejected = subprocess.run(arguments + ["-Output", str(existing)], text=True, encoding="utf-8",
                              capture_output=True, env=environment)
    assert rejected.returncode != 0
    assert existing.read_text(encoding="utf-8") == "do-not-overwrite"
