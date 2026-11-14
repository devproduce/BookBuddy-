package com.bookbuddy.book;

import com.bookbuddy.auth.CurrentUser;
import com.bookbuddy.book.BookDtos.BookResponse;
import com.bookbuddy.book.BookDtos.CreateBookRequest;
import com.bookbuddy.book.BookDtos.UpdateBookRequest;
import com.bookbuddy.ingestion.IngestionDtos.BookStatusResponse;
import com.bookbuddy.ingestion.IngestionDtos.ChunkPageResponse;
import com.bookbuddy.ingestion.IngestionJobService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;
    private final IngestionJobService ingestionJobService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookResponse create(@Valid @RequestBody CreateBookRequest request) {
        return bookService.create(CurrentUser.userId(), request);
    }

    @GetMapping
    public List<BookResponse> list() {
        return bookService.list(CurrentUser.userId());
    }

    @GetMapping("/{id}")
    public BookResponse get(@PathVariable String id) {
        return bookService.get(CurrentUser.userId(), id);
    }

    @PutMapping("/{id}")
    public BookResponse put(@PathVariable String id, @Valid @RequestBody UpdateBookRequest request) {
        return bookService.update(CurrentUser.userId(), id, request);
    }

    @PatchMapping("/{id}")
    public BookResponse patch(@PathVariable String id, @Valid @RequestBody UpdateBookRequest request) {
        return bookService.update(CurrentUser.userId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        ingestionJobService.deleteBookCascade(CurrentUser.userId(), id);
    }

    /** Stores the PDF temporarily; it is deleted once processing finishes. */
    @PostMapping(value = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BookStatusResponse uploadFile(@PathVariable String id, @RequestPart("file") MultipartFile file) {
        return ingestionJobService.upload(CurrentUser.userId(), id, file);
    }

    @PostMapping("/{id}/process")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BookStatusResponse process(@PathVariable String id) {
        return ingestionJobService.start(CurrentUser.userId(), id);
    }

    @PostMapping("/{id}/cancel")
    public BookStatusResponse cancel(@PathVariable String id) {
        return ingestionJobService.cancel(CurrentUser.userId(), id);
    }

    @GetMapping("/{id}/status")
    public BookStatusResponse status(@PathVariable String id) {
        return ingestionJobService.status(CurrentUser.userId(), id);
    }

    @GetMapping("/{id}/chunks")
    public ChunkPageResponse chunks(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ingestionJobService.listChunks(CurrentUser.userId(), id, page, size);
    }
}
