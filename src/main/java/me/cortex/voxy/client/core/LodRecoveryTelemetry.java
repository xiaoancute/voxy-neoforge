package me.cortex.voxy.client.core;

final class LodRecoveryTelemetry {
    private static final int MIN_NON_EMPTY_SYNC_SECTIONS = 128;
    private static final int EMPTY_SYNC_REFRESH_THRESHOLD = 3;
    private static final int LOW_BOUND_FRAME_THRESHOLD = 3;
    private static final long MIN_REFRESH_INTERVAL_MS = 30_000L;

    private int lastSyncSections;
    private int lastNonEmptySyncSections;
    private int consecutiveEmptySyncs;
    private int lowBoundFrameStreak;
    private int lastEffectiveBounds;
    private long lastSyncTimeMs;
    private long lastRefreshTimeMs;
    private int autoRefreshCount;
    private boolean refreshPending;

    void recordSyncResult(int syncedSections, int effectiveBounds, long nowMs) {
        this.lastSyncSections = syncedSections;
        this.lastEffectiveBounds = effectiveBounds;
        this.lastSyncTimeMs = nowMs;
        if (syncedSections > 0) {
            this.lastNonEmptySyncSections = syncedSections;
            this.consecutiveEmptySyncs = 0;
            this.lowBoundFrameStreak = 0;
        } else if (this.lastNonEmptySyncSections > 0) {
            this.consecutiveEmptySyncs++;
        }
    }

    void recordFrameBounds(int effectiveBounds) {
        this.lastEffectiveBounds = effectiveBounds;
        if (this.lastNonEmptySyncSections < MIN_NON_EMPTY_SYNC_SECTIONS) {
            this.lowBoundFrameStreak = 0;
            return;
        }
        if (isSuspiciouslyLow(effectiveBounds)) {
            this.lowBoundFrameStreak++;
        } else {
            this.lowBoundFrameStreak = 0;
        }
    }

    boolean shouldRequestRefresh(long nowMs) {
        if (this.refreshPending || this.lastNonEmptySyncSections < MIN_NON_EMPTY_SYNC_SECTIONS) {
            return false;
        }
        if (!isSuspiciouslyLow(this.lastEffectiveBounds)) {
            return false;
        }
        if (this.consecutiveEmptySyncs < EMPTY_SYNC_REFRESH_THRESHOLD
                && this.lowBoundFrameStreak < LOW_BOUND_FRAME_THRESHOLD) {
            return false;
        }
        return this.lastRefreshTimeMs == 0 || nowMs - this.lastRefreshTimeMs >= MIN_REFRESH_INTERVAL_MS;
    }

    void markRefreshRequested(long nowMs) {
        this.refreshPending = true;
        this.lastRefreshTimeMs = nowMs;
        this.autoRefreshCount++;
    }

    void markRefreshObserved() {
        this.refreshPending = false;
        this.consecutiveEmptySyncs = 0;
        this.lowBoundFrameStreak = 0;
    }

    String getSummary() {
        return "lastSync=" + this.lastSyncSections
                + ",lastGoodSync=" + this.lastNonEmptySyncSections
                + ",emptySyncStreak=" + this.consecutiveEmptySyncs
                + ",lowBoundFrames=" + this.lowBoundFrameStreak
                + ",bounds=" + this.lastEffectiveBounds
                + ",lastSyncMs=" + this.lastSyncTimeMs
                + ",autoRefreshes=" + this.autoRefreshCount
                + ",refreshPending=" + this.refreshPending;
    }

    private boolean isSuspiciouslyLow(int effectiveBounds) {
        int lowBoundLimit = Math.max(8, this.lastNonEmptySyncSections / 4);
        return effectiveBounds < lowBoundLimit;
    }
}
