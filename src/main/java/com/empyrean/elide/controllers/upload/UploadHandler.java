package com.empyrean.elide.upload;

import java.io.IOException;
import java.io.InputStream;

/**
 * Strategy for interpreting the contents of an uploaded file.
 * <p>
 * {@link UploadResource} accepts a file of any content type and then asks each handler, in
 * bean-discovery order, whether it wants to process it. Supporting a format means adding an
 * {@code @Component} implementation of this interface - no change to the endpoint. A
 * file no handler claims is still accepted and reported, just not parsed.
 * <p>
 * No handler ships with the endpoint: out of the box every upload is reported as unhandled.
 */
public interface UploadHandler {

    /** Short name identifying this handler in an {@link UploadResult}. */
    String name();

    /**
     * Whether this handler can parse the given upload.
     *
     * @param contentType the part's declared content type, or {@code null} if absent
     * @param fileName the client-supplied file name, or {@code null} if absent
     */
    boolean supports(String contentType, String fileName);

    /**
     * Processes the upload and describes what was found.
     * <p>
     * Implementations must not close {@code content}; the caller owns the stream. A handler that
     * needs the bytes more than once should copy them, as the stream is not re-readable.
     *
     * @throws IOException if the stream cannot be read
     */
    UploadResult handle(String fileName, String contentType, long size, InputStream content)
            throws IOException;
}
