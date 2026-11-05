SELECT 'component_calls',COUNT(*) FROM fg_sq_20261104_221513_7669588.model_call_reservation;
SELECT 'component_budgets',status,COUNT(*),SUM(settled_cny),SUM(CASE WHEN status IN ('RESERVED','UNKNOWN_HELD') THEN reserved_cny ELSE 0 END) FROM fg_sq_20261104_221513_7669588.model_budget_reservation GROUP BY status;
SELECT 'component_results',COUNT(*) FROM fg_sq_20261104_221513_7669588.analysis_result;
SELECT 'process_calls',COUNT(*) FROM fg_copilot_e2e_20261104222752.model_call_reservation;
SELECT 'process_budgets',status,COUNT(*),SUM(settled_cny),SUM(CASE WHEN status IN ('RESERVED','UNKNOWN_HELD') THEN reserved_cny ELSE 0 END) FROM fg_copilot_e2e_20261104222752.model_budget_reservation GROUP BY status;
