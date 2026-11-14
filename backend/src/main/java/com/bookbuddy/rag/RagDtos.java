package com.bookbuddy.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class RagDtos {

    private RagDtos() {}

    public record AskRequest(
            @NotBlank @Size(max = 2000) String question, @Size(max = 50) List<String> bookIds) {}

    /** Citation {@code n} in the list corresponds to the marker {@code [n]} (1-based) used in the answer text. */
    public record Citation(String chunkId, String bookId, String bookTitle, Integer pageStart, Integer pageEnd) {}

    public record AskResponse(String answer, List<Citation> citations, int usedChunks) {}
}
