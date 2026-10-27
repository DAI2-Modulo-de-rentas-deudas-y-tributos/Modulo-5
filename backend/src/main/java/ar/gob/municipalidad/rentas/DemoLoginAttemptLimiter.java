package ar.gob.municipalidad.rentas;

interface DemoLoginAttemptLimiter {
    boolean recordFailure(String hashedKey);
    void reset(String hashedKey);
}
