from pathlib import Path

import pandas as pd

from finguard_agent_evaluation.models import EvaluationCase


def load_cases(path: Path) -> list[EvaluationCase]:
    records = pd.read_json(path, lines=True).to_dict(orient="records")
    return [EvaluationCase.model_validate(record) for record in records]
