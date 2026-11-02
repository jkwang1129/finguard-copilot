package com.finguard.copilot.governance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;

class CallAdmissionTest {
    final UUID id=UUID.randomUUID();
    final BudgetReservations budgets=mock(BudgetReservations.class);
    final RedisProviderPermits permits=mock(RedisProviderPermits.class);
    final RedisProviderPermits.Lease lease=new RedisProviderPermits.Lease("owner");
    CallAdmission admission;
    @BeforeEach void init() {
        when(budgets.frozen(id)).thenReturn(new BudgetReservations.FrozenCall("hash",CanonicalJson.tree(Map.of("reviewerKey","a".repeat(64))),null,null,"deepseek"));
        when(permits.acquire(anyString(),any())).thenReturn(lease);
        when(permits.valid(eq(lease),any())).thenReturn(true);
        when(budgets.acquire(id)).thenReturn(true);
        admission=new CallAdmission(()->budgets,permits,Duration.ofSeconds(20));
    }
    @Test void redisFailurePreventsBudgetAndCallClaim() {
        when(permits.acquire(anyString(),any())).thenThrow(RedisProviderPermits.capacity());
        assertThatThrownBy(()->admission.open(id)).hasMessageContaining("capacity");
        verify(budgets,never()).acquire(id);
    }
    @Test void expiredPermissionAfterClaimReleasesOnlyProvenNotSentMoney() {
        when(permits.valid(eq(lease),any())).thenReturn(false);
        assertThatThrownBy(()->admission.open(id)).hasMessageContaining("capacity");
        verify(budgets).releaseNotSent(id);verify(permits).release(lease);
    }
    @Test void uncertainDatabaseCommitDoesNotReleaseAnotherWorkersMoney() {
        when(budgets.acquire(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test commit uncertain"));
        assertThatThrownBy(()->admission.open(id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        verify(budgets,never()).releaseNotSent(id);verify(permits).release(lease);
    }
    @Test void duplicateAdmissionCannotReleaseTheActiveOwnersPermit() {
        var second=new RedisProviderPermits.Lease("other-owner");
        when(permits.acquire(anyString(),any())).thenReturn(lease,second);
        when(budgets.acquire(id)).thenReturn(true,false);
        admission.open(id);
        assertThatThrownBy(()->admission.open(id)).isInstanceOf(ExistingModelCallException.class);
        verify(permits).release(second);verify(permits,never()).release(lease);
        admission.close(id);admission.close(id);
        verify(permits,times(1)).release(lease);verify(budgets,never()).releaseNotSent(id);
    }
}
