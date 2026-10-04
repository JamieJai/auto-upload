package com.autoreg.channel;

public enum ListingStatus {
    PENDING,
    REGISTERING,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_INVALID,
    CANCELLED
}
