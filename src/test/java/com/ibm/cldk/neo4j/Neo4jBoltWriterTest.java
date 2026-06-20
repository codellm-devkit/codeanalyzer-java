/*
Copyright IBM Corporation 2023, 2024

Licensed under the Apache Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.Problem;
import com.ibm.cldk.SymbolTable;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.neo4j.BoltWriter.BoltConfig;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.containers.Neo4jContainer;

/**
 * Integration test for the Neo4j {@link BoltWriter}. Spins up a real Neo4j via Testcontainers,
 * projects a sample fixture to graph rows, pushes them, and asserts the graph in the database —
 * including the incremental behaviours (idempotent re-push, vanished-declaration cleanup, and
 * full-run orphan pruning).
 *
 * <p>This suite needs a container runtime (Docker / Podman), so it is OPT-IN: it runs only when the
 * {@code RUN_CONTAINER_TESTS} environment variable is set (e.g. {@code RUN_CONTAINER_TESTS=1 ./gradlew
 * test}). The no-container schema conformance test always runs.
 */
@EnabledIfEnvironmentVariable(named = "RUN_CONTAINER_TESTS", matches = ".+")
public class Neo4jBoltWriterTest {

    private static final Path FIXTURE = Paths.get("src/test/resources/test-applications/call-graph-test");
    private static final String APP = "call-graph-test";
    private static final String PASSWORD = "testpassword123";

    @SuppressWarnings("resource")
    private static final Neo4jContainer<?> CONTAINER =
            new Neo4jContainer<>("neo4j:5").withAdminPassword(PASSWORD);

    private static Driver driver;
    private static BoltConfig cfg;

    @BeforeAll
    static void startup() {
        CONTAINER.start();
        cfg = new BoltConfig(CONTAINER.getBoltUrl(), "neo4j", CONTAINER.getAdminPassword(), null);
        driver = GraphDatabase.driver(cfg.uri, AuthTokens.basic(cfg.user, cfg.password));
    }

    @AfterAll
    static void teardown() {
        if (driver != null) {
            driver.close();
        }
        CONTAINER.stop();
    }

    private static GraphRows projectFixture() throws Exception {
        Pair<Map<String, JavaCompilationUnit>, Map<String, List<Problem>>> extracted =
                SymbolTable.extractAll(FIXTURE);
        return GraphProjector.project(extracted.getLeft(), null, APP);
    }

    private long num(String cypher) {
        try (Session s = driver.session()) {
            return s.run(cypher).single().get(0).asLong();
        }
    }

    @Test
    public void fullPushMaterializesTheWholeGraphAndSchema() throws Exception {
        GraphRows rows = projectFixture();
        BoltWriter.write(rows, cfg, true);

        // Every projected node/edge lands.
        assertEquals(rows.nodes.size(), num("MATCH (n) RETURN count(n)"));
        assertEquals(rows.edges.size(), num("MATCH ()-[r]->() RETURN count(r)"));

        // Shared :Symbol label spans the id-keyed declaration kinds (Type + Callable).
        long symbol = num("MATCH (s:Symbol) RETURN count(s)");
        long kinds = num("MATCH (s:Symbol) WHERE s:Type OR s:Callable RETURN count(s)");
        assertTrue(symbol > 0, "expected Symbol nodes");
        assertEquals(symbol, kinds);

        // Constraints + indexes were created up front.
        assertTrue(num("SHOW CONSTRAINTS YIELD name RETURN count(*)") >= 11);
        assertTrue(num("SHOW INDEXES YIELD name RETURN count(*)") >= 4);
    }

    @Test
    public void rePushingIdenticalAnalysisIsIdempotent() throws Exception {
        GraphRows rows = projectFixture();
        BoltWriter.write(rows, cfg, true);
        BoltWriter.write(rows, cfg, true);
        assertEquals(rows.nodes.size(), num("MATCH (n) RETURN count(n)"));
        assertEquals(rows.edges.size(), num("MATCH ()-[r]->() RETURN count(r)"));
    }

    @Test
    public void fullRunPrunesAUnitWhoseSourceVanished() throws Exception {
        Pair<Map<String, JavaCompilationUnit>, Map<String, List<Problem>>> extracted =
                SymbolTable.extractAll(FIXTURE);
        Map<String, JavaCompilationUnit> symbolTable = extracted.getLeft();
        BoltWriter.write(GraphProjector.project(symbolTable, null, APP), cfg, true);

        // Drop one compilation unit and re-push as a full run.
        String victim = symbolTable.keySet().stream().sorted().findFirst().orElseThrow(IllegalStateException::new);
        symbolTable.remove(victim);
        GraphRows reduced = GraphProjector.project(symbolTable, null, APP);
        BoltWriter.write(reduced, cfg, true);

        // The victim's unit-scoped nodes are gone; the surviving unit-scoped graph matches.
        try (Session s = driver.session()) {
            long victimNodes = s.run("MATCH (n {_unit: $m}) RETURN count(n)",
                    org.neo4j.driver.Values.parameters("m", victim)).single().get(0).asLong();
            assertEquals(0L, victimNodes);
        }
        long unitScoped = reduced.nodes.stream().filter(n -> n.props.containsKey("_unit")).count();
        assertEquals(unitScoped, num("MATCH (n) WHERE n._unit IS NOT NULL RETURN count(n)"));
    }
}
