package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainCoverageGate {
    private static final String PACKAGE = "ar.gob.municipalidad.rentas";
    private static final List<String> EXPECTED_FAMILIES = List.of(
        "BillingService", "CreditBalanceService", "AdjustmentService",
        "ElectronicPaymentService", "ExemptionService", "LateChargeService",
        "LiquidationRunService", "LiquidationService", "PaymentPlanPrincipalLedger",
        "PaymentPlanService", "PaymentService", "PlanWorkflowService",
        "ReversalService", "DueDateProcessor"
    );
    private static final List<String> CSV_HEADER = List.of(
        "GROUP", "PACKAGE", "CLASS", "INSTRUCTION_MISSED", "INSTRUCTION_COVERED",
        "BRANCH_MISSED", "BRANCH_COVERED", "LINE_MISSED", "LINE_COVERED"
    );

    @Test
    void domainCoverageMeetsItsDedicatedGate() throws Exception {
        Path pom = Path.of(requiredProperty("jacoco.domain.pom"));
        Path report = Path.of(requiredProperty("jacoco.domain.csv"));
        Path output = Path.of(requiredProperty("jacoco.domain.output"));
        List<String> configuredFamilies = readConfiguredFamilies(pom);
        assertTrue(configuredFamilies.equals(EXPECTED_FAMILIES),
            "El perímetro JaCoCo del dominio cambió: " + configuredFamilies);
        assertTrue(readPitFamilies(pom).equals(EXPECTED_FAMILIES),
            "El perímetro PIT y el perímetro JaCoCo deben contener las mismas 14 familias");

        Map<String, Counts> counts = new LinkedHashMap<>();
        Map<String, Set<String>> classes = new LinkedHashMap<>();
        EXPECTED_FAMILIES.forEach(family -> {
            counts.put(family, new Counts());
            classes.put(family, new TreeSet<>());
        });
        Counts total = new Counts();
        try (BufferedReader reader = Files.newBufferedReader(report, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            assertTrue(header.size() >= CSV_HEADER.size() && header.subList(0, CSV_HEADER.size()).equals(CSV_HEADER),
                "El CSV JaCoCo no tiene las columnas esperadas");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> row = parseCsv(line);
                assertTrue(row.size() == header.size(), "Fila JaCoCo malformada: " + line);
                if (!PACKAGE.equals(row.get(1))) continue;
                String className = row.get(2);
                String family = EXPECTED_FAMILIES.stream()
                    .filter(candidate -> belongsToFamily(className, candidate))
                    .findFirst().orElse(null);
                if (family == null) continue;
                Counts classCounts = Counts.from(row);
                counts.get(family).add(classCounts);
                total.add(classCounts);
                classes.get(family).add(className);
            }
        }

        for (String family : EXPECTED_FAMILIES) {
            assertTrue(!classes.get(family).isEmpty(), "Falta del reporte la clase esperada " + family);
            assertTrue(counts.get(family).hasLineDenominator(), "Denominador de líneas vacío para " + family);
        }
        assertTrue(total.hasLineDenominator(), "Denominador agregado de líneas vacío");
        assertTrue(total.hasBranchDenominator(), "Denominador agregado de ramas vacío");

        BigDecimal lineThreshold = threshold("jacoco.domain.lines.minimum", "0.80");
        BigDecimal branchThreshold = threshold("jacoco.domain.branches.minimum", "0.70");
        Files.createDirectories(output);
        writeEvidence(output.resolve("coverage.csv"), classes, counts, total);
        Files.writeString(output.resolve("summary.txt"),
            "Familias: " + EXPECTED_FAMILIES.size() + "\n" +
            "Clases (incluye internas): " + classes.values().stream().mapToInt(Set::size).sum() + "\n" +
            "Líneas: " + total.coveredLines + "/" + (total.coveredLines + total.missedLines) +
            " = " + total.ratio(total.coveredLines, total.missedLines).toPlainString() +
            " (mínimo " + lineThreshold + ")\n" +
            "Ramas: " + total.coveredBranches + "/" + (total.coveredBranches + total.missedBranches) +
            " = " + total.ratio(total.coveredBranches, total.missedBranches).toPlainString() +
            " (mínimo " + branchThreshold + ")\n", StandardCharsets.UTF_8);

        assertTrue(total.meets(total.coveredLines, total.missedLines, lineThreshold),
            "Cobertura de líneas de dominio inferior al mínimo: " + total.ratio(total.coveredLines, total.missedLines));
        assertTrue(total.meets(total.coveredBranches, total.missedBranches, branchThreshold),
            "Cobertura de ramas de dominio inferior al mínimo: " + total.ratio(total.coveredBranches, total.missedBranches));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Falta la propiedad " + name);
        return value;
    }

    private static BigDecimal threshold(String property, String fallback) {
        BigDecimal value = new BigDecimal(System.getProperty(property, fallback));
        assertTrue(value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0,
            "Umbral fuera de rango para " + property);
        return value;
    }

    private static boolean belongsToFamily(String className, String family) {
        return className.equals(family) || className.startsWith(family + ".")
            || className.startsWith(family + "$") || className.startsWith(family + " ");
    }

    private static List<String> readConfiguredFamilies(Path pom) throws Exception {
        Document document = parseXml(pom);
        NodeList properties = document.getElementsByTagNameNS("http://maven.apache.org/POM/4.0.0", "m5.domain.coverage.families");
        if (properties.getLength() == 0) throw new IllegalStateException("No se encontró m5.domain.coverage.families");
        List<String> families = new ArrayList<>();
        for (String family : properties.item(0).getTextContent().split(",")) {
            String value = family.trim();
            assertTrue(!value.isEmpty() && value.matches("[A-Za-z0-9]+"), "Familia de dominio inválida: " + value);
            families.add(value);
        }
        return families;
    }

    private static List<String> readPitFamilies(Path pom) throws Exception {
        Document document = parseXml(pom);
        NodeList targets = document.getElementsByTagNameNS("http://maven.apache.org/POM/4.0.0", "targetClasses");
        for (int i = 0; i < targets.getLength(); i++) {
            Element target = (Element) targets.item(i);
            NodeList params = target.getElementsByTagNameNS("http://maven.apache.org/POM/4.0.0", "param");
            if (params.getLength() != EXPECTED_FAMILIES.size()) continue;
            List<String> families = new ArrayList<>();
            for (int j = 0; j < params.getLength(); j++) {
                String name = params.item(j).getTextContent();
                String prefix = PACKAGE + ".";
                assertTrue(name.startsWith(prefix) && name.endsWith("*"), "Target PIT inválido: " + name);
                families.add(name.substring(prefix.length(), name.length() - 1));
            }
            return families;
        }
        throw new IllegalStateException("No se encontraron los 14 targets PIT");
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static List<String> parseCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else field.append(current);
        }
        assertTrue(!quoted, "Campo CSV sin cerrar");
        fields.add(field.toString());
        return fields;
    }

    private static void writeEvidence(Path path, Map<String, Set<String>> classes,
                                      Map<String, Counts> counts, Counts total) throws Exception {
        StringBuilder csv = new StringBuilder("family,classes,lines_covered,lines_missed,line_ratio,branches_covered,branches_missed,branch_ratio\n");
        for (String family : EXPECTED_FAMILIES) appendEvidence(csv, family, classes.get(family).size(), counts.get(family));
        appendEvidence(csv, "TOTAL", classes.values().stream().mapToInt(Set::size).sum(), total);
        Files.writeString(path, csv, StandardCharsets.UTF_8);
    }

    private static void appendEvidence(StringBuilder csv, String family, int classCount, Counts counts) {
        csv.append(family).append(',').append(classCount).append(',')
            .append(counts.coveredLines).append(',').append(counts.missedLines).append(',')
            .append(counts.ratio(counts.coveredLines, counts.missedLines)).append(',')
            .append(counts.coveredBranches).append(',').append(counts.missedBranches).append(',')
            .append(counts.ratio(counts.coveredBranches, counts.missedBranches)).append('\n');
    }

    private static final class Counts {
        long coveredLines, missedLines, coveredBranches, missedBranches;
        static Counts from(List<String> row) {
            Counts result = new Counts();
            result.missedBranches = Long.parseLong(row.get(5));
            result.coveredBranches = Long.parseLong(row.get(6));
            result.missedLines = Long.parseLong(row.get(7));
            result.coveredLines = Long.parseLong(row.get(8));
            return result;
        }
        void add(Counts other) {
            coveredLines += other.coveredLines; missedLines += other.missedLines;
            coveredBranches += other.coveredBranches; missedBranches += other.missedBranches;
        }
        boolean hasLineDenominator() { return coveredLines + missedLines > 0; }
        boolean hasBranchDenominator() { return coveredBranches + missedBranches > 0; }
        boolean meets(long covered, long missed, BigDecimal threshold) {
            long denominator = covered + missed;
            if (denominator == 0) throw new IllegalStateException("Denominador de cobertura cero");
            return BigDecimal.valueOf(covered).compareTo(threshold.multiply(BigDecimal.valueOf(denominator))) >= 0;
        }
        BigDecimal ratio(long covered, long missed) {
            long denominator = covered + missed;
            if (denominator == 0) throw new IllegalStateException("Denominador de cobertura cero");
            return BigDecimal.valueOf(covered).divide(BigDecimal.valueOf(denominator), 8, java.math.RoundingMode.HALF_UP);
        }
    }
}
