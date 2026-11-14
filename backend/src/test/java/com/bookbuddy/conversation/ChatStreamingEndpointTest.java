package com.bookbuddy.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bookbuddy.ai.CancelToken;
import com.bookbuddy.auth.UserPrincipal;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.common.GlobalExceptionHandler;
import com.bookbuddy.conversation.ChatEvents.Done;
import com.bookbuddy.conversation.ChatEvents.Meta;
import com.bookbuddy.conversation.ChatEvents.Safety;
import com.bookbuddy.conversation.ConversationService.Turn;
import com.bookbuddy.conversation.MessageDocument.Status;
import com.bookbuddy.safety.CrisisResponseService.Resource;
import com.bookbuddy.safety.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifies the SSE wire format of the streaming endpoints (event names and JSON payloads). */
class ChatStreamingEndpointTest {

    private final ConversationService service = mock(ConversationService.class);
    private MockMvc mvc;
    private final Turn turn = new Turn(null, null, null, null, "en");

    @BeforeEach
    void setUp() {
        UserPrincipal principal = new UserPrincipal("u1", "a@b.c", "");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        ChatStreamer streamer = new ChatStreamer(service, Runnable::run);
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service, streamer))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private String streamBody(String path) throws Exception {
        MvcResult started = mvc.perform(post(path)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    @Test
    void streamEmitsMetaTokensSafetyDoneInOrderAsSse() throws Exception {
        when(service.startSend(eq("u1"), eq("c1"), any())).thenReturn(turn);
        doAnswer(inv -> {
            ChatEvents.Sink sink = inv.getArgument(1);
            sink.meta(new Meta("m2", "m1", "c1"));
            sink.safety(new Safety(RiskLevel.ELEVATED, List.of(new Resource("Tele-MANAS", "14416", false))));
            sink.token("Hel");
            sink.token("lo");
            sink.citations(List.of(new MessageCitation(1, "ch", "b", "Book", 3, 3, "passage text")));
            sink.done(new Done("m2", Status.COMPLETE, "Hello", null, "test"));
            return null;
        }).when(service).runTurn(any(Turn.class), any(ChatEvents.Sink.class), any(CancelToken.class));

        String body = streamBody("/api/v1/conversations/c1/messages/stream");

        int meta = body.indexOf("event:meta");
        int safety = body.indexOf("event:safety");
        int token = body.indexOf("event:token");
        int cites = body.indexOf("event:citations");
        int done = body.indexOf("event:done");
        assertTrue(meta >= 0 && meta < safety && safety < token && token < cites && cites < done, body);
        assertTrue(body.contains("data:{\"messageId\":\"m2\",\"userMessageId\":\"m1\",\"conversationId\":\"c1\"}"), body);
        assertTrue(body.contains("data:{\"text\":\"Hel\"}"), body);
        assertTrue(body.contains("\"phone\":\"14416\""), body);
        assertTrue(body.contains("\"excerpt\":\"passage text\""), body);
        assertTrue(body.contains("\"status\":\"COMPLETE\""), body);
    }

    @Test
    void failuresAreDeliveredAsAnErrorEvent() throws Exception {
        when(service.startSend(eq("u1"), eq("c1"), any())).thenReturn(turn);
        doAnswer(inv -> {
            ChatEvents.Sink sink = inv.getArgument(1);
            sink.error(new ChatEvents.Error("LLM_UNAVAILABLE", "try again", true, "m2"));
            return null;
        }).when(service).runTurn(any(Turn.class), any(ChatEvents.Sink.class), any(CancelToken.class));

        String body = streamBody("/api/v1/conversations/c1/messages/stream");
        assertTrue(body.contains("event:error"), body);
        assertTrue(body.contains("\"code\":\"LLM_UNAVAILABLE\""), body);
        assertTrue(body.contains("\"retryable\":true"), body);
    }

    @Test
    void regenerateStreamsWhenEventStreamIsAccepted() throws Exception {
        when(service.startRegenerate("u1", "c1", "m2")).thenReturn(turn);
        doAnswer(inv -> {
            ChatEvents.Sink sink = inv.getArgument(1);
            sink.token("again");
            return null;
        }).when(service).runTurn(any(Turn.class), any(ChatEvents.Sink.class), any(CancelToken.class));

        MvcResult started = mvc.perform(post("/api/v1/conversations/c1/messages/m2/regenerate")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("data:{\"text\":\"again\"}"), body);
    }

    @Test
    void preflightFailuresAreOrdinaryHttpErrors() throws Exception {
        when(service.startSend(eq("u1"), eq("c1"), any()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "CONSENT_REQUIRED", "no"));

        mvc.perform(post("/api/v1/conversations/c1/messages/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankContentIsRejectedByValidation() throws Exception {
        assertEquals(400, mvc.perform(post("/api/v1/conversations/c1/messages/stream")
                                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content\":\"   \"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
    }
}