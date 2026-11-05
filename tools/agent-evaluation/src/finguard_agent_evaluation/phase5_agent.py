"""Offline conditional LangGraph reference and strict comparison of frozen synthetic cases.

No Core, MySQL, HTTP, credentials or real models. Expected labels never enter graph state.
Java's report supplies actual native HTTP/durable component evidence; this reference is not that evidence.
"""
import argparse
from copy import deepcopy
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import time
from typing import TypedDict
import unicodedata
from jsonschema import Draft202012Validator
from langgraph.graph import StateGraph, START, END

ROOT=Path(__file__).resolve().parents[4]
DATA=ROOT/'tools/agent-evaluation/data/phase5-agent-v1.jsonl'
DATA_SHA='01c883a77cbefad1af0c4d458df6003deaeddb8a0208762d385d383092a415ed'
SCHEMA=ROOT/'src/main/resources/schema/agent-tools-v1.schema.json'
SCHEMA_SHA='e0401e9bcd95bd5290d8184e89b2aa97ed164f6fc697f49b3af8780931c7d80f'
SCENARIOS={'direct_final','tool_final','format_repair','fact_mismatch','policy_conflict','output_injection',
    'second_tools','invalid_tool','too_many_tools','call_budget','redis_down','retrieval_retry','index_mismatch',
    'duplicate_delivery','provider_unknown','wait_cancel','wait_continue','wait_expired','facts_changed',
    'mcp_scope','mcp_slots','dual_worker','corrupt_checkpoint'}
if hashlib.sha256(SCHEMA.read_bytes()).hexdigest()!=SCHEMA_SHA:
    raise ValueError('Shared tool schema changed; explicitly version the evaluation contract')
TOOLS={tool['name']:tool['parameters'] for tool in json.loads(SCHEMA.read_text())['tools']}


def load_cases(path=DATA):
    path=Path(path)
    if hashlib.sha256(path.read_bytes()).hexdigest()!=DATA_SHA:raise ValueError('Frozen cases changed')
    cases=[json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
    if len(cases)!=26 or len({c['case_id'] for c in cases})!=26:raise ValueError('Invalid frozen case denominator')
    for case in cases:
        if set(case)!={'case_id','scenario','toolName','arguments','expected'} or case['scenario'] not in SCENARIOS:
            raise ValueError('Invalid case contract')
        if set(case['expected'])!={'status','failureCode','modelCalls','toolCalls','operationError'}:raise ValueError('Invalid expected outcome')
    return cases


def valid_tool(name,arguments):
    if name not in TOOLS or not isinstance(arguments,dict):return False
    if list(Draft202012Validator(TOOLS[name]).iter_errors(arguments)):return False
    for value in arguments.values():
        if isinstance(value,str):
            text=''.join(c for c in unicodedata.normalize('NFKC',value) if unicodedata.category(c)!='Cf')
            if not text.strip() or re.search(r'https?://|ftp://|file:|www\.|bearer\s|ignore previous instructions|忽略之前的指令',text,re.I):return False
    return True


class State(TypedDict):
    case:dict
    status:str
    failureCode:str|None
    operationError:str|None
    modelCalls:int
    toolCalls:int
    node:str
    retrievals:int


def failure(state,code,**updates):
    return {**updates,'status':'FAILED','failureCode':code,'node':'END'}


def retrieve(state):
    scenario=state['case']['scenario']
    if scenario=='corrupt_checkpoint':return failure(state,'AGENT_CHECKPOINT_INVALID')
    count=state['toolCalls']+1;attempt=state['retrievals']+1
    if scenario=='index_mismatch':return failure(state,'INVALID_REQUEST',toolCalls=count,retrievals=attempt)
    return {'toolCalls':count,'retrievals':attempt,'node':'RETRIEVE' if scenario=='retrieval_retry' and attempt<2 else 'PLAN'}


def plan(state):
    case=state['case'];scenario=case['scenario']
    if scenario=='redis_down':return failure(state,'MODEL_CAPACITY_UNAVAILABLE')
    if scenario=='call_budget':return failure(state,'MODEL_BUDGET_EXCEEDED')
    calls=state['modelCalls']+1
    if scenario=='provider_unknown':return failure(state,'MODEL_UNAVAILABLE',modelCalls=calls)
    if scenario in {'fact_mismatch','policy_conflict','output_injection'}:return failure(state,'FACT_VALIDATION_FAILED',modelCalls=calls)
    if scenario=='format_repair':return {'modelCalls':calls,'node':'GENERATE'}
    if scenario in {'direct_final','retrieval_retry'}:return {'modelCalls':calls,'status':'SUCCEEDED','node':'END'}
    if not valid_tool(case['toolName'],case['arguments']):return failure(state,'MODEL_OUTPUT_INVALID',modelCalls=calls)
    if scenario=='too_many_tools':return failure(state,'MODEL_BUDGET_EXCEEDED',modelCalls=calls)
    if scenario.startswith('wait_') or scenario in {'facts_changed','mcp_scope','mcp_slots'}:
        return {'modelCalls':calls,'status':'WAITING_FOR_HUMAN','node':'HUMAN'}
    return {'modelCalls':calls,'node':'TOOLS'}


def human(state):
    scenario=state['case']['scenario']
    if scenario=='wait_cancel':return failure(state,'ANALYSIS_CANCELLED')
    if scenario=='wait_expired':return failure(state,'WAIT_EXPIRED')
    if scenario=='facts_changed':return failure(state,'CONTEXT_STALE')
    if scenario=='mcp_scope':return {'operationError':'FORBIDDEN','node':'END'}
    if scenario=='mcp_slots':return {'toolCalls':4,'operationError':'MODEL_BUDGET_EXCEEDED','node':'END'}
    return {'node':'TOOLS'}


def tool(state):
    if state['toolCalls']>=4:return failure(state,'MODEL_BUDGET_EXCEEDED')
    return {'toolCalls':state['toolCalls']+1,'node':'GENERATE'}


def generate(state):
    calls=state['modelCalls']+1
    if calls>2:return failure(state,'MODEL_BUDGET_EXCEEDED')
    if state['case']['scenario']=='second_tools':return failure(state,'MODEL_OUTPUT_INVALID',modelCalls=calls)
    return {'modelCalls':calls,'status':'SUCCEEDED','node':'END'}


builder=StateGraph(State)
routes={key:key for key in ['RETRIEVE','PLAN','TOOLS','GENERATE','HUMAN']};routes['END']=END
for name,action in [('RETRIEVE',retrieve),('PLAN',plan),('TOOLS',tool),('GENERATE',generate),('HUMAN',human)]:
    builder.add_node(name,action);builder.add_conditional_edges(name,lambda state:state['node'],routes)
builder.add_edge(START,'RETRIEVE')
GRAPH=builder.compile()


def run_case(case):
    started=time.monotonic()
    payload={key:deepcopy(value) for key,value in case.items() if key!='expected'}
    if payload['scenario'] not in SCENARIOS:raise ValueError('Unknown scenario')
    result=GRAPH.invoke({'case':payload,'status':'QUEUED','failureCode':None,'operationError':None,
        'modelCalls':0,'toolCalls':0,'node':'RETRIEVE','retrievals':0})
    return {'case_id':case['case_id'],'actual':{key:result[key] for key in ['status','failureCode','modelCalls','toolCalls','operationError']},
        'latencyMs':(time.monotonic()-started)*1000,'lane':'offline-langgraph-reference'}


def compare_report(cases,rows):
    indexed={row['case_id']:row for row in rows}
    if len(rows)!=len(cases) or len(indexed)!=len(rows) or set(indexed)!={c['case_id'] for c in cases}:
        raise ValueError('Every frozen case, including failures, must appear exactly once')
    comparisons=[{'case_id':case['case_id'],'matched':indexed[case['case_id']]['actual']==case['expected']} for case in cases]
    bounds=all(type(row['actual']['modelCalls']) is int and 0<=row['actual']['modelCalls']<=2
        and type(row['actual']['toolCalls']) is int and 0<=row['actual']['toolCalls']<=4 for row in rows)
    matched=sum(row['matched'] for row in comparisons)
    # Quality fields are visible against the full denominator; failures do not silently disappear.
    quality={field:{'verified':sum(row.get(field) is True for row in rows),'unavailable':sum(row.get(field) is not True for row in rows),'total':len(cases)} for field in ['factsValidated','citationsValidated']}
    positive=[case['case_id'] for case in cases if case['expected']['status']=='SUCCEEDED']
    quality['recommendation']={'correct':sum(indexed[key].get('recommendation')=='INSUFFICIENT_EVIDENCE' for key in positive),
        'incorrect':sum(indexed[key].get('recommendation') not in {None,'INSUFFICIENT_EVIDENCE'} for key in positive),
        'unavailable':sum(row.get('recommendation') is None for row in rows),'total':len(cases),
        'scope':'conservative synthetic candidate; process lane separately checks directional project policy'}
    selected=[case for case in cases if case['scenario'] in {'tool_final','invalid_tool','second_tools','too_many_tools','mcp_scope','mcp_slots'}]
    quality['toolProtocol']={'matched':sum(indexed[c['case_id']]['actual']==c['expected'] for c in selected),
        'evaluated':len(selected),'unavailable':len(cases)-len(selected),'total':len(cases)}
    delays=sorted(row['latencyMs'] for row in rows if isinstance(row.get('latencyMs'),(int,float)))
    known_costs=[Decimal(row['estimatedCny']) for row in rows if row.get('estimatedCny') is not None]
    recovery=[case for case in cases if case['scenario'] in {'duplicate_delivery','dual_worker','corrupt_checkpoint','wait_cancel','wait_continue','wait_expired','facts_changed'}]
    quality['recoveryContract']={'matched':sum(indexed[c['case_id']]['actual']==c['expected'] for c in recovery),
        'evaluated':len(recovery),'unavailable':len(cases)-len(recovery),'total':len(cases)}
    measurements={'modelClaims':sum(row['actual']['modelCalls'] for row in rows),'toolReceipts':sum(row['actual']['toolCalls'] for row in rows),
        'providerAttempts':sum(row.get('providerAttempts',0) for row in rows),
        'knownEstimatedCny':format(sum(known_costs,Decimal(0)),'f') if known_costs else None,
        'unknownOrPendingCostCases':sum('estimatedCny' in row and row['estimatedCny'] is None and row['actual']['modelCalls']>0 for row in rows),
        'notRecordedCostCases':sum('estimatedCny' in row and row['estimatedCny'] is None and row['actual']['modelCalls']==0 for row in rows),
        'accountingEvidenceUnavailable':sum('estimatedCny' not in row for row in rows),
        'latencyMs':{'total':len(cases),'available':len(delays),'p50':delays[(len(delays)-1)//2] if delays else None,'p95':delays[min(len(delays)-1,int(len(delays)*.95))] if delays else None}}
    return {'schemaVersion':1,'datasetSha256':DATA_SHA,'toolSchemaSha256':SCHEMA_SHA,'total':len(cases),
        'matched':matched,'safe':bounds and matched==len(cases),'comparisons':comparisons,'quality':quality,'measurements':measurements,
        'realProviderQualified':False,'costScope':'fixture estimates only; unknown remains null','rows':rows}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--java-report',type=Path);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();cases=load_cases();reference=[run_case(case) for case in cases]
    result={'reference':compare_report(cases,reference)}
    if args.java_report:
        java=json.loads(args.java_report.read_text(encoding='utf-8'))
        if java['datasetSha256']!=DATA_SHA:raise ValueError('Java report used another dataset')
        result['java']=compare_report(cases,java['rows'])
        result['crossRuntimeEqual']=all(a['actual']==b['actual'] for a,b in zip(sorted(reference,key=lambda r:r['case_id']),sorted(java['rows'],key=lambda r:r['case_id'])))
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'total':len(cases),'referenceSafe':result['reference']['safe'],'javaSafe':result.get('java',{}).get('safe'),'crossRuntimeEqual':result.get('crossRuntimeEqual')}))
    if not result['reference']['safe'] or args.java_report and (not result['java']['safe'] or not result['crossRuntimeEqual']):raise SystemExit(1)


if __name__=='__main__':main()
