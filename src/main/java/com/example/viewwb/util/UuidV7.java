package com.example.viewwb.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7 generator. ScalarRE's outbox poller derives its event_id
 * scan range from timestamps assuming time-ordered UUIDv7 ids — a random
 * UUIDv4 falls outside that range and is never picked up, so producers MUST
 * use this for re_outbox event ids.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static String generate() {
        long ts = System.currentTimeMillis();
        long msb = (ts << 16)                              // unix_ts_ms (48 bit)
                | 0x7000L                                  // version 7
                | RANDOM.nextInt(1 << 12);                 // rand_a (12 bit)
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL)
                | 0x8000000000000000L;                     // variant '10' + rand_b (62 bit)
        return new UUID(msb, lsb).toString();
    }
}
