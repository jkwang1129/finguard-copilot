START TRANSACTION;
INSERT INTO accounts(account_no,account_name,account_type)
 VALUES('P6-SYNTHETIC','Synthetic acceptance account','BANK');
SET @account=LAST_INSERT_ID();
SET @admin=(SELECT id FROM users WHERE username='e2e-admin');
INSERT INTO import_jobs(original_file_name,file_hash,file_size_bytes,status,total_rows,success_rows,created_by)
 VALUES('synthetic.csv',REPEAT('a',64),100,'SUCCESS',1,1,@admin);
SET @import=LAST_INSERT_ID();
INSERT INTO transactions(account_id,import_job_id,external_transaction_no,direction,amount,transaction_time,source)
 VALUES(@account,@import,'P6-SYNTHETIC-TX','EXPENSE',120000.10,'2026-10-19 10:00:00','CSV_IMPORT');
SET @transaction=LAST_INSERT_ID();
INSERT INTO reconciliation_jobs(import_job_id,status,total_count,unmatched_count,created_by,started_at,finished_at)
 VALUES(@import,'COMPLETED',1,1,@admin,NOW(),NOW());
SET @job=LAST_INSERT_ID();
INSERT INTO reconciliation_results(reconciliation_job_id,csv_transaction_id,result_type,match_method,reason_code)
 VALUES(@job,@transaction,'UNMATCHED','NONE','NO_CANDIDATE');
INSERT INTO risk_hits(reconciliation_result_id,rule_code,reason_code,observed_amount,threshold_amount,reason_summary)
 VALUES(LAST_INSERT_ID(),'LARGE_AMOUNT','AMOUNT_AT_OR_ABOVE_THRESHOLD',120000.10,100000.00,'Synthetic threshold reached');
INSERT INTO review_tasks(source_type,risk_hit_id) VALUES('RISK_HIT',LAST_INSERT_ID());
SELECT LAST_INSERT_ID();
COMMIT;
