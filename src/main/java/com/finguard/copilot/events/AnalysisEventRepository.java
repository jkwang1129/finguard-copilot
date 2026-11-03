package com.finguard.copilot.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.JdbcAnalysisRunRepository;
import com.finguard.copilot.observability.SafeSpanData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.*;

@Repository
public class AnalysisEventRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AnalysisWakeups wakeups;
    public AnalysisEventRepository(JdbcTemplate jdbc){this(jdbc,new AnalysisWakeups());}
    @Autowired public AnalysisEventRepository(JdbcTemplate jdbc,AnalysisWakeups wakeups) {
        this.jdbc=jdbc;this.wakeups=wakeups;
        transactions=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public record Event(UUID analysisId,String sequence,String type,AnalysisStatus status,String failureCode,
                        boolean resultAvailable,Instant occurredAt) {}
    public void append(UUID id,AnalysisStatus status,String failure,Instant at,String type) {
        if(id==null || status==null || at==null || !Set.of("SNAPSHOT","STATE_CHANGED").contains(type)) throw new IllegalArgumentException("invalid event");
        transactions.executeWithoutResult(tx->{
            byte[] key=JdbcAnalysisRunRepository.uuidBytes(id);
            // Same lock order as status updates and model admission: parent first.
            jdbc.queryForObject("SELECT id FROM analysis_run WHERE id=? FOR UPDATE",byte[].class,key);
            jdbc.update("INSERT IGNORE INTO analysis_event_cursor(analysis_id,last_sequence) VALUES(?,0)",key);
            jdbc.update("UPDATE analysis_event_cursor SET last_sequence=last_sequence+1 WHERE analysis_id=?",key);
            long sequence=jdbc.queryForObject("SELECT last_sequence FROM analysis_event_cursor WHERE analysis_id=?",Long.class,key);
            jdbc.update("INSERT INTO analysis_event(analysis_id,sequence_number,event_type,analysis_status,failure_code,occurred_at) VALUES(?,?,?,?,?,?)",
                    key,sequence,type,status.name(),status==AnalysisStatus.FAILED?SafeSpanData.errorCode(failure):null,Timestamp.from(at));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){wakeups.committed(id);}
            });
        });
    }
    public long head(UUID id) {
        return jdbc.query("SELECT last_sequence FROM analysis_event_cursor WHERE analysis_id=?",rs->rs.next()?rs.getLong(1):0L,JdbcAnalysisRunRepository.uuidBytes(id));
    }
    public List<Event> after(UUID id,long sequence,int limit) {
        if(id==null || sequence<0 || limit<1 || limit>64) throw new IllegalArgumentException("invalid event cursor");
        return jdbc.query("SELECT sequence_number,event_type,analysis_status,failure_code,occurred_at FROM analysis_event WHERE analysis_id=? AND sequence_number>? ORDER BY sequence_number LIMIT ?",
                (rs,row)->{var state=AnalysisStatus.valueOf(rs.getString("analysis_status"));
                    return new Event(id,Long.toString(rs.getLong("sequence_number")),rs.getString("event_type"),state,
                            state==AnalysisStatus.FAILED?SafeSpanData.errorCode(rs.getString("failure_code")):null,state==AnalysisStatus.SUCCEEDED,rs.getTimestamp("occurred_at").toInstant());},
                JdbcAnalysisRunRepository.uuidBytes(id),sequence,limit);
    }
}
