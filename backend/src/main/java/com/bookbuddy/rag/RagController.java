package com.bookbuddy.rag;

import com.bookbuddy.auth.CurrentUser;
import com.bookbuddy.rag.RagDtos.AskRequest;
import com.bookbuddy.rag.RagDtos.AskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return ragService.ask(CurrentUser.userId(), request);
    }
}
