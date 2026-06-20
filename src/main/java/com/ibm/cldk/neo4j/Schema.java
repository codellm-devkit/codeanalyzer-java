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

import java.util.Arrays;
import java.util.List;

/**
 * The Cypher DDL — uniqueness constraints and indexes — shared by both writers. Run BEFORE any
 * load so MERGE uses an index seek (not a label scan) and the identity invariant is enforced by the
 * database. Every statement is idempotent ({@code IF NOT EXISTS}).
 */
public final class Schema {

    private Schema() {}

    public static final List<String> CONSTRAINTS = Arrays.asList(
            "CREATE CONSTRAINT symbol_id IF NOT EXISTS FOR (s:Symbol) REQUIRE s.id IS UNIQUE",
            "CREATE CONSTRAINT application_name IF NOT EXISTS FOR (a:Application) REQUIRE a.name IS UNIQUE",
            "CREATE CONSTRAINT compilation_unit_key IF NOT EXISTS FOR (c:CompilationUnit) REQUIRE c.file_key IS UNIQUE",
            "CREATE CONSTRAINT package_name IF NOT EXISTS FOR (p:Package) REQUIRE p.name IS UNIQUE",
            "CREATE CONSTRAINT annotation_name IF NOT EXISTS FOR (an:Annotation) REQUIRE an.name IS UNIQUE",
            "CREATE CONSTRAINT callsite_id IF NOT EXISTS FOR (cs:CallSite) REQUIRE cs.id IS UNIQUE",
            "CREATE CONSTRAINT field_id IF NOT EXISTS FOR (f:Field) REQUIRE f.id IS UNIQUE",
            "CREATE CONSTRAINT parameter_id IF NOT EXISTS FOR (p:Parameter) REQUIRE p.id IS UNIQUE",
            "CREATE CONSTRAINT variable_id IF NOT EXISTS FOR (v:Variable) REQUIRE v.id IS UNIQUE",
            "CREATE CONSTRAINT enum_constant_id IF NOT EXISTS FOR (e:EnumConstant) REQUIRE e.id IS UNIQUE",
            "CREATE CONSTRAINT record_component_id IF NOT EXISTS FOR (r:RecordComponent) REQUIRE r.id IS UNIQUE");

    public static final List<String> INDEXES = Arrays.asList(
            "CREATE INDEX callable_name IF NOT EXISTS FOR (c:Callable) ON (c.name)",
            "CREATE INDEX type_name IF NOT EXISTS FOR (t:Type) ON (t.name)",
            "CREATE INDEX annotation_name_idx IF NOT EXISTS FOR (an:Annotation) ON (an.name)",
            "CREATE FULLTEXT INDEX code_fts IF NOT EXISTS FOR (c:Callable) ON EACH [c.code, c.docstring]");
}
