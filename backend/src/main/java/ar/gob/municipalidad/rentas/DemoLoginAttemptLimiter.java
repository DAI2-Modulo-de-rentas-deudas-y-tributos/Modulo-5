package ar.gob.municipalidad.rentas;

interface DemoLoginAttemptLimiter {
    boolean allowAttempt(String hashedKey);
    default boolean recordFailure(String hashedKey) { return allowAttempt(hashedKey); }
    boolean allowOriginAttempt(String hashedOriginKey, long maximumFailures);
    boolean recordOriginFailure(String hashedOriginKey, long maximumFailures);
    void reset(String hashedKey);
}
