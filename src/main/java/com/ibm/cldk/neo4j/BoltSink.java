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

/**
 * The driver-free seam between the core and the live Bolt writer. {@code BoltWriter} is the only
 * class that imports {@code org.neo4j.driver.*}; everything else (the CLI, {@link Neo4jEmitter})
 * talks to it through this interface and never names {@code BoltWriter} statically.
 *
 * <p>{@link Neo4jEmitter} obtains the implementation reflectively (by a non-constant class name), so:
 * <ul>
 *   <li><b>Fat jar</b> — the Neo4j driver is bundled, reflection succeeds, live push works.</li>
 *   <li><b>GraalVM native image</b> — because nothing references {@code BoltWriter} statically and the
 *       reflective name is not a foldable constant, native-image's reachability analysis prunes
 *       {@code BoltWriter}, so the Neo4j driver and Netty are never compiled into the binary. At
 *       runtime {@code --neo4j-uri} then degrades gracefully to a {@code graph.cypher} snapshot.</li>
 * </ul>
 */
public interface BoltSink {
    void write(GraphRows rows, BoltConfig cfg, boolean fullRun);
}
