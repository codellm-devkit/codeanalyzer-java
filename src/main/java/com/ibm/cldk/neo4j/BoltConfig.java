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
 * Bolt connection configuration. Deliberately driver-free (no {@code org.neo4j.driver} import) and
 * separate from {@link BoltSink}/{@code BoltWriter} so the core (CLI, {@link Neo4jEmitter}) can carry
 * connection options without statically referencing the Neo4j driver — which is what lets the GraalVM
 * native image prune the driver + Netty entirely. See {@link BoltSink}.
 */
public final class BoltConfig {
    public final String uri;
    public final String user;
    public final String password;
    public final String database;

    public BoltConfig(String uri, String user, String password, String database) {
        this.uri = uri;
        this.user = user;
        this.password = password;
        this.database = database;
    }
}
