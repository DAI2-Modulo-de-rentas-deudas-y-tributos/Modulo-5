package ar.gob.municipalidad.rentas;

interface DemoLoginAttemptLimiter {
    boolean allowAttempt(String hashedKey);
    int retryAfterAttempt(String hashedKey);
    default boolean recordFailure(String hashedKey) { return allowAttempt(hashedKey); }
    boolean allowOriginAttempt(String hashedOriginKey, long maximumFailures);
    int retryAfterOriginAttempt(String hashedOriginKey);
    boolean recordOriginFailure(String hashedOriginKey, long maximumFailures);
    void reset(String hashedKey);
}
