package com.bookbuddy.book;

import com.bookbuddy.book.BookDtos.BookResponse;
import com.bookbuddy.book.BookDtos.CreateBookRequest;
import com.bookbuddy.book.BookDtos.UpdateBookRequest;
import com.bookbuddy.common.ApiException;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookService {

    private final BookRepository bookRepository;

    public BookResponse create(String userId, CreateBookRequest request) {
        String hash = request.contentHash().toLowerCase(Locale.ROOT);
        bookRepository.findByUserIdAndContentHash(userId, hash).ifPresent(existing -> {
            throw duplicate(existing.getId());
        });

        BookDocument book = new BookDocument();
        book.setUserId(userId);
        book.setTitle(request.title().trim());
        book.setAuthor(request.author() == null ? null : request.author().trim());
        book.setContentHash(hash);
        book.setFileSizeBytes(request.fileSizeBytes());
        book.setPageCount(request.pageCount());
        book.setStatus(BookDocument.Status.QUEUED);

        try {
            return BookResponse.from(bookRepository.save(book));
        } catch (DuplicateKeyException e) {
            String existingId = bookRepository
                    .findByUserIdAndContentHash(userId, hash)
                    .map(BookDocument::getId)
                    .orElse("unknown");
            throw duplicate(existingId);
        }
    }

    public List<BookResponse> list(String userId) {
        return bookRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(BookResponse::from)
                .toList();
    }

    public BookResponse get(String userId, String bookId) {
        return BookResponse.from(find(userId, bookId));
    }

    public BookResponse update(String userId, String bookId, UpdateBookRequest request) {
        BookDocument book = find(userId, bookId);
        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw ApiException.badRequest("VALIDATION_ERROR", "title must not be blank");
            }
            book.setTitle(request.title().trim());
        }
        if (request.author() != null) {
            book.setAuthor(request.author().trim());
        }
        return BookResponse.from(bookRepository.save(book));
    }

    /**
     * Removes only the book document. Use {@code IngestionJobService#deleteBookCascade} to also remove its chunks,
     * ingestion jobs and pending upload.
     */
    public void delete(String userId, String bookId) {
        BookDocument book = find(userId, bookId);
        bookRepository.delete(book);
    }

    private BookDocument find(String userId, String bookId) {
        return bookRepository
                .findByIdAndUserId(bookId, userId)
                .orElseThrow(() -> ApiException.notFound("BOOK_NOT_FOUND", "Book not found"));
    }

    private static ApiException duplicate(String existingId) {
        return ApiException.conflict("BOOK_DUPLICATE", "Book already exists with id " + existingId);
    }
}
