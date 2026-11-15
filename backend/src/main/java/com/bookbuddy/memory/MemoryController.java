package com.bookbuddy.memory;

import com.bookbuddy.auth.CurrentUser;
import com.bookbuddy.common.ApiException;
import com.bookbuddy.memory.MemoryDocument.Category;
import com.bookbuddy.memory.MemoryDocument.Status;
import com.bookbuddy.memory.MemoryDtos.CreateMemoryRequest;
import com.bookbuddy.memory.MemoryDtos.JobResponse;
import com.bookbuddy.memory.MemoryDtos.MemoryResponse;
import com.bookbuddy.memory.MemoryDtos.ProfileResponse;
import com.bookbuddy.memory.MemoryDtos.UpdateMemoryRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memory;

    @GetMapping("/profile")
    public ProfileResponse profile() {
        return memory.profile(CurrentUser.userId());
    }

    /**
     * @param status ACTIVE (default), ARCHIVED, EXPIRED, DELETED or ALL
     * @param ids    comma-separated ids; returns exactly those (any status)
     */
    @GetMapping
    public List<MemoryResponse> list(
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ids) {
        boolean all = false;
        Status parsed = null;
        if (status != null && !status.isBlank()) {
            if ("ALL".equalsIgnoreCase(status.strip())) {
                all = true;
            } else {
                try {
                    parsed = Status.valueOf(status.strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("VALIDATION_ERROR", "Unknown status");
                }
            }
        }
        return memory.list(CurrentUser.userId(), category, parsed, all, MemoryService.parseIds(ids));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryResponse create(@Valid @RequestBody CreateMemoryRequest request) {
        return memory.create(CurrentUser.userId(), request);
    }

    @PatchMapping("/{id}")
    public MemoryResponse update(@PathVariable String id, @Valid @RequestBody UpdateMemoryRequest request) {
        return memory.update(CurrentUser.userId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        memory.delete(CurrentUser.userId(), id);
    }

    @PostMapping("/{id}/confirm")
    public MemoryResponse confirm(@PathVariable String id) {
        return memory.confirm(CurrentUser.userId(), id);
    }

    /** Queues an asynchronous rebuild from the user's (non-incognito) conversations; poll the returned job id. */
    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse rebuild() {
        return memory.rebuild(CurrentUser.userId());
    }

    @GetMapping("/rebuild/{jobId}")
    public JobResponse rebuildStatus(@PathVariable String jobId) {
        return memory.job(CurrentUser.userId(), jobId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        memory.clear(CurrentUser.userId());
    }
}
