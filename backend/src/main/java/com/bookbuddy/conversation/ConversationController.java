package com.bookbuddy.conversation;

import com.bookbuddy.auth.CurrentUser;
import com.bookbuddy.conversation.ConversationDtos.ConversationResponse;
import com.bookbuddy.conversation.ConversationDtos.CreateConversationRequest;
import com.bookbuddy.conversation.ConversationDtos.CursorPage;
import com.bookbuddy.conversation.ConversationDtos.MessageResponse;
import com.bookbuddy.conversation.ConversationDtos.SendMessageRequest;
import com.bookbuddy.conversation.ConversationDtos.SendMessageResponse;
import com.bookbuddy.conversation.ConversationDtos.UpdateConversationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversations;
    private final ChatStreamer streamer;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(@Valid @RequestBody(required = false) CreateConversationRequest request) {
        return conversations.create(
                CurrentUser.userId(), request == null ? new CreateConversationRequest(null, null, null) : request);
    }

    @GetMapping
    public CursorPage<ConversationResponse> list(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return conversations.list(CurrentUser.userId(), limit, cursor);
    }

    @GetMapping("/{id}")
    public ConversationResponse get(@PathVariable String id) {
        return conversations.get(CurrentUser.userId(), id);
    }

    @PatchMapping("/{id}")
    public ConversationResponse patch(@PathVariable String id, @Valid @RequestBody UpdateConversationRequest request) {
        return conversations.update(CurrentUser.userId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        conversations.delete(CurrentUser.userId(), id);
    }

    /** The latest {@code limit} messages in reading order; pass {@code nextCursor} back as {@code cursor} for older ones. */
    @GetMapping("/{id}/messages")
    public CursorPage<MessageResponse> messages(
            @PathVariable String id,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return conversations.listMessages(CurrentUser.userId(), id, limit, cursor);
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public SendMessageResponse send(@PathVariable String id, @Valid @RequestBody SendMessageRequest request) {
        return conversations.sendMessage(CurrentUser.userId(), id, request);
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String id, @Valid @RequestBody SendMessageRequest request) {
        // consent, ownership and persisting the user message happen here, so failures are ordinary HTTP errors
        ConversationService.Turn turn = conversations.startSend(CurrentUser.userId(), id, request);
        return sse(streamer.stream(turn));
    }

    @PostMapping(value = "/{id}/messages/{mid}/regenerate", produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageResponse regenerate(@PathVariable String id, @PathVariable String mid) {
        return conversations.regenerate(CurrentUser.userId(), id, mid);
    }

    /** Same endpoint, streamed, when the client sends {@code Accept: text/event-stream}. */
    @PostMapping(value = "/{id}/messages/{mid}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> regenerateStream(@PathVariable String id, @PathVariable String mid) {
        ConversationService.Turn turn = conversations.startRegenerate(CurrentUser.userId(), id, mid);
        return sse(streamer.stream(turn));
    }

    private static ResponseEntity<SseEmitter> sse(SseEmitter emitter) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}