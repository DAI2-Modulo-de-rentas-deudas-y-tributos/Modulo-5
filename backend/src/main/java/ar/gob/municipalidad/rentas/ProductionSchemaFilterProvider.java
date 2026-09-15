package ar.gob.municipalidad.rentas;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Excluye el esquema DEMO de todas las operaciones Hibernate del perfil productivo. */
public final class ProductionSchemaFilterProvider implements SchemaFilterProvider {
    private static final SchemaFilter NON_DEMO = new SchemaFilter() {
        @Override public boolean includeNamespace(Namespace namespace) { return true; }
        @Override public boolean includeTable(Table table) { return !isDemo(table.getName()); }
        @Override public boolean includeSequence(Sequence sequence) {
            return !isDemo(sequence.getName().getSequenceName().getText());
        }
        private boolean isDemo(String name) {
            return name != null && name.toLowerCase(java.util.Locale.ROOT).startsWith("demo_");
        }
    };

    @Override public SchemaFilter getCreateFilter() { return NON_DEMO; }
    @Override public SchemaFilter getDropFilter() { return NON_DEMO; }
    @Override public SchemaFilter getTruncatorFilter() { return NON_DEMO; }
    @Override public SchemaFilter getMigrateFilter() { return NON_DEMO; }
    @Override public SchemaFilter getValidateFilter() { return NON_DEMO; }
}

@Configuration
@Profile("prod")
class ProductionSecurityBoundary {
    ProductionSecurityBoundary(@Value("${rentas.security.dev-mode:false}") boolean demoEnabled) {
        if (demoEnabled) throw new IllegalStateException("RENTAS_SECURITY_DEV_MODE no puede habilitarse con el perfil prod");
    }
}
