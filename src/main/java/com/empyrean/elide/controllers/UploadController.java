package com.empyrean.elide.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.empyrean.elide.controllers.handlers.UploadHandler;
import com.empyrean.elide.controllers.responses.UploadResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Accepts a file upload at {@code POST /api/v1/uploads} as
 * {@code multipart/form-data}, under the
 * form field {@code file}.
 * <p>
 * Any content type is accepted. What happens to the bytes is decided by the
 * {@link UploadHandler}
 * beans on the classpath; a file no handler claims is accepted and described in
 * the response
 * without being parsed. Supporting a format is a matter of adding a handler
 * bean, not editing this
 * class. No handler ships with the endpoint, so by default every upload is
 * received, measured and
 * reported as unhandled.
 * <p>
 * Nothing is persisted here. Reading, importing or storing the contents belongs
 * to a handler.
 * <p>
 * Body size is bounded by Spring's multipart limits
 * ({@code spring.servlet.multipart.max-file-size}) rather than a check here.
 */
@RestController
public class UploadController {

    private static final Logger LOG = LoggerFactory.getLogger(UploadController.class);

    private final List<UploadHandler> handlers;

    public UploadController(List<UploadHandler> handlers) {
        this.handlers = handlers;
    }

    @PostMapping(path = "/api/v1/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Upload a file of any type")
    @ApiResponse(responseCode = "200", description = "Upload received; body reports what the handler found")
    @ApiResponse(responseCode = "400", description = "No file part was supplied, or it could not be read")
    public UploadResult upload(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing required multipart form field: 'file'");
        }

        String fileName = file.getOriginalFilename();
        String contentType = file.getContentType();
        long size = file.getSize();

        UploadHandler handler = findHandler(contentType, fileName);
        if (handler == null) {
            LOG.debug("No upload handler for contentType={} fileName={}; accepting without parsing",
                    contentType, fileName);
            return UploadResult.notHandled(fileName, contentType, size);
        }

        try (InputStream content = file.getInputStream()) {
            return handler.handle(fileName, contentType, size, content);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read uploaded file: " + e.getMessage(), e);
        }
    }

    /** First handler claiming the upload, or {@code null} when none does. */
    private UploadHandler findHandler(String contentType, String fileName) {
        for (UploadHandler handler : handlers) {
            if (handler.supports(contentType, fileName)) {
                return handler;
            }
        }
        return null;
    }
}
