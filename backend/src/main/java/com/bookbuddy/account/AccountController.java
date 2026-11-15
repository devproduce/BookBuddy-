package com.bookbuddy.account;

import com.bookbuddy.account.AccountDtos.DeletionReport;
import com.bookbuddy.account.AccountDtos.ExportDocument;
import com.bookbuddy.auth.CurrentUser;
import java.time.LocalDate;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AccountController {

    private final AccountDataService account;

    /** Full JSON export, sent as a download. */
    @GetMapping("/export")
    public ResponseEntity<ExportDocument> export() {
        ExportDocument doc = account.export(CurrentUser.userId());
        String name = "bookbuddy-export-" + LocalDate.now(ZoneOffset.UTC) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(doc);
    }

    /** Hard-deletes every book, conversation, memory and job of the signed-in user. The account itself stays. */
    @DeleteMapping("/account/data")
    public DeletionReport deleteAllData() {
        return account.deleteAllData(CurrentUser.userId());
    }
}
