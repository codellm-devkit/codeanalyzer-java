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
            "CREATE CONSTRAINT j_symbol_id IF NOT EXISTS FOR (s:JSymbol) REQUIRE s.id IS UNIQUE",
            "CREATE CONSTRAINT j_application_name IF NOT EXISTS FOR (a:JApplication) REQUIRE a.name IS UNIQUE",
            "CREATE CONSTRAINT j_compilation_unit_key IF NOT EXISTS FOR (c:JCompilationUnit) REQUIRE c.file_key IS UNIQUE",
            "CREATE CONSTRAINT j_package_name IF NOT EXISTS FOR (p:JPackage) REQUIRE p.name IS UNIQUE",
            "CREATE CONSTRAINT j_annotation_name IF NOT EXISTS FOR (an:JAnnotation) REQUIRE an.name IS UNIQUE",
            "CREATE CONSTRAINT j_callsite_id IF NOT EXISTS FOR (cs:JCallSite) REQUIRE cs.id IS UNIQUE",
            "CREATE CONSTRAINT j_field_id IF NOT EXISTS FOR (f:JField) REQUIRE f.id IS UNIQUE",
            "CREATE CONSTRAINT j_parameter_id IF NOT EXISTS FOR (p:JParameter) REQUIRE p.id IS UNIQUE",
            "CREATE CONSTRAINT j_variable_id IF NOT EXISTS FOR (v:JVariable) REQUIRE v.id IS UNIQUE",
            "CREATE CONSTRAINT j_enum_constant_id IF NOT EXISTS FOR (e:JEnumConstant) REQUIRE e.id IS UNIQUE",
            "CREATE CONSTRAINT j_record_component_id IF NOT EXISTS FOR (r:JRecordComponent) REQUIRE r.id IS UNIQUE",
            "CREATE CONSTRAINT j_init_block_id IF NOT EXISTS FOR (ib:JInitializationBlock) REQUIRE ib.id IS UNIQUE",
            "CREATE CONSTRAINT j_crud_operation_id IF NOT EXISTS FOR (co:JCrudOperation) REQUIRE co.id IS UNIQUE",
            "CREATE CONSTRAINT j_crud_query_id IF NOT EXISTS FOR (cq:JCrudQuery) REQUIRE cq.id IS UNIQUE",
            "CREATE CONSTRAINT j_comment_id IF NOT EXISTS FOR (cm:JComment) REQUIRE cm.id IS UNIQUE");

    public static final List<String> INDEXES = Arrays.asList(
            "CREATE INDEX j_callable_name IF NOT EXISTS FOR (c:JCallable) ON (c.name)",
            "CREATE INDEX j_type_name IF NOT EXISTS FOR (t:JType) ON (t.name)",
            "CREATE INDEX j_annotation_name_idx IF NOT EXISTS FOR (an:JAnnotation) ON (an.name)",
            "CREATE FULLTEXT INDEX j_code_fts IF NOT EXISTS FOR (c:JCallable) ON EACH [c.code, c.docstring]");
}
