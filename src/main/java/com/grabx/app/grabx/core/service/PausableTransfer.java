package com.grabx.app.grabx.core.service;

/** A transfer that can suspend reading without discarding its connection. */
interface PausableTransfer {
    void pauseTransfer();
    void resumeTransfer();
}
