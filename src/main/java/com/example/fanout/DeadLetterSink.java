package com.example.fanout;

/** Where tasks that cannot succeed go (in production: SQS DLQ + alarm + redrive tooling). */
public interface DeadLetterSink {
    void accept(Task task, String reason, Throwable cause);
}
