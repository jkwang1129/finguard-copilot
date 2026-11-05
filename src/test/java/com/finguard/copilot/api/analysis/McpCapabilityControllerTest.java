package com.finguard.copilot.api.analysis;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import com.finguard.copilot.agent.mcp.*;
import com.finguard.copilot.api.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class McpCapabilityControllerTest {
    @Test void rejectsNonlocalOriginsExtraFieldsAndMissingIdentityBeforeIssuance() throws Exception {
        var service=mock(McpCapabilityService.class);var adapter=mock(McpToolAdapter.class);
        var mvc=MockMvcBuilders.standaloneSetup(new McpCapabilityController(service,adapter)).setControllerAdvice(new GlobalExceptionHandler()).build();
        String url="/api/copilot/review-analyses/"+UUID.randomUUID()+"/mcp-capabilities";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"toolScope\":[\"get_task_state\"]}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(url).header("Authorization","Bearer jwt").header("Origin","https://untrusted.example")
            .contentType(MediaType.APPLICATION_JSON).content("{\"toolScope\":[\"get_task_state\"]}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(url).with(request->{request.setRemoteAddr("10.0.0.2");return request;}).header("Authorization","Bearer jwt")
            .contentType(MediaType.APPLICATION_JSON).content("{\"toolScope\":[\"get_task_state\"]}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization","Bearer jwt").contentType(MediaType.APPLICATION_JSON)
            .content("{\"toolScope\":[\"get_task_state\"],\"reviewer\":\"forged\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service,adapter);
    }
}
