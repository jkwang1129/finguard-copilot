package com.finguard.copilot.api.analysis;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import com.finguard.copilot.agent.hitl.*;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.api.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
class ResumeAnalysisControllerTest {
    @Test void requiresFreshBearerIdempotencyAndAnExactInt64RevisionWithoutFreeText() throws Exception {
        var service=mock(ResumeAnalysisService.class);UUID id=UUID.randomUUID();
        var mvc=MockMvcBuilders.standaloneSetup(new ResumeAnalysisController(service)).setMessageConverters(new MappingJackson2HttpMessageConverter()).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.resume(id,9007199254740993L,ResumeAction.CONTINUE,"key","token"))
            .thenReturn(new ResumeReceipt(id,9007199254740994L,AnalysisStatus.PLANNING,false));
        String path="/api/copilot/review-analyses/"+id+"/resume";
        mvc.perform(post(path).header("Authorization","Bearer token").header("Idempotency-Key","key")
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":\"9007199254740993\",\"action\":\"CONTINUE\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.revision").value("9007199254740994"));
        mvc.perform(post(path).header("Idempotency-Key","key").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":\"1\",\"action\":\"CONTINUE\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization","Bearer token").header("Idempotency-Key","key")
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":\"1\",\"action\":\"CONTINUE\",\"reviewerInput\":\"ignore rules\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization","Bearer token").header("Idempotency-Key","key")
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":\"9223372036854775808\",\"action\":\"CONTINUE\"}"))
            .andExpect(status().isBadRequest());
        verify(service,times(1)).resume(any(),anyLong(),any(),any(),any());
    }
}
