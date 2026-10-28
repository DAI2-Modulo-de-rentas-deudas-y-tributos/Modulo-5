package ar.gob.municipalidad.rentas;

import java.util.function.Supplier;

/** Frontera transaccional para proteger comandos HTTP repetibles. */
public interface IdempotencyExecutor {
    <T> T execute(String operation,String key,Object request,Class<T> responseType,Supplier<T> action);
}
