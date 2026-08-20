package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDecorator;
import com.ibm.cldk.schema.JEnumConstant;
import com.ibm.cldk.schema.JRecordComponent;
import com.ibm.cldk.schema.JType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests the v2 {@link TypeBuilder} — kind, byte-offset span, structured decorators, inheritance. */
class TypeBuilderTest {

    private static final String FILE_KEY = "src/main/java/com/example/Foo.java";

    private static CompilationUnit parse(String source) {
        return TestParsers.parseResolved(source);
    }

    private static JType buildFirstType(String source) {
        CompilationUnit cu = parse(source);
        TypeDeclaration<?> td = cu.getType(0);
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        return new TypeBuilder(ctx).build(td, ctx.moduleId());
    }

    @Test
    void build_setsIdAndClassKind() {
        JType t = buildFirstType("package com.example;\n\npublic class Foo {}\n");
        assertEquals("can://java/myapp/" + FILE_KEY + "/Foo", t.getId());
        assertEquals("class", t.getKind());
    }

    @Test
    void build_derivesKindForInterfaceEnumRecordAnnotation() {
        assertEquals("interface", buildFirstType("package p;\npublic interface I {}\n").getKind());
        assertEquals("enum", buildFirstType("package p;\npublic enum E { A, B }\n").getKind());
        assertEquals("record", buildFirstType("package p;\npublic record R(int x) {}\n").getKind());
        assertEquals("annotation", buildFirstType("package p;\npublic @interface A {}\n").getKind());
    }

    @Test
    void build_capturesJavadocAsOwnComment() {
        JType t = buildFirstType("package p;\n/** A widget. */\nclass Foo {}\n");
        assertEquals(1, t.getComments().size());
        assertTrue(t.getComments().get(0).getContent().contains("A widget."));
        assertTrue(t.getComments().get(0).isJavadoc());
        assertNotNull(t.getComments().get(0).getSpan());
    }

    @Test
    void build_commentsAreOwnNotAllContained() {
        // v1 used getAllContainedComments(), so a type listed every comment inside every member.
        // v2 gives each node only its OWN attached comment.
        JType t = buildFirstType("package p;\n/** Type doc. */\nclass Foo {\n  /** Method doc. */\n  void m() {}\n}\n");
        assertEquals(1, t.getComments().size(), "the method's javadoc belongs to the method, not the type");
        assertTrue(t.getComments().get(0).getContent().contains("Type doc."));
    }

    @Test
    void build_capturesModifiers() {
        // Keystone's type node lists modifiers[] — v1 had them and v2 must not drop them.
        assertEquals(List.of("public", "abstract"),
                buildFirstType("package p;\npublic abstract class Foo {}\n").getModifiers());
    }

    @Test
    void build_capturesInheritance() {
        JType t = buildFirstType("package p;\nclass Foo extends Base implements Runnable {}\n");
        assertEquals(List.of("java.lang.Runnable"), t.getInterfaces(), "resolved to a qualified name");
        assertEquals(List.of("Base"), t.getBaseTypes(), "unresolvable supertype degrades to its spelling");
    }

    @Test
    void build_spanBytesSliceToTheTypeText() {
        String source = "package com.example;\n\npublic class Foo {}\n";
        JType t = buildFirstType(source);
        assertNotNull(t.getSpan());
        int[] bytes = t.getSpan().getBytes();
        assertTrue(source.substring(bytes[0], bytes[1]).contains("class Foo"),
                "span.bytes should slice module source to the type's declaration text");
    }

    @Test
    void build_recursesIntoMemberTypesViaContainment() {
        // Nesting is encoded by containment (member types under the parent's `types`) and the id
        // path — no separate nesting/is_local field.
        String source = "package p;\nclass Outer {\n  class Inner {}\n  enum E { A }\n}\n";
        JType outer = buildFirstType(source);

        assertEquals(Set.of("Inner", "E"), outer.getTypes().keySet());
        assertEquals("enum", outer.getTypes().get("E").getKind());

        JType inner = outer.getTypes().get("Inner");
        assertEquals(outer.getId() + "/Inner", inner.getId());
        assertEquals("class", inner.getKind());
    }

    @Test
    void build_populatesFieldsKeyedBySimpleName() {
        JType t = buildFirstType("package p;\nclass Foo {\n  private int count;\n  String name;\n}\n");
        assertEquals(Set.of("count", "name"), t.getFields().keySet());
        assertEquals("int", t.getFields().get("count").getType());
        assertEquals(t.getId() + "/count", t.getFields().get("count").getId());
    }

    @Test
    void build_populatesCallablesKeyedBySignature() {
        JType t = buildFirstType("package p;\nclass Foo {\n  Foo() {}\n  void inc() {}\n}\n");
        assertTrue(t.getCallables().containsKey("inc()"));
        assertEquals(2, t.getCallables().size(), "constructor + method");
        assertEquals("method", t.getCallables().get("inc()").getKind());
    }

    @Test
    void build_callableRefsSeeEnclosingTypeFields() {
        // TypeBuilder must hand its field names to the callable builder so refs.fields resolves.
        JType t = buildFirstType("package p;\nclass Foo {\n  int count;\n  void inc() { count = count + 1; }\n}\n");
        JCallable inc = t.getCallables().get("inc()");
        assertEquals(List.of("p.Foo.count"), inc.getRefs().getFields(),
                "field refs are qualified by their declaring type");
    }

    @Test
    void build_flagsEntrypointClass() {
        assertTrue(buildFirstType("package p;\n@RestController\nclass Api {}\n").isEntrypointClass(),
                "@RestController is a Spring entrypoint class");
        assertFalse(buildFirstType("package p;\nclass Plain {}\n").isEntrypointClass());
    }

    @Test
    void build_capturesEnumConstants() {
        JType t = buildFirstType("package p;\nenum Color { RED, GREEN(\"g\"); Color() {} Color(String s) {} }\n");
        assertEquals(List.of("RED", "GREEN"),
                t.getEnumConstants().stream().map(JEnumConstant::getName).collect(Collectors.toList()));
        assertEquals(List.of("\"g\""), t.getEnumConstants().get(1).getArguments());
        assertNotNull(t.getEnumConstants().get(0).getSpan());
    }

    @Test
    void build_capturesRecordComponents() {
        JType t = buildFirstType("package p;\nrecord Point(int x, String label) {}\n");
        assertEquals("record", t.getKind());
        assertEquals(List.of("x", "label"),
                t.getRecordComponents().stream().map(JRecordComponent::getName).collect(Collectors.toList()));
        assertEquals("int", t.getRecordComponents().get(0).getType());
        assertEquals("java.lang.String", t.getRecordComponents().get(1).getType(), "resolved like any other type");
    }

    @Test
    void build_capturesVariadicRecordComponent() {
        JType t = buildFirstType("package p;\nrecord Args(String... values) {}\n");
        assertTrue(t.getRecordComponents().get(0).isVariadic());
    }

    @Test
    void build_emitsStaticInitializerAsCallable() {
        // The keystone's callable kinds include `initializer`; L3 needs these to get their own CFGs.
        JType t = buildFirstType("package p;\nclass Foo {\n  static { setup(); }\n}\n");
        JCallable init = t.getCallables().get("<clinit>$0()");
        assertNotNull(init, "static initializer must appear among the type's callables");
        assertEquals("initializer", init.getKind());
        assertEquals(1, init.getBody().size(), "its call sites belong to it, not to any constructor");
    }

    @Test
    void build_emitsInstanceInitializerAsCallable() {
        JType t = buildFirstType("package p;\nclass Foo {\n  { prime(); }\n}\n");
        JCallable init = t.getCallables().get("<instance-init>$0()");
        assertNotNull(init);
        assertEquals("initializer", init.getKind());
    }

    @Test
    void build_numbersMultipleInitializersOfTheSameKind() {
        JType t = buildFirstType("package p;\nclass Foo {\n  static { a(); }\n  static { b(); }\n}\n");
        assertTrue(t.getCallables().containsKey("<clinit>$0()"));
        assertTrue(t.getCallables().containsKey("<clinit>$1()"));
    }

    @Test
    void build_modelsAnonymousClassInAFieldInitializer() {
        // commons-lang's AnnotationUtils does exactly this: an anonymous subclass configured by a
        // double-brace initializer, in a field initializer — outside any callable body.
        JType t = buildFirstType("package p;\nclass Foo {\n"
                + "  static final Runnable R = new Runnable() {\n"
                + "    { setUp(); }\n"
                + "    public void run() { go(); }\n"
                + "  };\n}\n");
        JType anon = t.getTypes().get("$anon$0");
        assertNotNull(anon, "expected the field-initializer anonymous class, got: " + t.getTypes().keySet());
        assertEquals(t.getId() + "/$anon$0", anon.getId());
        assertTrue(anon.getCallables().containsKey("run()"), "its methods belong to it");
        assertNotNull(anon.getCallables().get("<instance-init>$0()"),
                "its double-brace initializer must survive, got: " + anon.getCallables().keySet());
    }

    @Test
    void build_numbersFieldInitializerAnonymousClassesSeparatelyFromNestedTypes() {
        JType t = buildFirstType("package p;\nclass Foo {\n"
                + "  static final Runnable A = new Runnable() { public void run() {} };\n"
                + "  static final Runnable B = new Runnable() { public void run() {} };\n"
                + "  static class Named {}\n}\n");
        assertTrue(t.getTypes().containsKey("$anon$0"));
        assertTrue(t.getTypes().containsKey("$anon$1"));
        assertTrue(t.getTypes().containsKey("Named"), "named nested types are unaffected");
    }

    @Test
    void build_capturesStructuredDecoratorWithArgs() {
        JType t = buildFirstType("package p;\n@SuppressWarnings(\"unchecked\")\nclass Foo {}\n");
        assertEquals(1, t.getDecorators().size());
        JDecorator d = t.getDecorators().get(0);
        assertEquals("SuppressWarnings", d.getName());
        assertEquals(List.of("\"unchecked\""), d.getArgs());
        assertNotNull(d.getSpan());
    }

    @Test
    void build_modelsRecordCompactConstructor() {
        // A CompactConstructorDeclaration extends BodyDeclaration, not CallableDeclaration, so matching
        // only the latter dropped the record's canonical constructor entirely — body, call sites and all.
        JType t = buildFirstType("package p;\nrecord Point(int x, int y) {\n"
                + "  Point {\n    validate(x);\n  }\n}\n");
        JCallable ctor = t.getCallables().get("<init>(int, int)");
        assertNotNull(ctor, "expected the compact constructor, got: " + t.getCallables().keySet());
        assertEquals("constructor", ctor.getKind());
        assertEquals(t.getId() + "/<init>(int, int)", ctor.getId());
        assertNotNull(ctor.getBodySpan());
        assertEquals(1, ctor.getBody().size(), "validate(x) is a call site of the compact constructor");
    }

    @Test
    void build_compactConstructorSignatureMatchesTheCanonicalConstructor() {
        // The signature must come from the record's components, not from the compact constructor's own
        // (empty) parameter list — otherwise it is <init>() and no `new Point(1, 2)` site can join it.
        JType t = buildFirstType("package p;\nimport java.util.List;\n"
                + "record Pair(List<String> names, int count) {\n  Pair {}\n}\n");
        assertTrue(t.getCallables().containsKey("<init>(java.util.List, int)"),
                "got: " + t.getCallables().keySet());
    }

    @Test
    void build_plainRecordDeclaresNoConstructorCallable() {
        // The implicit canonical constructor is not declared in source; L1 reads declarations only.
        JType t = buildFirstType("package p;\nrecord Point(int x, int y) {}\n");
        assertTrue(t.getCallables().isEmpty(), "got: " + t.getCallables().keySet());
    }

    @Test
    void build_nestedAnonymousClassInFieldInitializerIsNotAlsoHoistedToThisType() {
        // The inner anonymous class belongs to the outer one. Without the scope filter it appeared twice
        // — correctly nested, and again hoisted here — double-counting its callables and metrics.
        JType t = buildFirstType("package p;\nclass Foo {\n"
                + "  static final Runnable A = new Runnable() {\n"
                + "    public void run() { r(new Runnable() { public void run() { deep(); } }); }\n"
                + "  };\n}\n");
        assertEquals(Set.of("$anon$0"), t.getTypes().keySet(),
                "only the field's own anonymous class is a member of Foo");
        JType inner = t.getTypes().get("$anon$0").getCallables().get("run()").getTypes().get("$anon$0");
        assertNotNull(inner, "the inner class stays nested under the outer class's run()");
        assertEquals(1, inner.getCallables().get("run()").getBody().size(), "deep() is counted once");
    }

    @Test
    void build_modelsEnumConstantClassBodyAsItsOwnType() {
        // A constant with a body is an anonymous subclass of the enum; leaving it unmodelled dropped its
        // callables from the output entirely, so L2 could not resolve calls into or out of them.
        JType t = buildFirstType("package p;\nenum Op {\n"
                + "  PLUS { int apply(int a) { return helper(a); } },\n  MINUS;\n"
                + "  int helper(int a) { return a; }\n}\n");
        JType plus = t.getTypes().get("$enum$PLUS");
        assertNotNull(plus, "expected a type for PLUS's class body, got: " + t.getTypes().keySet());
        assertEquals(t.getId() + "/$enum$PLUS", plus.getId());
        assertEquals(List.of("p.Op"), plus.getBaseTypes(), "the enum is the supertype it specialises");
        assertEquals(1, plus.getCallables().get("apply(int)").getBody().size(),
                "helper(a) is a call site of PLUS.apply, not of anything on Op");
        assertFalse(t.getTypes().containsKey("$enum$MINUS"), "a constant with no body gets no type");
    }

    @Test
    void build_capturesEnumConstantDecorators() {
        JType t = buildFirstType("package p;\nenum E {\n  @Deprecated OLD,\n  NEW;\n}\n");
        List<JEnumConstant> constants = t.getEnumConstants();
        assertEquals("Deprecated", constants.get(0).getDecorators().get(0).getName());
        assertTrue(constants.get(1).getDecorators().isEmpty());
    }
}
