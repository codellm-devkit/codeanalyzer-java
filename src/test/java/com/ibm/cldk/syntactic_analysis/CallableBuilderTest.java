package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.JTypeParameter;
import com.ibm.cldk.schema.JVariableDeclaration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests the v2 {@link CallableBuilder} — signature, params, return/error channel, metrics, refs, body. */
class CallableBuilderTest {

    private static final String FILE_KEY = "src/main/java/com/example/Foo.java";
    private static final String TYPE_ID = "can://java/myapp/" + FILE_KEY + "/Foo";

    private static JCallable build(String memberSource, List<String> classFieldNames) {
        String source = "package com.example;\nimport java.io.IOException;\nimport java.util.*;\n"
                + "class Foo {\n  " + memberSource + "\n}\n";
        CompilationUnit cu = TestParsers.parseResolved(source);
        CallableDeclaration<?> cd = cu.getType(0).findFirst(CallableDeclaration.class).orElseThrow();
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        return new CallableBuilder(ctx).build(cd, TYPE_ID, "com.example.Foo", classFieldNames);
    }

    private static JCallable build(String memberSource) {
        return build(memberSource, List.of());
    }

    @Test
    void build_methodKindSignatureIdAndSpan() {
        JCallable c = build("int add(int a, int b) { return a + b; }");
        assertEquals("method", c.getKind());
        assertEquals("add(int, int)", c.getSignature());
        assertEquals(TYPE_ID + "/add(int, int)", c.getId());
        assertNotNull(c.getSpan());
    }

    @Test
    void build_signatureUsesTypeErasure() {
        // The durable id's last segment: parameter types are RESOLVED and ERASED (type arguments
        // dropped), which is why a symbol solver is required — a syntactic signature would differ.
        JCallable c = build("void m(List<String> xs, String s) {}");
        assertEquals("m(java.util.List, java.lang.String)", c.getSignature());
        assertEquals(TYPE_ID + "/m(java.util.List, java.lang.String)", c.getId());
    }

    @Test
    void build_signatureFallsBackToAstWhenParameterTypeUnresolvable() {
        assertEquals("m(Mystery)", build("void m(Mystery x) {}").getSignature());
    }

    @Test
    void build_constructorKindHasNullReturnType() {
        JCallable c = build("Foo(int x) {}");
        assertEquals("constructor", c.getKind());
        assertNull(c.getReturnType());
        assertTrue(c.getId().startsWith(TYPE_ID + "/"));
    }

    @Test
    void build_capturesParametersReturnTypeModifiersAndDecorators() {
        JCallable c = build("@Override public String greet(String name) { return \"hi\"; }");
        assertEquals(List.of("name"), c.getParameters().stream()
                .map(p -> p.getName()).collect(Collectors.toList()));
        assertEquals("java.lang.String", c.getParameters().get(0).getType());
        assertEquals("java.lang.String", c.getReturnType());
        assertEquals(List.of("public"), c.getModifiers());
        assertEquals("Override", c.getDecorators().get(0).getName());
    }

    @Test
    void build_flagsEntrypointMethod() {
        assertTrue(build("@GetMapping(\"/x\") String get() { return \"\"; }").isEntrypoint(),
                "@GetMapping is a Spring entrypoint method");
        assertFalse(build("String plain() { return \"\"; }").isEntrypoint());
    }

    @Test
    void build_capturesLocalVariables() {
        JCallable c = build("void m() { int total = 0; String name; }");
        assertEquals(List.of("total", "name"),
                c.getLocalVariables().stream().map(JVariableDeclaration::getName).collect(Collectors.toList()));
        assertEquals("int", c.getLocalVariables().get(0).getType());
        assertEquals("0", c.getLocalVariables().get(0).getInitializer());
        assertNull(c.getLocalVariables().get(1).getInitializer(), "uninitialized -> absent");
        assertNotNull(c.getLocalVariables().get(0).getSpan());
    }

    @Test
    void build_localVariablesExcludeThoseInNestedLocalClasses() {
        JCallable c = build("void m() { int mine = 1; class Local { void inner() { int theirs = 2; } } }");
        assertEquals(List.of("mine"),
                c.getLocalVariables().stream().map(JVariableDeclaration::getName).collect(Collectors.toList()));
    }

    @Test
    void build_capturesJavadocComment() {
        JCallable c = build("/** Adds two numbers. */\n  int add(int a, int b) { return a + b; }");
        assertEquals(1, c.getComments().size());
        assertTrue(c.getComments().get(0).getContent().contains("Adds two numbers."));
        assertTrue(c.getComments().get(0).isJavadoc());
    }

    @Test
    void build_capturesDeclarationString() {
        // The signature-with-names text v1 exposed as `declaration` (useful verbatim in LLM prompts);
        // it is not recoverable from span.bytes, which covers the body too.
        JCallable c = build("public int add(int a, int b) { return a + b; }");
        assertEquals("public int add(int a, int b)", c.getDeclaration());
    }

    @Test
    void build_bodySpanStartIsTheBodysFirstLine() {
        // v1's `code_start_line` is exactly body_span.start[0], so it is not emitted separately (D1).
        // "class Foo {" is line 4 of the wrapper, so the member starts on line 5.
        JCallable c = build("void m() {\n    x();\n  }");
        assertEquals(5, c.getBodySpan().getStart()[0]);
    }

    @Test
    void build_abstractMethodHasNoBodySpan() {
        // Absence encodes "no fact" (D10) — where v1 had to assert code_start_line = -1.
        assertNull(build("abstract void m();").getBodySpan());
    }

    @Test
    void build_capturesErrorChannelFromThrows() {
        JCallable c = build("void read() throws IOException, RuntimeException {}");
        assertEquals(List.of("java.io.IOException", "java.lang.RuntimeException"), c.getErrorChannel());
    }

    @Test
    void build_computesCyclomaticMetric() {
        JCallable c = build("void m(int x) { if (x > 0) { } }");
        assertEquals(2, c.getMetrics().getCyclomatic());
    }

    @Test
    void build_refsTypesIncludeCastsInstanceofAndCatchTypes() {
        // v1 only scanned variable declarators and object creations; a cast/instanceof/catch type is
        // just as much a referenced type.
        JCallable c = build("void m(Object o) { try { String s = (String) o; if (o instanceof Integer) {} }"
                + " catch (IllegalStateException e) {} }");
        assertTrue(c.getRefs().getTypes().contains("java.lang.String"));
        assertTrue(c.getRefs().getTypes().contains("java.lang.Integer"), "instanceof type");
        assertTrue(c.getRefs().getTypes().contains("java.lang.IllegalStateException"), "catch type");
    }

    @Test
    void build_cyclomaticMetricExcludesNestedAnonymousClassBranches() {
        // Every other metric is scope-filtered; complexity must be too, or the branches of a nested
        // class are counted twice — once on it and once on the method that merely declares it.
        JCallable c = build("void m(boolean p) { Runnable r = new Runnable() {"
                + " public void run() { if (p) {} if (!p) {} } }; }");
        assertEquals(1, c.getMetrics().getCyclomatic(), "m() itself branches nowhere");
        assertEquals(3, c.getTypes().get("$anon$0").getCallables().get("run()")
                .getMetrics().getCyclomatic(), "the two ifs belong to run()");
    }

    @Test
    void build_capturesBodyCallNodes() {
        JCallable c = build("void m() { foo(); }");
        assertEquals(1, c.getBody().size());
        assertEquals("call", c.getBody().values().iterator().next().getKind());
    }

    @Test
    void build_capturesRefsTypesAndAccessedFields() {
        JCallable c = build("void m() { Helper h = new Helper(); this.count = h.value(); }", List.of("count"));
        assertTrue(c.getRefs().getTypes().contains("Helper"),
                "referenced types should include the syntactic type Helper");
        assertEquals(List.of("com.example.Foo.count"), c.getRefs().getFields());
    }

    @Test
    void build_capturesLocalClassUnderCallableTypesViaContainment() {
        JCallable c = build("void m() { class Local {} }");
        assertTrue(c.getTypes().containsKey("Local"));
        assertEquals(c.getId() + "/Local", c.getTypes().get("Local").getId());
    }

    @Test
    void build_modelsAnonymousClassAsTypeUnderTheCallable() {
        // v1 recursed into anonymous bodies and mis-attributed their members to the enclosing type;
        // dropping them instead loses real facts, so they get their own node like a local class does.
        JCallable c = build("void m() { Runnable r = new Runnable() { public void run() { log(); } }; }");
        JType anon = c.getTypes().get("$anon$0");
        assertNotNull(anon, "expected an anonymous-class type node, got: " + c.getTypes().keySet());
        assertEquals("class", anon.getKind());
        assertEquals(c.getId() + "/$anon$0", anon.getId());
        assertEquals(List.of("java.lang.Runnable"), anon.getInterfaces(),
                "an anonymous class implementing an interface records it under interfaces");
        assertTrue(anon.getCallables().containsKey("run()"), "its methods are its own callables");
        assertEquals(1, anon.getCallables().get("run()").getBody().size(),
                "log() belongs to the anonymous class's run(), not to m()");
    }

    @Test
    void build_anonymousClassCallsAreNotAttributedToTheEnclosingCallable() {
        JCallable c = build("void m() { outer(); Runnable r = new Runnable() { public void run() { hidden(); } }; }");
        // m()'s own body holds outer() and the `new Runnable()` constructor call, but never hidden().
        assertEquals(2, c.getBody().size(), "got: " + c.getBody().keySet());
        assertTrue(c.getBody().values().stream().noneMatch(n -> "hidden".equals(n.getCalleeSignature())));
    }

    @Test
    void build_capturesAnonymousInstanceInitializerDoubleBraceIdiom() {
        // The idiom spring-petclinic uses: new PetType() {{ setName("Dog"); }}
        JCallable c = build("void m() { Object o = new Object() { { setUp(); } }; }");
        JType anon = c.getTypes().get("$anon$0");
        assertNotNull(anon);
        JCallable init = anon.getCallables().get("<instance-init>$0()");
        assertNotNull(init, "the double-brace initializer must survive as a callable, got: "
                + anon.getCallables().keySet());
        assertEquals("initializer", init.getKind());
        assertEquals(1, init.getBody().size(), "setUp() belongs to the anonymous initializer");
    }

    @Test
    void build_numbersMultipleAnonymousClassesInDeclarationOrder() {
        JCallable c = build("void m() { r(new Runnable() { public void run() {} });"
                + " r(new Runnable() { public void run() {} }); }");
        assertTrue(c.getTypes().containsKey("$anon$0"));
        assertTrue(c.getTypes().containsKey("$anon$1"));
    }

    @Test
    void build_abstractMethodHasEmptyBody() {
        JCallable c = build("abstract void m();");
        assertTrue(c.getBody().isEmpty());
    }

    @Test
    void build_signatureQualifiesResolvableParametersEvenWhenAnotherIsUnresolvable() {
        // The signature must stay joinable against a call site's callee_signature, which always
        // qualifies. Degrading the *whole* signature to AST spellings because one parameter failed
        // produced m(List, Mystery) against the call side's m(java.util.List, Mystery) — never joinable,
        // so L2 would silently drop the edge.
        assertEquals("m(java.util.List, Mystery)", build("void m(List<String> xs, Mystery y) {}").getSignature());
    }

    @Test
    void build_initializerErrorChannelRecordsWhatItThrows() {
        // An initializer block cannot declare `throws`, so its error channel is what it actually throws
        // (the fact v1 carried as InitializationBlock.thrownExceptions).
        String source = "package com.example;\nimport java.io.IOException;\nimport java.util.*;\n"
                + "class Foo {\n  static { if (true) throw new IllegalStateException(\"x\"); }\n}\n";
        CompilationUnit cu = TestParsers.parseResolved(source);
        var id = cu.getType(0).findFirst(com.github.javaparser.ast.body.InitializerDeclaration.class).orElseThrow();
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        JCallable c = new CallableBuilder(ctx)
                .buildInitializer(id, TYPE_ID, "com.example.Foo", List.of(), "<clinit>$0()");
        assertEquals(List.of("java.lang.IllegalStateException"), c.getErrorChannel());
    }

    @Test
    void build_capturesMethodTypeParametersWithTheirBounds() {
        // Without these the bound is unrecoverable: the parameter's `type` is the bare variable name, and
        // `declaration` omits the `<V extends Number>` clause because JavaParser renders it that way.
        JCallable c = build("<V extends Number> V pick(V v) { return v; }");
        assertEquals(List.of("V"),
                c.getTypeParameters().stream().map(JTypeParameter::getName).collect(Collectors.toList()));
        assertEquals(List.of("java.lang.Number"), c.getTypeParameters().get(0).getBounds());
        assertEquals("V", c.getParameters().get(0).getType(), "a type variable has no qualified name");
        assertFalse(c.getDeclaration().contains("extends Number"),
                "`declaration` drops the clause, which is precisely why type_parameters exists");
    }

    @Test
    void build_nonGenericMethodHasNoTypeParameters() {
        assertTrue(build("int add(int a, int b) { return a + b; }").getTypeParameters().isEmpty());
    }

    @Test
    void build_genericConstructorCarriesItsOwnTypeParameters() {
        assertEquals(List.of("T"),
                build("<T> Foo(T seed) {}").getTypeParameters().stream()
                        .map(JTypeParameter::getName).collect(Collectors.toList()));
    }

    @Test
    void build_signatureIgnoresTypeParametersSoItStaysJoinable() {
        // The erased signature is the containment key. A type variable erases to its bound, and the
        // clause must not leak into the id or a call site could never join it.
        JCallable c = build("<V extends Number> void take(V v) {}");
        assertEquals("take(java.lang.Number)", c.getSignature());
    }
}
