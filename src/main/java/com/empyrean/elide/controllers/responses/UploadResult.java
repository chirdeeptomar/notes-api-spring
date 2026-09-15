package com.empyrean.elide.controllers.responses;

import java.util.List;
import java.util.Map;

import com.empyrean.elide.controllers.handlers.UploadHandler;

/**
 * Outcome of handling one uploaded file, serialized as the JSON response body
 * of
 * {@link UploadResource}.
 * <p>
 * The first four fields describe the upload itself and are filled in by
 * {@link UploadResource}
 * for every request. {@code details} is open-ended so a handler can report
 * whatever is
 * meaningful for its format (row counts for a CSV, page counts for a PDF)
 * without this record
 * having to know about that format.
 *
 * @param fileName    the client-supplied file name, or {@code null} if the
 *                    client sent none
 * @param contentType the part's declared content type
 * @param size        the uploaded size in bytes
 * @param handler     name of the {@link UploadHandler} that processed the file,
 *                    or
 *                    {@code "none"} when no handler claimed the content type
 * @param details     handler-specific description of what was found; empty when
 *                    nothing was parsed
 * @param errors      problems encountered while handling the file, in the order
 *                    found; empty on
 *                    success
 */
public record UploadResult(
        String fileName,
        String contentType,
        long size,
        String handler,
        Map<String, Object> details,
        List<String> errors) {

    /**
     * Result for a file no handler claimed: it was received and measured, but
     * nothing was
     * parsed. Reported as a success, since accepting arbitrary file types is
     * intended
     * behaviour rather than an error.
     */
    public static UploadResult notHandled(String fileName, String contentType, long size) {
        return new UploadResult(fileName, contentType, size, "none", Map.of(), List.of());
    }

    /** Result for a file a handler parsed successfully. */
    public static UploadResult handled(String fileName, String contentType, long size,
            String handler, Map<String, Object> details) {
        return new UploadResult(fileName, contentType, size, handler, details, List.of());
    }

    /** Result for a file a handler claimed but could not fully process. */
    public static UploadResult failed(String fileName, String contentType, long size,
            String handler, List<String> errors) {
        return new UploadResult(fileName, contentType, size, handler, Map.of(), errors);
    }
}
