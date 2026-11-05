package com.finguard.copilot.coreclient.http;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.copilot.coreclient.port.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpCoreTaskStateTest {
    final RestClient.Builder builder=RestClient.builder().baseUrl("https://core.invalid");
    final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    final CoreClient client=new HttpCoreClient(builder.build(),
        new CoreHttpConfiguration().strictResponseObjectMapper(new ObjectMapper().findAndRegisterModules()));
    final String token="header.payload.signature";
    @AfterEach void verify(){server.verify();}
    @Test void projectsOnlyTaskFieldsFromFixedAuthenticatedGet() {
        server.expect(requestTo("https://core.invalid/api/review-tasks/42"))
            .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization","Bearer "+token))
            .andRespond(withSuccess("""
                {"id":42,"version":0,"status":"PENDING","ruleCode":null,"reasonCode":"REVIEW",
                 "decisionNote":"PRIVATE-NOTE","reviewedBy":999,"updatedAt":"irrelevant"}
                """,MediaType.APPLICATION_JSON));
        var state=client.reviewTaskState(42,token);
        assertThat(state).isEqualTo(new ReviewTaskState(42,0,"PENDING",null,"REVIEW"));
        assertThat(state.toString()).doesNotContain("PRIVATE-NOTE","999");
    }
    @ParameterizedTest @ValueSource(ints={401,403,404,500})
    void mapsFailuresWithoutLeakingResponse(int status) {
        server.expect(requestTo("https://core.invalid/api/review-tasks/42"))
            .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("PRIVATE"));
        assertThatThrownBy(()->client.reviewTaskState(42,token))
            .isInstanceOf(CoreClientException.class).hasMessageNotContaining("PRIVATE");
    }
    @Test void missingVersionCannotDefaultToZero() {
        server.expect(requestTo("https://core.invalid/api/review-tasks/42"))
            .andRespond(withSuccess("{\"id\":42,\"status\":\"PENDING\",\"reasonCode\":\"REVIEW\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.reviewTaskState(42,token)).isInstanceOf(CoreClientException.class);
    }
}
