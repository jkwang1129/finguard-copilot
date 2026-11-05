import hashlib
import json
from pathlib import Path
import pytest
from finguard_agent_evaluation.phase5_agent import load_cases, run_case, compare_report

DATA=Path(__file__).parents[1]/'data/phase5-agent-v1.jsonl'
SHA='01c883a77cbefad1af0c4d458df6003deaeddb8a0208762d385d383092a415ed'

def test_frozen_cases_use_shared_schema_and_include_failures_in_denominator():
    cases=load_cases(DATA)
    assert len(cases)==26 and hashlib.sha256(DATA.read_bytes()).hexdigest()==SHA
    for case in cases:
        assert run_case(case)['actual']==case['expected']
    rows=[run_case(c) for c in cases]
    report=compare_report(cases,rows)
    assert report['total']==26 and report['matched']==26
    assert report['realProviderQualified'] is False
    assert report['measurements']['knownEstimatedCny'] is None  # reference has no accounting evidence

def test_oracle_labels_are_not_available_to_graph_execution():
    case=load_cases(DATA)[0]
    original=run_case(case)['actual']
    case['expected']={'status':'FAILED','modelCalls':0}
    assert run_case(case)['actual']==original

def test_missing_duplicate_or_malformed_rows_cannot_improve_report():
    cases=load_cases(DATA);rows=[run_case(c) for c in cases]
    with pytest.raises(ValueError):compare_report(cases,rows[:-1])
    with pytest.raises(ValueError):compare_report(cases,[*rows[:-1],rows[0]])
    rows[0]['actual']['modelCalls']=3
    report=compare_report(cases,rows)
    assert report['total']==26 and report['matched']==25 and report['safe'] is False
