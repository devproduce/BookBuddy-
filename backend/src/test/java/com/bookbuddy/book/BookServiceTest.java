package com.bookbuddy.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bookbuddy.book.BookDtos.BookResponse;
import com.bookbuddy.book.BookDtos.CreateBookRequest;
import com.bookbuddy.common.ApiException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BookServiceTest {

    private static final String HASH = "a".repeat(64);

    private final BookRepository repository = mock(BookRepository.class);
    private final BookService service = new BookService(repository);

    @Test
    void duplicateHashReturnsConflictWithExistingId() {
        BookDocument existing = new BookDocument();
        existing.setId("book-123");
        when(repository.findByUserIdAndContentHash("u1", HASH)).thenReturn(Optional.of(existing));

        ApiException ex = assertThrows(
                ApiException.class,
                () -> service.create("u1", new CreateBookRequest("T", "A", HASH, 100L, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertTrue(ex.getMessage().contains("book-123"));
        verify(repository, never()).save(any());
    }

    @Test
    void createQueuesBook() {
        when(repository.findByUserIdAndContentHash("u1", HASH)).thenReturn(Optional.empty());
        when(repository.save(any(BookDocument.class))).thenAnswer(inv -> {
            BookDocument b = inv.getArgument(0);
            b.setId("new-id");
            return b;
        });

        BookResponse response = service.create("u1", new CreateBookRequest(" T ", "A", HASH.toUpperCase(), 100L, 10));

        assertEquals("new-id", response.id());
        assertEquals("T", response.title());
        assertEquals(HASH, response.contentHash());
        assertEquals(BookDocument.Status.QUEUED, response.status());
    }

    @Test
    void getOtherUsersBookIsNotFound() {
        when(repository.findByIdAndUserId("b1", "u2")).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.get("u2", "b1"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
