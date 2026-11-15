package com.bookbuddy.memory;

import com.bookbuddy.memory.MemoryMerger.Outcome;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Persists merge outcomes write-then-swap: every new memory is written first, then in-place modifications, and only
 * then are replaced memories archived. If anything fails part-way the user ends up with a duplicate at worst, never
 * with a lost memory. Nothing is ever deleted here.
 */
@Component
public class MemoryWriter {

    private final MemoryRepository repository;

    public MemoryWriter(MemoryRepository repository) {
        this.repository = repository;
    }

    public void persist(List<Outcome> outcomes) {
        for (Outcome o : outcomes) {
            if (o.created() != null) {
                repository.save(o.created());
            }
        }
        for (Outcome o : outcomes) {
            if (o.changed() != null) {
                repository.save(o.changed());
            }
        }
        for (Outcome o : outcomes) {
            if (o.superseded() != null) {
                repository.save(o.superseded());
            }
        }
    }
}
