package ar.gob.municipalidad.rentas;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Fila única creada por Flyway: serializa el bootstrap entre procesos y transacciones. */
@Entity @Table(name="demo_bootstrap_lock")
class DemoBootstrapLock {
    @Id Integer id;
    protected DemoBootstrapLock() {}
}

interface DemoBootstrapLockRepository extends JpaRepository<DemoBootstrapLock,Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from DemoBootstrapLock b where b.id=1")
    DemoBootstrapLock lockBootstrap();
}
