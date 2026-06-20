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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.neo4j.BoltWriter.BoltConfig;
import com.ibm.cldk.utils.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * The Neo4j output facade. Two targets, mirroring codeanalyzer-typescript:
 * <ul>
 *   <li>{@code neo4j}: project the IR to a graph. With {@code --neo4j-uri}, push incrementally to a
 *       live DB over Bolt; otherwise write a self-contained {@code graph.cypher} snapshot.</li>
 *   <li>{@code schema}: emit the Neo4j schema contract ({@code schema.json}) — a static artifact
 *       derived from the in-repo catalog, independent of any analyzed project.</li>
 * </ul>
 */
public final class Neo4jEmitter {

    private Neo4jEmitter() {}

    /** Emit the machine-readable schema contract. {@code output == null} prints to stdout. */
    public static void emitSchema(String output) throws IOException {
        String doc = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(SchemaCatalog.buildSchemaDocument()) + "\n";
        if (output == null) {
            System.out.print(doc);
            return;
        }
        Files.createDirectories(Paths.get(output));
        try (FileWriter w = new FileWriter(new File(output, "schema.json"))) {
            w.write(doc);
            Log.done("Neo4j schema contract saved at " + output + File.separator + "schema.json");
        }
    }

    /**
     * Project + emit the Neo4j graph.
     *
     * @param symbolTable the {@code symbol_table} map.
     * @param callGraph   the {@code call_graph} array (level 2), or {@code null}.
     * @param appName     logical application name (null ⇒ derived from input dir).
     * @param input       the analyzed project root (used to derive appName + the cypher output dir).
     * @param output      output directory (null ⇒ cwd for the snapshot).
     * @param targetFiles non-null when an incremental/targeted run was requested.
     * @param bolt        non-null ⇒ push to a live DB over Bolt; null ⇒ write graph.cypher.
     */
    public static void emit(Map<String, JavaCompilationUnit> symbolTable, JsonArray callGraph, String appName,
            String input, String output, boolean targetedRun, BoltConfig bolt) throws IOException {
        String name = appName != null ? appName : deriveAppName(input);
        GraphRows rows = GraphProjector.project(symbolTable, callGraph, name);

        if (bolt != null) {
            Log.info("Pushing graph to Neo4j at " + bolt.uri);
            BoltWriter.write(rows, bolt, !targetedRun); // full run ⇒ orphan pruning is safe
            Log.done("Neo4j graph push complete (" + rows.nodes.size() + " nodes, " + rows.edges.size() + " edges)");
            return;
        }

        String dir = output != null ? output : System.getProperty("user.dir");
        Files.createDirectories(Paths.get(dir));
        try (FileWriter w = new FileWriter(new File(dir, "graph.cypher"))) {
            w.write(CypherWriter.renderCypher(rows, name));
            Log.done("Neo4j graph.cypher saved at " + dir + File.separator + "graph.cypher");
        }
    }

    private static String deriveAppName(String input) {
        if (input == null) {
            return "application";
        }
        Path p = Paths.get(input).toAbsolutePath().normalize();
        Path fileName = p.getFileName();
        return fileName != null ? fileName.toString() : "application";
    }
}
