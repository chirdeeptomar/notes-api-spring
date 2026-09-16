package com.empyrean.elide.observability;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Emits the per-request tier summary once an async dispatch has finished.
 * <p>
 * Elide handles requests on a worker thread, so the tally is only complete after the async context
 * completes; see {@link QuerySourceLoggingFilter} for why this cannot be done in the filter's
 * {@code finally}. Timeout and error are logged too - a request that failed still answers "how far
 * did it get and what did it touch", which is usually the first thing worth knowing.
 */
class QuerySourceSummaryListener implements AsyncListener {

    private final QuerySourceRecorder recorder;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    QuerySourceSummaryListener(QuerySourceRecorder recorder, HttpServletRequest request,
            HttpServletResponse response) {
        this.recorder = recorder;
        this.request = request;
        this.response = response;
    }

    @Override
    public void onComplete(AsyncEvent event) {
        QuerySourceLoggingFilter.log(recorder, request, response.getStatus());
    }

    @Override
    public void onTimeout(AsyncEvent event) {
        QuerySourceLoggingFilter.log(recorder, request, response.getStatus());
    }

    @Override
    public void onError(AsyncEvent event) {
        QuerySourceLoggingFilter.log(recorder, request, response.getStatus());
    }

    /**
     * A re-dispatch starts a new async cycle on the same request; re-registering keeps the
     * listener attached so the summary is not lost when the container dispatches more than once.
     */
    @Override
    public void onStartAsync(AsyncEvent event) {
        event.getAsyncContext().addListener(this);
    }
}
