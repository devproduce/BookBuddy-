package com.bookbuddy.memory;

import com.bookbuddy.embedding.EmbeddingProvider;
import com.bookbuddy.embedding.VectorMath;
import com.bookbuddy.memory.MemoryControlDetector.Detection;
import com.bookbuddy.memory.MemoryControlDetector.Kind;
import com.bookbuddy.memory.MemoryDocument.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Handles in-chat forget / correct requests by deleting or archiving matching memories (embedding + lexical).
 */
@Service
public class MemoryControlService {

    public record ControlResult(boolean handled, int affected, String acknowledgment) {
        public static final ControlResult NONE = new ControlResult(false, 0, "");
    }

    private static final double MIN_SIM = 0.45;

    private final MemoryRepository memories;
    private final EmbeddingProvider embeddings;
    private final MemoryControlDetector detector;

    public MemoryControlService(
            MemoryRepository memories, EmbeddingProvider embeddings, MemoryControlDetector detector) {
        this.memories = memories;
        this.embeddings = embeddings;
        this.detector = detector;
    }

    public ControlResult handle(String userId, String message) {
        Detection detection = detector.detect(message);
        if (!detection.isControl()) {
            return ControlResult.NONE;
        }
        List<MemoryDocument> matches = findMatches(userId, detection.topic());
        if (matches.isEmpty()) {
            String ack = detection.kind() == Kind.FORGET
                    ? "Got it — I don't have a matching note to drop, but I won't bring that up."
                    : "Got it — I'll take your correction on board.";
            return new ControlResult(true, 0, ack);
        }
        Instant now = Instant.now();
        int n = 0;
        for (MemoryDocument m : matches) {
            if (detection.kind() == Kind.FORGET) {
                memories.delete(m);
            } else {
                // correction: archive so incremental analysis can supersede with the new statement
                m.setStatus(Status.ARCHIVED);
                m.setUpdatedAt(now);
                memories.save(m);
            }
            n++;
            if (n >= 5) {
                break;
            }
        }
        String ack = detection.kind() == Kind.FORGET
                ? (n == 1
                        ? "Done — I've forgotten that."
                        : "Done — I've dropped " + n + " related notes.")
                : "Thanks for the correction — I'll remember it the way you said.";
        return new ControlResult(true, n, ack);
    }

    private List<MemoryDocument> findMatches(String userId, String topic) {
        String needle = topic == null ? "" : topic.strip();
        if (needle.length() < 2) {
            return List.of();
        }
        float[] query = embeddings.embedQuery(needle);
        String normalized = MemoryPolicy.normalize(needle);
        record Scored(MemoryDocument m, double score) {}
        List<Scored> scored = new ArrayList<>();
        Instant now = Instant.now();
        for (MemoryDocument m : memories.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, Status.ACTIVE)) {
            if (!MemoryPolicy.isUsable(m, now)) {
                continue;
            }
            double sim = m.getEmbedding() != null && !m.getEmbedding().isEmpty()
                    ? VectorMath.cosine(query, m.getEmbedding())
                    : 0;
            String contentNorm = MemoryPolicy.normalize(m.getContent());
            if (contentNorm.contains(normalized) || normalized.contains(contentNorm)) {
                sim = Math.max(sim, 0.85);
            }
            if (sim >= MIN_SIM) {
                scored.add(new Scored(m, sim));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<MemoryDocument> out = new ArrayList<>();
        for (Scored s : scored) {
            out.add(s.m());
            if (out.size() >= 5) {
                break;
            }
        }
        return out;
    }
}
