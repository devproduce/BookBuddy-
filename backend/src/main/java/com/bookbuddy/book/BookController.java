package com.bookbuddy.book;

import com.bookbuddy.auth.CurrentUser;
import com.bookbuddy.book.BookDtos.BookResponse;
import com.bookbuddy.book.BookDtos.CreateBookRequest;
import com.bookbuddy.book.BookDtos.UpdateBookRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

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
        bookService.delete(CurrentUser.userId(), id);
    }
}
