SELECT 'component_calls',COUNT(*) FROM fg_sq_20261104_175023.model_call_reservation;
SELECT 'component_budgets',status,COUNT(*),SUM(settled_cny),SUM(CASE WHEN status IN ('RESERVED','UNKNOWN_HELD') THEN reserved_cny ELSE 0 END) FROM fg_sq_20261104_175023.model_budget_reservation GROUP BY status;
SELECT 'component_runs',status,COALESCE(failure_code,'NONE'),COUNT(*) FROM fg_sq_20261104_175023.analysis_run GROUP BY status,failure_code;
SELECT 'component_results',COUNT(*) FROM fg_sq_20261104_175023.analysis_result;
SELECT 'process_calls',COUNT(*) FROM fg_copilot_e2e_20261104183249.model_call_reservation;
SELECT 'process_budgets',COUNT(*) FROM fg_copilot_e2e_20261104183249.model_budget_reservation;
SELECT 'process_analyses',COUNT(*) FROM fg_copilot_e2e_20261104183249.analysis_run;
SELECT 'process_core_review_tasks',COUNT(*) FROM fg_core_e2e_20261104183249.review_tasks;
