package ar.gob.municipalidad.rentas;

interface DemoLoginAttemptLimiter {
    boolean allowAttempt(String hashedKey);
    default boolean recordFailure(String hashedKey) { return allowAttempt(hashedKey); }
    void reset(String hashedKey);
}
