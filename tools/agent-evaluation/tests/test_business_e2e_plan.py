import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest


PWSH = shutil.which("pwsh")
pytestmark = pytest.mark.skipif(PWSH is None, reason="Windows PowerShell launcher boundary")
SCRIPT = Path(__file__).resolve().parents[3] / "scripts/run-business-e2e.ps1"


def environment_file(tmp_path, provider="deepseek", model="deepseek-flash", base="https://api.deepseek.com"):
    path = tmp_path / ".env.local"
    path.write_text(
        f"COPILOT_MODEL_PROVIDER={provider}\nCOPILOT_MODEL_ID={model}\n"
        f"COPILOT_MODEL_BASE_URL={base}\nCOPILOT_MODEL_API_KEY=test-only-credential\n"
        "COPILOT_PROMPT_VERSION=review-analysis-v2\n", encoding="utf-8")
    return path


def run_plan(path, *arguments):
    environment = {name: value for name, value in os.environ.items()
                   if not name.startswith("COPILOT_") and name != "SPRING_PROFILES_ACTIVE"}
    return subprocess.run(
        [PWSH, "-NoProfile", "-File", str(SCRIPT), "-PlanOnly", "-EnvironmentFile", str(path), *arguments],
        text=True, encoding="utf-8", capture_output=True, env=environment, timeout=20,
    )


def test_business_plan_uses_deepseek_profile_endpoint_and_one_call_budget(tmp_path):
    result = run_plan(environment_file(tmp_path))
    assert result.returncode == 0, result.stderr
    plan = json.loads(result.stdout)
    assert plan["provider"] == "deepseek"
    assert plan["model"] == "deepseek-flash"
    assert plan["profile"] == "real-deepseek"
    assert plan["baseUrl"] == "https://api.deepseek.com"
    assert plan["chatCompletionsPath"] == "/chat/completions"
    assert plan["maxModelCalls"] == 1
    assert plan["prompt"] == "review-analysis-v2"
    assert plan["currency"] == "CNY"
    assert plan["estimatedUpperBoundCny"] == 0.08
    assert plan["priceTableVersion"] == "smoke-deepseek-flash-20261102-peak-cny"
    assert "test-only-credential" not in result.stdout + result.stderr


def test_business_plan_does_not_route_deepseek_credentials_to_qwen(tmp_path):
    result = run_plan(environment_file(tmp_path), "-Provider", "qwen")
    assert result.returncode != 0
    assert "credential provider" in result.stderr


def test_business_plan_rejects_non_allowlisted_base_url_before_startup(tmp_path):
    result = run_plan(environment_file(tmp_path, base="https://untrusted.invalid"))
    assert result.returncode != 0
    assert "base URL" in result.stderr


def test_business_plan_can_still_explicitly_reproduce_qwen_configuration(tmp_path):
    result = run_plan(environment_file(tmp_path, "qwen", "qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
    assert result.returncode == 0, result.stderr
    plan = json.loads(result.stdout)
    assert plan["profile"] == "real-qwen"
    assert plan["baseUrl"] == "https://dashscope.aliyuncs.com/compatible-mode/v1"


def test_business_plan_rejects_underfunded_smoke_before_side_effects(tmp_path):
    result = run_plan(environment_file(tmp_path), "-SmokeBudgetCny", "0.079")
    assert result.returncode != 0
    assert "smoke budget" in result.stderr.lower()
    assert "test-only-credential" not in result.stdout + result.stderr


def test_business_plan_requires_complete_explicit_price_override(tmp_path):
    result = run_plan(environment_file(tmp_path), "-InputPricePerMillionCny", "1")
    assert result.returncode != 0
    assert "price override" in result.stderr.lower()


@pytest.mark.parametrize("scenario", ["-ExpectBudgetRejection", "-ExpectRedisFailure"])
def test_governance_rejection_plan_requires_no_model_calls(tmp_path, scenario):
    result = run_plan(environment_file(tmp_path), "-LocalModelStub", scenario)
    assert result.returncode == 0, result.stderr
    plan = json.loads(result.stdout)
    assert plan["fixture"] is True
    assert plan["expectedCalls"] == 0
