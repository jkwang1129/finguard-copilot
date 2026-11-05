package com.finguard.copilot.api.analysis;

import java.util.UUID;

import com.finguard.copilot.analysis.application.SubmitFeedbackService;
import com.finguard.copilot.analysis.application.SubmitFeedbackService.FeedbackResponse;
import com.finguard.copilot.api.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewFeedbackControllerTest {
    private final SubmitFeedbackService service = mock(SubmitFeedbackService.class);
    private final UUID id = UUID.randomUUID();

    @Test void acceptsOnlyBoundedVerdictAndBearerToken() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ReviewFeedbackController(service)).setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.submit(id, "AGREE", "token")).thenReturn(new FeedbackResponse(id, "AGREE"));
        mvc.perform(put("/api/copilot/review-analyses/" + id + "/feedback")
                .header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"verdict\":\"AGREE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verdict").value("AGREE"));
        mvc.perform(put("/api/copilot/review-analyses/" + id + "/feedback")
                .header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"verdict\":\"AGREE\",\"reviewerUsername\":\"bob\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/copilot/review-analyses/" + id + "/feedback")
                .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"AGREE\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, times(1)).submit(id, "AGREE", "token");
    }
}
