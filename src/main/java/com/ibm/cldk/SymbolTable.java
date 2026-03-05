package com.ibm.cldk;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.ibm.cldk.entities.*;
import com.ibm.cldk.javaee.CRUDFinderFactory;
import com.ibm.cldk.javaee.EntrypointsFinderFactory;
import com.ibm.cldk.javaee.utils.enums.CRUDOperationType;
import com.ibm.cldk.javaee.utils.enums.CRUDQueryType;
import com.ibm.cldk.utils.Log;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.tuple.Pair;

@SuppressWarnings({"unchecked", "rawtypes"})
public class SymbolTable {

    private static JavaSymbolSolver javaSymbolSolver;
    private static Set<String> unresolvedTypes = new HashSet<>();
    private static Set<String> unresolvedExpressions = new HashSet<>();

    /**
     * Processes the given compilation unit to extract information about classes
     * and interfaces declared in the unit and returns a JSON object containing
     * the extracted information.
     *
     * @param parseResult compilation unit to be processed
     * @return JSON object containing extracted information
     */
    // Let's store the known callables here for future use.
    public static Table<String, String, Callable> declaredMethodsAndConstructors = Tables
            .newCustomTable(new HashMap<>(), () -> new HashMap<>() {
                @Override
                public Callable get(Object key) {
                    if (key instanceof String) {
                        Optional<Entry<String, Callable>> matchingEntry = this.entrySet().stream()
                                .filter(entry -> isMethodSignatureMatch((String) key, entry.getKey())).findFirst();
                        if (matchingEntry.isPresent()) {
                            return matchingEntry.get().getValue();
                        }
                    }
                    return super.get(key);
                }

                private boolean isMethodSignatureMatch(String fullSignature, String searchSignature) {
                    String methodName = fullSignature.split("\\(")[0];
                    String searchMethodName = searchSignature.split("\\(")[0];

                    // Check method name match
                    if (!methodName.equals(searchMethodName)) {
                        return false;
                    }

                    // Extract parameters, split by comma, and trim
                    String[] fullParams = fullSignature
                            .substring(fullSignature.indexOf("(") + 1, fullSignature.lastIndexOf(")")).split(",");
                    String[] searchParams = searchSignature
                            .substring(searchSignature.indexOf("(") + 1, searchSignature.lastIndexOf(")")).split(",");

                    // Allow matching with fewer search parameters
                    if (searchParams.length != fullParams.length) {
                        return false;
                    }

                    return IntStream.range(0, searchParams.length).allMatch(i -> {
                        String fullParamTrimmed = fullParams[i].trim();
                        String searchParamTrimmed = searchParams[i].trim();
                        return fullParamTrimmed.endsWith(searchParamTrimmed);
                    });
                }
            });

    private static JavaCompilationUnit processCompilationUnit(CompilationUnit parseResult) {
        JavaCompilationUnit cUnit = new JavaCompilationUnit();

        cUnit.setFilePath(parseResult.getStorage().map(s -> s.getPath().toString()).orElse("<in-memory>"));

        // Set file level comment
        parseResult.getAllComments().stream().findFirst().ifPresent(c -> {
            com.ibm.cldk.entities.Comment fileComment = new com.ibm.cldk.entities.Comment();
            fileComment.setContent(c.getContent());
            fileComment.setStartLine(c.getRange().isPresent() ? c.getRange().get().begin.line : -1);
            fileComment.setEndLine(c.getRange().isPresent() ? c.getRange().get().end.line : -1);
            fileComment.setStartColumn(c.getRange().isPresent() ? c.getRange().get().begin.column : -1);
            fileComment.setEndColumn(c.getRange().isPresent() ? c.getRange().get().end.column : -1);
            fileComment.setJavadoc(c.isJavadocComment());
            cUnit.getComments().add(fileComment);
        });

        // Add class comment
        cUnit.setComments(
                parseResult.getAllComments().stream().map(c -> {
                            com.ibm.cldk.entities.Comment fileComment = new com.ibm.cldk.entities.Comment();
                            fileComment.setContent(c.getContent());
                            fileComment.setStartLine(c.getRange().isPresent() ? c.getRange().get().begin.line : -1);
                            fileComment.setEndLine(c.getRange().isPresent() ? c.getRange().get().end.line : -1);
                            fileComment.setStartColumn(c.getRange().isPresent() ? c.getRange().get().begin.column : -1);
                            fileComment.setEndColumn(c.getRange().isPresent() ? c.getRange().get().end.column : -1);
                            fileComment.setJavadoc(c.isJavadocComment());
                            return fileComment;
                        })
                        .collect(Collectors.toList()));

        // Set package name
        cUnit.setPackageName(parseResult.getPackageDeclaration().map(NodeWithName::getNameAsString).orElse(""));
        // Add javadoc comment
        // Add imports
        cUnit.setImports(
                parseResult.getImports().stream().map(importDecl -> {
                    Import importNode = new Import();
                    importNode.setPath(importDecl.getNameAsString());
                    importNode.setStatic(importDecl.isStatic());
                    importNode.setWildcard(importDecl.isAsterisk());
                    return importNode;
                }).collect(Collectors.toList()));

        // create array node for type declarations
        cUnit.setTypeDeclarations(parseResult.findAll(TypeDeclaration.class).stream()
                .filter(typeDecl -> typeDecl.getFullyQualifiedName().isPresent()).map(typeDecl -> {
                    // get type name and initialize the type object
                    String typeName = typeDecl.getFullyQualifiedName().get().toString();
                    com.ibm.cldk.entities.Type typeNode = new com.ibm.cldk.entities.Type();

                    if (typeDecl instanceof ClassOrInterfaceDeclaration) {
                        ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) typeDecl;

                        // Add interfaces implemented by class
                        typeNode.setImplementsList(classDecl.getImplementedTypes().stream()
                                .map(SymbolTable::resolveType).collect(Collectors.toList()));

                        // Add class modifiers
                        typeNode.setModifiers(classDecl.getModifiers().stream().map(m -> m.toString().strip())
                                .collect(Collectors.toList()));

                        // Add class annotations
                        typeNode.setAnnotations(classDecl.getAnnotations().stream().map(a -> a.toString().strip())
                                .collect(Collectors.toList()));

                        // add booleans indicating interfaces and inner/local classes
                        typeNode.setInterface(classDecl.isInterface());
                        typeNode.setInnerClass(classDecl.isInnerClass());
                        typeNode.setLocalClass(classDecl.isLocalClassDeclaration());

                        // Add extends
                        typeNode.setExtendsList(classDecl.getExtendedTypes().stream().map(SymbolTable::resolveType)
                                .collect(Collectors.toList()));

                    } else if (typeDecl instanceof EnumDeclaration) {
                        EnumDeclaration enumDecl = (EnumDeclaration) typeDecl;

                        // Add interfaces implemented by enum
                        typeNode.setImplementsList(enumDecl.getImplementedTypes().stream().map(SymbolTable::resolveType)
                                .collect(Collectors.toList()));

                        // Add enum modifiers
                        typeNode.setModifiers(enumDecl.getModifiers().stream().map(m -> m.toString().strip())
                                .collect(Collectors.toList()));

                        // Add enum annotations
                        typeNode.setAnnotations(enumDecl.getAnnotations().stream().map(a -> a.toString().strip())
                                .collect(Collectors.toList()));

                        // Add enum constants
                        typeNode.setEnumConstants(enumDecl.getEntries().stream()
                                .map(SymbolTable::processEnumConstantDeclaration).collect(Collectors.toList()));
                    } else if (typeDecl instanceof RecordDeclaration) {
                        RecordDeclaration recordDecl = (RecordDeclaration) typeDecl;

                        // Set that this is a record declaration
                        typeNode.setRecordDeclaration(typeDecl.isRecordDeclaration());

                        // Add interfaces implemented by record
                        typeNode.setImplementsList(recordDecl.getImplementedTypes().stream()
                                .map(SymbolTable::resolveType).collect(Collectors.toList()));

                        // Add record modifiers
                        typeNode.setModifiers(recordDecl.getModifiers().stream().map(m -> m.toString().strip())
                                .collect(Collectors.toList()));

                        // Add record annotations
                        typeNode.setAnnotations(recordDecl.getAnnotations().stream().map(a -> a.toString().strip())
                                .collect(Collectors.toList()));

                        // Add record components
                        typeNode.setRecordComponents(processRecordComponents(recordDecl));
                    } else {
                        // TODO: handle AnnotationDeclaration, RecordDeclaration
                        // set the common type attributes only
                        typeNode = new com.ibm.cldk.entities.Type();
                    }

                    /*
                     * set common attributes of types that available in type declarations:
                     * is nested type, is class or interface declaration, is enum declaration,
                     * comments, parent class, callable declarations, field declarations
                     */
                    // Discover initialization blocks
                    typeNode.setInitializationBlocks(typeDecl.findAll(InitializerDeclaration.class).stream()
                            .map(initializerDeclaration -> {
                                return createInitializationBlock(initializerDeclaration, parseResult.getStorage()
                                        .map(s -> s.getPath().toString()).orElse("<in-memory>"));
                            })
                            .collect(Collectors.toList()));
                    // Set fields indicating nested, class/interface, enum, annotation, and record
                    // types
                    typeNode.setNestedType(typeDecl.isNestedType());
                    typeNode.setClassOrInterfaceDeclaration(typeDecl.isClassOrInterfaceDeclaration());
                    typeNode.setEnumDeclaration(typeDecl.isEnumDeclaration());
                    typeNode.setAnnotationDeclaration(typeDecl.isAnnotationDeclaration());

                    // Add class comment
                    typeNode.setComments(
                            typeDecl.getAllContainedComments().stream()
//                            .filter(c -> c.getParentNode().isEmpty() || (c.getParentNode().isPresent() && parseResult.getPrimaryType().get().equals(c.getCommentedNode().get())))
                            .map(c -> {
                                com.ibm.cldk.entities.Comment typeNodeComment = new com.ibm.cldk.entities.Comment();
                                typeNodeComment.setContent(c.getContent());
                                typeNodeComment.setStartLine(c.getRange().isPresent() ? c.getRange().get().begin.line : -1);
                                typeNodeComment.setEndLine(c.getRange().isPresent() ? c.getRange().get().end.line : -1);
                                typeNodeComment.setStartColumn(c.getRange().isPresent() ? c.getRange().get().begin.column : -1);
                                typeNodeComment.setEndColumn(c.getRange().isPresent() ? c.getRange().get().end.column : -1);
                                typeNodeComment.setJavadoc(c.isJavadocComment());
                                return typeNodeComment;
                            })
                            .collect(Collectors.toList()));

                    // Get JavaDoc comments
                    // Check to see if there is a java doc comment if so, add it to the comments list
                    if (getJavadoc(typeDecl).isPresent()) {
                        typeNode.getComments().add(getJavadoc(typeDecl).get());
                    }

                    // add parent class (for nested type declarations)
                    typeNode.setParentType(typeDecl.getParentNode().get() instanceof TypeDeclaration
                            ? ((TypeDeclaration<TypeDeclaration<?>>) typeDecl.getParentNode().get())
                                    .getFullyQualifiedName().get()
                            : "");

                    typeNode.setNestedTypeDeclarations(typeDecl.findAll(TypeDeclaration.class).stream()
                            .filter(typ -> typ.isClassOrInterfaceDeclaration() || typ.isEnumDeclaration())
                            .filter(typ -> typ.getParentNode().isPresent() && typ.getParentNode().get() == typeDecl)
                            .map(typ -> typ.getFullyQualifiedName().get().toString()).collect(Collectors.toList()));

                    // Add information about declared fields (filtering to fields declared in the
                    // type, not in a nested type)
                    typeNode.setFieldDeclarations(typeDecl.findAll(FieldDeclaration.class).stream()
                            .filter(f -> f.getParentNode().isPresent() && f.getParentNode().get() == typeDecl)
                            .map(SymbolTable::processFieldDeclaration).collect(Collectors.toList()));
                    List<String> fieldNames = new ArrayList<>();
                    typeNode.getFieldDeclarations().stream().map(Field::getVariables).forEach(fieldNames::addAll);

                    // Add information about declared methods (filtering to methods declared in the
                    // class, not in a nested class)
                    typeNode.setCallableDeclarations(typeDecl.findAll(CallableDeclaration.class).stream()
                            .filter(c -> c.getParentNode().isPresent() && c.getParentNode().get() == typeDecl)
                            .map(meth -> {
                                Pair<String, Callable> callableDeclaration = processCallableDeclaration(meth,
                                        fieldNames, typeName, parseResult.getStorage().map(s -> s.getPath().toString())
                                                .orElse("<in-memory>"));
                                declaredMethodsAndConstructors.put(typeName, callableDeclaration.getLeft(),
                                        callableDeclaration.getRight());
                                return callableDeclaration;
                            }).collect(Collectors.toMap(p -> p.getLeft(), p -> p.getRight())));

                    // Add information about if the TypeNode is an entry point class
                    typeNode.setEntrypointClass(isEntryPointClass(typeDecl));

                    return Pair.of(typeName, typeNode);

                }).collect(Collectors.toMap(p -> p.getLeft(), p -> p.getRight())));

        return cUnit;
    }

    private static InitializationBlock createInitializationBlock(InitializerDeclaration initializerDeclaration,
            String filePath) {
        InitializationBlock initializationBlock = new InitializationBlock();
        initializationBlock.setFilePath(filePath);

        com.ibm.cldk.entities.Comment comment = new com.ibm.cldk.entities.Comment();

        // Add class comment
        initializationBlock.setComments(
                initializerDeclaration.getAllContainedComments().stream()
                        .map(c -> {
                            com.ibm.cldk.entities.Comment typeNodeComment = new com.ibm.cldk.entities.Comment();
                            typeNodeComment.setContent(c.getContent());
                            typeNodeComment.setStartLine(c.getRange().isPresent() ? c.getRange().get().begin.line : -1);
                            typeNodeComment.setEndLine(c.getRange().isPresent() ? c.getRange().get().end.line : -1);
                            typeNodeComment.setStartColumn(c.getRange().isPresent() ? c.getRange().get().begin.column : -1);
                            typeNodeComment.setEndColumn(c.getRange().isPresent() ? c.getRange().get().end.column : -1);
                            typeNodeComment.setJavadoc(c.isJavadocComment());
                            return typeNodeComment;
                        })
                        .collect(Collectors.toList()));

        // Check to see if there is a java doc comment if so, add it to the comments list
        getJavadoc(initializerDeclaration).ifPresent(value -> initializationBlock.getComments().add(value));


        // Set annotations
        initializationBlock.setAnnotations(initializerDeclaration.getAnnotations().stream()
                .map(a -> a.toString().strip()).collect(Collectors.toList()));
        // add exceptions declared in "throws" clause
        initializationBlock.setThrownExceptions(initializerDeclaration.getBody().getStatements().stream()
                .filter(Statement::isThrowStmt).map(throwStmt -> {
                    try {
                        return javaSymbolSolver.calculateType(throwStmt.asThrowStmt().getExpression()).describe();
                    } catch (Exception e) {
                        return throwStmt.asThrowStmt().getExpression().toString();
                    }
                }).collect(Collectors.toList()));
        initializationBlock.setCode(LexicalPreservingPrinter.print(initializerDeclaration.getBody()));
        initializationBlock.setStartLine(
                initializerDeclaration.getRange().isPresent() ? initializerDeclaration.getRange().get().begin.line
                        : -1);
        initializationBlock.setEndLine(
                initializerDeclaration.getRange().isPresent() ? initializerDeclaration.getRange().get().end.line : -1);
        initializationBlock.setStatic(initializerDeclaration.isStatic());
        initializationBlock
                .setReferencedTypes(getReferencedTypes(Optional.ofNullable(initializerDeclaration.getBody())));
        initializationBlock.setAccessedFields(
                getAccessedFields(Optional.ofNullable(initializerDeclaration.getBody()), Collections.emptyList(), ""));
        initializationBlock.setCallSites(getCallSites(Optional.ofNullable(initializerDeclaration.getBody())));
        initializationBlock.setVariableDeclarations(
                getVariableDeclarations(Optional.ofNullable(initializerDeclaration.getBody())));
        initializationBlock.setCyclomaticComplexity(getCyclomaticComplexity(initializerDeclaration));
        return initializationBlock;
    }

    private static Optional<com.ibm.cldk.entities.Comment> getJavadoc(NodeWithJavadoc bodyDeclaration) {
        Optional<JavadocComment> javadocComment = bodyDeclaration.getJavadocComment();
        if (!javadocComment.isPresent()) {
            return Optional.empty();
        }
        com.ibm.cldk.entities.Comment javadoc = new com.ibm.cldk.entities.Comment();
        javadoc.setContent(javadocComment.get().getContent().isEmpty() || javadocComment.get().getContent().isBlank() ? "" : javadocComment.get().getContent());
        javadoc.setStartLine(javadocComment.get().getRange().get().begin.line);
        javadoc.setEndLine(javadocComment.get().getRange().get().end.line);
        javadoc.setStartColumn(javadocComment.get().getRange().get().begin.column);
        javadoc.setEndColumn(javadocComment.get().getRange().get().end.column);
        javadoc.setJavadoc(!(javadocComment.get().getContent().isEmpty() && javadocComment.get().getContent().isBlank()));
        return Optional.of(javadoc);
    }

    /**
     * Processes the given record to extract information about the
     * declared field and returns a JSON object containing the extracted
     * information.
     *
     * @param recordDecl field declaration to be processed
     * @return Field object containing extracted information
     */
    private static List<RecordComponent> processRecordComponents(RecordDeclaration recordDecl) {
        return recordDecl.getParameters().stream().map(
                parameter -> {
                    RecordComponent recordComponent = new RecordComponent();
                    com.ibm.cldk.entities.Comment comment = new com.ibm.cldk.entities.Comment();
                    if (parameter.getComment().isPresent()) {
                        Comment parsedComment = parameter.getComment().get();
                        comment.setContent(parsedComment.getContent());
                        parsedComment.getRange().ifPresent(range -> {
                            comment.setStartLine(range.begin.line);
                            comment.setEndLine(range.end.line);
                            comment.setStartColumn(range.begin.column);
                            comment.setEndColumn(range.end.column);
                        });

                    } else {
                        comment.setContent("");
                        comment.setStartLine(-1);
                        comment.setEndLine(-1);
                        comment.setStartColumn(-1);
                        comment.setEndColumn(-1);
                    }

                    recordComponent.setComment(comment);
                    recordComponent.setName(parameter.getNameAsString());
                    recordComponent.setType(resolveType(parameter.getType()));
                    recordComponent.setAnnotations(parameter.getAnnotations().stream().map(a -> a.toString().strip())
                            .collect(Collectors.toList()));
                    recordComponent.setModifiers(parameter.getModifiers().stream().map(a -> a.toString().strip())
                            .collect(Collectors.toList()));
                    recordComponent.setVarArgs(parameter.isVarArgs());
                    recordComponent.setDefaultValue(
                            mapRecordConstructorDefaults(recordDecl).getOrDefault(parameter.getNameAsString(), null));
                    return recordComponent;
                }).collect(Collectors.toList());
    }

    private static Map<String, Object> mapRecordConstructorDefaults(RecordDeclaration recordDecl) {

        return recordDecl.getCompactConstructors().stream()
                .flatMap(constructor -> constructor.findAll(AssignExpr.class).stream()) // Flatten all assignments
                .filter(assignExpr -> assignExpr.getTarget().isNameExpr()) // Ensure assignment is to a parameter
                .collect(Collectors.toMap(
                        assignExpr -> assignExpr.getTarget().asNameExpr().getNameAsString(), // Key: Parameter Name
                        assignExpr -> Optional.ofNullable(assignExpr.getValue()).map(valueExpr -> { // Value: Default
                                                                                                    // Value
                            return valueExpr.isStringLiteralExpr() ? valueExpr.asStringLiteralExpr().asString()
                                    : valueExpr.isBooleanLiteralExpr() ? valueExpr.asBooleanLiteralExpr().getValue()
                                            : valueExpr.isCharLiteralExpr() ? valueExpr.asCharLiteralExpr().getValue()
                                                    : valueExpr.isDoubleLiteralExpr()
                                                            ? valueExpr.asDoubleLiteralExpr().asDouble()
                                                            : valueExpr.isIntegerLiteralExpr()
                                                                    ? valueExpr.asIntegerLiteralExpr().asNumber()
                                                                    : valueExpr.isLongLiteralExpr()
                                                                            ? valueExpr.asLongLiteralExpr().asNumber()
                                                                            : valueExpr.isNullLiteralExpr() ? null
                                                                                    : valueExpr.toString();
                        }).orElse("null"))); // Default: store as a string
    }

    private static boolean isEntryPointClass(TypeDeclaration typeDecl) {
        return EntrypointsFinderFactory.getEntrypointFinders()
                .anyMatch(finder -> finder.isEntrypointClass(typeDecl));
    }

    /**
     * Process enum constant declaration.
     *
     * @param enumConstDecl enum constant declaration to be processed
     * @return EnumConstant object containing extracted information
     */
    private static EnumConstant processEnumConstantDeclaration(EnumConstantDeclaration enumConstDecl) {
        EnumConstant enumConstant = new EnumConstant();

        // add enum constant name
        enumConstant.setName(enumConstDecl.getNameAsString());

        // add enum constant arguments
        enumConstant.setArguments(
                enumConstDecl.getArguments().stream().map(Node::toString).collect(Collectors.toList()));

        return enumConstant;
    }

    /**
     * Process parameter declarations on callables.
     *
     * @param paramDecl parameter declaration to be processed
     */
    private static ParameterInCallable processParameterDeclaration(Parameter paramDecl) {
        ParameterInCallable parameter = new ParameterInCallable();
        parameter.setType(resolveType(paramDecl.getType()));
        parameter.setName(paramDecl.getName().toString());
        parameter.setAnnotations(
                paramDecl.getAnnotations().stream().map(a -> a.toString().strip()).collect(Collectors.toList()));
        parameter.setModifiers(
                paramDecl.getModifiers().stream().map(a -> a.toString().strip()).collect(Collectors.toList()));
        parameter.setStartLine(paramDecl.getRange().isPresent() ? paramDecl.getRange().get().begin.line : -1);
        parameter.setStartColumn(paramDecl.getRange().isPresent() ? paramDecl.getRange().get().begin.column : -1);
        parameter.setEndLine(paramDecl.getRange().isPresent() ? paramDecl.getRange().get().end.line : -1);
        parameter.setEndColumn(paramDecl.getRange().isPresent() ? paramDecl.getRange().get().end.column : -1);
        return parameter;
    }

    /**
     * Processes the given callable declaration to extract information about the
     * declared method or constructor and returns a JSON object containing the
     * extracted information.
     *
     * @param callableDecl callable (method or constructor) to be processed
     * @return Callable object containing extracted information
     */
    @SuppressWarnings("unchecked")
    private static Pair<String, Callable> processCallableDeclaration(CallableDeclaration callableDecl,
            List<String> classFields, String typeName, String filePath) {
        Callable callableNode = new Callable();

        // Set file path
        callableNode.setFilePath(filePath);

        // add callable signature
        callableNode.setSignature(getTypeErasureSignature(callableDecl));

        // add comment associated with method/constructor
        callableNode.setComments(
                callableDecl.getAllContainedComments().stream()
                        .map(c -> {
                            com.ibm.cldk.entities.Comment methodComment = new com.ibm.cldk.entities.Comment();
                            methodComment.setContent(c.getContent());
                            methodComment.setStartLine(c.getRange().isPresent() ? c.getRange().get().begin.line : -1);
                            methodComment.setEndLine(c.getRange().isPresent() ? c.getRange().get().end.line : -1);
                            methodComment.setStartColumn(c.getRange().isPresent() ? c.getRange().get().begin.column : -1);
                            methodComment.setEndColumn(c.getRange().isPresent() ? c.getRange().get().end.column : -1);
                            methodComment.setJavadoc(c.isJavadocComment());
                            return methodComment;
                        })
                        .collect(Collectors.toList()));

        // Check to see if there are JavaDoc comments
        getJavadoc(callableDecl).ifPresent(value -> callableNode.getComments().add(value));

        // add annotations on method/constructor
        callableNode.setAnnotations((List<String>) callableDecl.getAnnotations().stream()
                .map(mod -> mod.toString().strip()).collect(Collectors.toList()));

        // add method or constructor modifiers
        callableNode.setModifiers((List<String>) callableDecl.getModifiers().stream().map(mod -> mod.toString().strip())
                .collect(Collectors.toList()));

        // add exceptions declared in "throws" clause
        callableNode.setThrownExceptions(((NodeList<ReferenceType>) callableDecl.getThrownExceptions()).stream()
                .map(SymbolTable::resolveType).collect(Collectors.toList()));

        // add the complete declaration string, including modifiers, throws, and
        // parameter names
        callableNode
                .setDeclaration(callableDecl.getDeclarationAsString(true, true, true).strip().replaceAll("//.*\n", ""));

        // add information about callable parameters: for each parameter, type, name,
        // annotations,
        // modifiers
        callableNode.setParameters((List<ParameterInCallable>) callableDecl.getParameters().stream()
                .map(param -> processParameterDeclaration((Parameter) param)).collect(Collectors.toList()));

        callableNode.setEntrypoint(isEntryPointMethod(callableDecl));

        // A method declaration may not have a body if it is an abstract method. A
        // constructor always has a body. So, we need to check if the body is present before processing it
        // and capture it using the Optional type.
        Optional<BlockStmt> body = (callableDecl instanceof MethodDeclaration)
                ? ((MethodDeclaration) callableDecl).getBody()
                : Optional.ofNullable(((ConstructorDeclaration) callableDecl).getBody());

        // Same as above, a constructor declaration may not have a return type
        // and method declaration always has a return type.
        callableNode.setReturnType(
                (callableDecl instanceof MethodDeclaration) ? resolveType(((MethodDeclaration) callableDecl).getType())
                        : null);

        callableNode.setConstructor(callableDecl instanceof ConstructorDeclaration);
        callableNode.setStartLine(callableDecl.getRange().isPresent() ? callableDecl.getRange().get().begin.line : -1);
        callableNode.setEndLine(callableDecl.getRange().isPresent() ? callableDecl.getRange().get().end.line : -1);
        callableNode.setReferencedTypes(getReferencedTypes(body));
        try {
            callableNode.setCode(body.isPresent() ? LexicalPreservingPrinter.print(body.get()) : "");
        } catch (UnsupportedOperationException uoe) {
            Log.warn("LexicalPreservingPrinter.print() failed on method " + callableDecl.getSignature() +
                    " of type "+typeName);
            Log.warn("Reverting to default printing");
            Log.warn(body.get().toString());
            callableNode.setCode(body.get().toString());
        }
        callableNode.setCodeStartLine(body.isPresent()? body.get().getBegin().get().line : -1);

        callableNode.setAccessedFields(getAccessedFields(body, classFields, typeName));
        callableNode.setCallSites(getCallSites(body));
        callableNode.setCrudOperations(
                callableNode.getCallSites().stream()
                        .map(CallSite::getCrudOperation)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));
        callableNode.setCrudQueries(
                callableNode.getCallSites().stream()
                        .map(CallSite::getCrudQuery)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));
        callableNode.setVariableDeclarations(getVariableDeclarations(body));
        callableNode.setCyclomaticComplexity(getCyclomaticComplexity(callableDecl));

        String callableSignature = getTypeErasureSignature(callableDecl);
        return Pair.of(callableSignature, callableNode);
    }

    /**
     * Returns type erasure signature for the given callable. Returns regular signature if an
     * error occurs in getting erased types.
     *
     * @param callableDecl: Callable to compute type erasure signature for
     * @return String representing type erasure or regular signature
     */
    private static String getTypeErasureSignature(CallableDeclaration callableDecl) {
        try {
            StringBuilder signature = new StringBuilder(
                    (callableDecl instanceof MethodDeclaration) ? callableDecl.getNameAsString() : "<init>"
            );
            List<String> erasureParameterTypes = new ArrayList<>();
            for (Object param : callableDecl.getParameters()) {
                Parameter parameter = (Parameter) param;
                ResolvedType resolvedType = parameter.getType().resolve();
                if (parameter.isVarArgs()) {
                    erasureParameterTypes.add(resolvedType.erasure().describe() + "[]");
                } else {
                    erasureParameterTypes.add(resolvedType.erasure().describe());
                }
            }
            signature.append("(");
            signature.append(String.join(", ", erasureParameterTypes));
            signature.append(")");
            return signature.toString();
        } catch (Throwable e) {
            Log.warn("Could not compute type erasure signature for "+callableDecl.getSignature().asString()+
                    "; computing regular signature");
            return callableDecl.getSignature().asString();
        }
    }

    /**
     * Returns type erasure signature for the given method or constructor declaration
     * resolved for a call site.
     *
     * @param methodDecl: Resolved method/constructor to compute type erasure signature for
     * @return String representing type erasure signature
     */
    private static String getTypeErasureSignature(ResolvedMethodLikeDeclaration methodDecl) {
        StringBuilder signature = new StringBuilder(methodDecl.getName());
        List<String> erasureParameterTypes = new ArrayList<>();
        for (int i = 0; i < methodDecl.getNumberOfParams(); i++) {
            erasureParameterTypes.add(methodDecl.getParam(i).getType().erasure().describe());
        }
        signature.append("(");
        signature.append(String.join(", ", erasureParameterTypes));
        signature.append(")");
        return signature.toString();
    }

    private static boolean isEntryPointMethod(CallableDeclaration callableDecl) {
        return EntrypointsFinderFactory.getEntrypointFinders()
                .anyMatch(finder -> finder.isEntrypointMethod(callableDecl));
    }


    /**
     * Computes cyclomatic complexity for the given callable.
     *
     * @param callableDeclaration Callable to compute cyclomatic complexity for
     * @return cyclomatic complexity
     */
    private static int getCyclomaticComplexity(CallableDeclaration callableDeclaration) {
        int ifStmtCount = callableDeclaration.findAll(IfStmt.class).size();
        int loopStmtCount = callableDeclaration.findAll(DoStmt.class).size()
                + callableDeclaration.findAll(ForStmt.class).size()
                + callableDeclaration.findAll(ForEachStmt.class).size()
                + callableDeclaration.findAll(WhileStmt.class).size();
        int switchCaseCount = callableDeclaration.findAll(SwitchStmt.class).stream()
                .map(stmt -> stmt.getEntries().size()).reduce(0, Integer::sum);
        int conditionalExprCount = callableDeclaration.findAll(ConditionalExpr.class).size();
        int catchClauseCount = callableDeclaration.findAll(CatchClause.class).size();
        return ifStmtCount + loopStmtCount + switchCaseCount + conditionalExprCount + catchClauseCount + 1;
    }

    private static int getCyclomaticComplexity(InitializerDeclaration initializerDeclaration) {
        int ifStmtCount = initializerDeclaration.findAll(IfStmt.class).size();
        int loopStmtCount = initializerDeclaration.findAll(DoStmt.class).size()
                + initializerDeclaration.findAll(ForStmt.class).size()
                + initializerDeclaration.findAll(ForEachStmt.class).size()
                + initializerDeclaration.findAll(WhileStmt.class).size();
        int switchCaseCount = initializerDeclaration.findAll(SwitchStmt.class).stream()
                .map(stmt -> stmt.getEntries().size()).reduce(0, Integer::sum);
        int conditionalExprCount = initializerDeclaration.findAll(ConditionalExpr.class).size();
        int catchClauseCount = initializerDeclaration.findAll(CatchClause.class).size();
        return ifStmtCount + loopStmtCount + switchCaseCount + conditionalExprCount + catchClauseCount + 1;
    }

    /**
     * Processes the given field declaration to extract information about the
     * declared field and returns a JSON object containing the extracted
     * information.
     *
     * @param fieldDecl field declaration to be processed
     * @return Field object containing extracted information
     */
    private static Field processFieldDeclaration(FieldDeclaration fieldDecl) {
        Field field = new Field();

        // add comment associated with field
        com.ibm.cldk.entities.Comment comment = new com.ibm.cldk.entities.Comment();
        if (fieldDecl.getComment().isPresent()) {
            Comment parsedComment = fieldDecl.getComment().get();
            comment.setContent(parsedComment.getContent());
            parsedComment.getRange().ifPresent(range -> {
                comment.setStartLine(range.begin.line);
                comment.setEndLine(range.end.line);
                comment.setStartColumn(range.begin.column);
                comment.setEndColumn(range.end.column);
            });
        }
        field.setComment(comment);

        // add annotations on field
        field.setAnnotations(
                fieldDecl.getAnnotations().stream().map(a -> a.toString().strip()).collect(Collectors.toList()));

        // add variable names
        field.setVariables(
                fieldDecl.getVariables().stream().map(v -> v.getName().asString()).collect(Collectors.toList()));

        // add field modifiers
        field.setModifiers(
                fieldDecl.getModifiers().stream().map(m -> m.toString().strip()).collect(Collectors.toList()));

        // add field type
        field.setType(resolveType(fieldDecl.getCommonType()));

        // add field start and end lines
        field.setStartLine(fieldDecl.getRange().isPresent() ? fieldDecl.getRange().get().begin.line : null);

        field.setEndLine(fieldDecl.getRange().get().end.line);

        return field;
    }

    /**
     * Computes and returns the set of types references in a block of statement
     * (method or constructor body).
     *
     * @param blockStmt Block statement to compute referenced types for
     * @return List of types referenced in the block statement
     */
    private static List<String> getReferencedTypes(Optional<BlockStmt> blockStmt) {
        Set<String> referencedTypes = new HashSet<>();
        blockStmt.ifPresent(
                bs -> bs.findAll(VariableDeclarator.class).stream().filter(vd -> vd.getType().isClassOrInterfaceType())
                        .map(vd -> resolveType(vd.getType())).forEach(referencedTypes::add));

        // add types of accessed fields to the set of referenced types
        blockStmt.ifPresent(
                bs -> bs.findAll(FieldAccessExpr.class).stream().filter(faExpr -> faExpr.getParentNode().isPresent()
                        && !(faExpr.getParentNode().get() instanceof FieldAccessExpr)).map(faExpr -> {
                            if (faExpr.getParentNode().isPresent()
                                    && faExpr.getParentNode().get() instanceof CastExpr) {
                                return resolveType(((CastExpr) faExpr.getParentNode().get()).getType());
                            } else {
                                return resolveExpression(faExpr);
                            }
                        }).filter(type -> !type.isEmpty()).forEach(referencedTypes::add));

        // TODO: add resolved method access expressions
        return new ArrayList<>(referencedTypes);
    }

    /**
     * Returns information about variable declarations in the given callable.
     * The information includes var name, var type, var initializer, and
     * position.
     *
     * @param blockStmt Callable to compute var declaration information for
     * @return list of variable declarations
     */
    private static List<VariableDeclaration> getVariableDeclarations(Optional<BlockStmt> blockStmt) {
        List<VariableDeclaration> varDeclarations = new ArrayList<>();
        if (blockStmt.isEmpty()) {
            return varDeclarations;
        }
        for (VariableDeclarator declarator : blockStmt.get().findAll(VariableDeclarator.class)) {
            VariableDeclaration varDeclaration = new VariableDeclaration();
            com.ibm.cldk.entities.Comment comment = new com.ibm.cldk.entities.Comment();
            if (declarator.getComment().isPresent()) {
                Comment parsedComment = declarator.getComment().get();
                comment.setContent(parsedComment.getContent().isBlank() ? "" : parsedComment.getContent().isEmpty() ? "" : parsedComment.getContent());
                parsedComment.getRange().ifPresent(range -> {
                    comment.setStartLine(range.begin.line);
                    comment.setEndLine(range.end.line);
                    comment.setStartColumn(range.begin.column);
                    comment.setEndColumn(range.end.column);
                });
            }
            varDeclaration.setComment(comment);
            varDeclaration.setName(declarator.getNameAsString());
            varDeclaration.setType(resolveType(declarator.getType()));
            varDeclaration.setInitializer(
                    declarator.getInitializer().isPresent() ? declarator.getInitializer().get().toString() : "");
            if (declarator.getRange().isPresent()) {
                varDeclaration.setStartLine(declarator.getRange().get().begin.line);
                varDeclaration.setStartColumn(declarator.getRange().get().begin.column);
                varDeclaration.setEndLine(declarator.getRange().get().end.line);
                varDeclaration.setEndColumn(declarator.getRange().get().end.column);
            } else {
                varDeclaration.setStartLine(-1);
                varDeclaration.setStartColumn(-1);
                varDeclaration.setEndLine(-1);
                varDeclaration.setEndColumn(-1);
            }
            varDeclarations.add(varDeclaration);
        }
        return varDeclarations;
    }

    /**
     * Computes and returns the list of fields accessed in the given callable
     * body. The returned values contain field names qualified by names of the
     * declaring types.
     *
     * @param callableBody Callable body to compute accessed fields for
     * @return List of fully qualified field names
     */
    private static List<String> getAccessedFields(Optional<BlockStmt> callableBody, List<String> classFields,
            String typeName) {
        Set<String> accessedFields = new HashSet<>();

        // process field access expressions in the callable
        callableBody.ifPresent(
                cb -> cb.findAll(FieldAccessExpr.class).stream().filter(faExpr -> faExpr.getParentNode().isPresent()
                        && !(faExpr.getParentNode().get() instanceof FieldAccessExpr)).map(faExpr -> {
                            String fieldDeclaringType = resolveExpression(faExpr.getScope());
                            if (!fieldDeclaringType.isEmpty()) {
                                return fieldDeclaringType + "." + faExpr.getNameAsString();
                            } else {
                                return faExpr.getNameAsString();
                            }
                        }).forEach(accessedFields::add));

        // process all names expressions in callable and match against names of declared
        // fields
        // in class TODO: handle local variable declarations with the same name
        if (callableBody.isPresent()) {
            for (NameExpr nameExpr : callableBody.get().findAll(NameExpr.class)) {
                for (String fieldName : classFields) {
                    if (nameExpr.getNameAsString().equals(fieldName)) {
                        accessedFields.add(typeName + "." + nameExpr.getNameAsString());
                    }
                }
            }
        }

        return new ArrayList<>(accessedFields);
    }

    /**
     * Returns information about call sites in the given callable. The
     * information includes: the method name, the declaring type name, and types
     * of arguments used in method call.
     *
     * @param callableBody callable to compute call-site information for
     * @return list of call sites
     */
    @SuppressWarnings({ "OptionalUsedAsFieldOrParameterType" })
    private static List<CallSite> getCallSites(Optional<BlockStmt> callableBody) {
        List<CallSite> callSites = new ArrayList<>();
        if (callableBody.isEmpty()) {
            return callSites;
        }
        for (MethodCallExpr methodCallExpr : callableBody.get().findAll(MethodCallExpr.class)) {
            // resolve declaring type for called method
            boolean isStaticCall = false;
            String declaringType = "";
            String receiverName = "";
            String returnType = "";
            if (methodCallExpr.getScope().isPresent()) {
                Expression scopeExpr = methodCallExpr.getScope().get();
                receiverName = scopeExpr.toString();
                declaringType = resolveExpression(scopeExpr);
                if (declaringType.contains(" | ")) {
                    declaringType = declaringType.split(" \\| ")[0];
                }
                String declaringTypeName = declaringType.contains(".")
                        ? declaringType.substring(declaringType.lastIndexOf(".") + 1)
                        : declaringType;
                if (declaringTypeName.equals(scopeExpr.toString())) {
                    isStaticCall = true;
                }
            }

            // compute return type for method call taking into account typecast of return
            // value
            if (methodCallExpr.getParentNode().isPresent()
                    && methodCallExpr.getParentNode().get() instanceof CastExpr) {
                returnType = resolveType(((CastExpr) methodCallExpr.getParentNode().get()).getType());
            } else {
                returnType = resolveExpression(methodCallExpr);
            }

            // resolve callee and get signature
            String calleeSignature = "";
            try {
                calleeSignature = getTypeErasureSignature(methodCallExpr.resolve());
            } catch (Throwable exception) {
                Log.debug("Could not resolve method call: " + methodCallExpr + ": " + exception.getMessage());
            }

            // Resolve access qualifier
            AccessSpecifier accessSpecifier = AccessSpecifier.NONE;
            try {
                ResolvedMethodDeclaration resolvedMethodDeclaration = methodCallExpr.resolve();
                accessSpecifier = resolvedMethodDeclaration.accessSpecifier();
            } catch (Throwable exception) {
                Log.debug("Could not resolve access specifier for method call: " + methodCallExpr + ": "
                        + exception.getMessage());
            }
            // resolve arguments of the method call to types
            List<String> argumentTypes = methodCallExpr.getArguments().stream().map(SymbolTable::resolveExpression)
                    .collect(Collectors.toList());
            // Get argument string from the callsite
            List<String> listOfArgumentStrings = methodCallExpr.getArguments().stream().map(Expression::toString)
                    .collect(Collectors.toList());
            // Determine if this call site is potentially a CRUD operation.
            CRUDOperation crudOperation = null;
            Optional<CRUDOperationType> crudOperationType = findCRUDOperation(declaringType,
                    methodCallExpr.getNameAsString());
            if (crudOperationType.isPresent()) {
                // We found a CRUD operation, so we need to populate the details of the call
                // site this CRUD operation.
                int lineNumber = methodCallExpr.getRange().isPresent() ? methodCallExpr.getRange().get().begin.line
                        : -1;
                crudOperation = new CRUDOperation();
                crudOperation.setLineNumber(lineNumber);
                crudOperation.setOperationType(crudOperationType.get());
            }
            // Determine if this call site is potentially a CRUD query.
            CRUDQuery crudQuery = null;
            Optional<CRUDQueryType> crudQueryType = findCRUDQuery(declaringType, methodCallExpr.getNameAsString(),
                    Optional.of(listOfArgumentStrings));
            if (crudQueryType.isPresent()) {
                // We found a CRUD query, so we need to populate the details of the call site
                // this CRUD query.
                int lineNumber = methodCallExpr.getRange().isPresent() ? methodCallExpr.getRange().get().begin.line
                        : -1;
                crudQuery = new CRUDQuery();
                crudQuery.setLineNumber(lineNumber);
                crudQuery.setQueryType(crudQueryType.get());
                crudQuery.setQueryArguments(listOfArgumentStrings);
            }
            // add a new call site object


            callSites.add(createCallSite(methodCallExpr, methodCallExpr.getNameAsString(), receiverName, declaringType,
                    argumentTypes, listOfArgumentStrings, returnType, calleeSignature, isStaticCall, false, crudOperation, crudQuery,
                    accessSpecifier));
        }

        for (ObjectCreationExpr objectCreationExpr : callableBody.get().findAll(ObjectCreationExpr.class)) {
            // resolve declaring type for called method
            String instantiatedType = resolveType(objectCreationExpr.getType());

            // resolve arguments of the constructor call to types
            List<String> argumentTypes = objectCreationExpr.getArguments().stream().map(SymbolTable::resolveExpression)
                    .collect(Collectors.toList());

            // get argument expressions for constructor call
            List<String> argumentExpressions = objectCreationExpr.getArguments().stream().map(Expression::toString)
                    .collect(Collectors.toList());

            // resolve callee and get signature
            String calleeSignature = "";
            try {
                calleeSignature = getTypeErasureSignature(objectCreationExpr.resolve());
            } catch (Throwable exception) {
                Log.debug("Could not resolve constructor call: " + objectCreationExpr + ": " + exception.getMessage());
            }

            // add a new call site object
            callSites
                    .add(createCallSite(objectCreationExpr, "<init>",
                            objectCreationExpr.getScope().isPresent() ? objectCreationExpr.getScope().get().toString()
                                    : "",
                            instantiatedType, argumentTypes, argumentExpressions, instantiatedType, calleeSignature, false, true, null, null,
                            AccessSpecifier.NONE));
        }

        return callSites;
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static Optional<CRUDQueryType> findCRUDQuery(String declaringType, String nameAsString,
            Optional<List<String>> arguments) {
        return CRUDFinderFactory.getCRUDFinders().map(
                finder -> {
                    if (finder.isReadQuery(declaringType, nameAsString, arguments)) {
                        return CRUDQueryType.READ;
                    } else if (finder.isWriteQuery(declaringType, nameAsString, arguments)) {
                        return CRUDQueryType.WRITE;
                    } else if (finder.isNamedQuery(declaringType, nameAsString, arguments)) {
                        return CRUDQueryType.NAMED;
                    } else
                        return null;
                })
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static Optional<CRUDOperationType> findCRUDOperation(String declaringType, String nameAsString) {
        return CRUDFinderFactory.getCRUDFinders().map(
                finder -> {
                    if (finder.isCreateOperation(declaringType, nameAsString)) {
                        return CRUDOperationType.CREATE;
                    } else if (finder.isReadOperation(declaringType, nameAsString)) {
                        return CRUDOperationType.READ;
                    } else if (finder.isUpdateOperation(declaringType, nameAsString)) {
                        return CRUDOperationType.UPDATE;
                    } else if (finder.isDeleteOperation(declaringType, nameAsString)) {
                        return CRUDOperationType.DELETE;
                    } else
                        return null;
                })
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * Creates and returns a new CallSite object for the given expression, which
     * can be a method-call or object-creation expression.
     *
     * @param callExpr
     * @param calleeName
     * @param receiverExpr
     * @param receiverType
     * @param argumentTypes
     * @param argumentExpr
     * @param returnType
     * @param calleeSignature
     * @param isStaticCall
     * @param isConstructorCall
     * @param crudOperation,
     * @param crudQuery,
     * @param accessSpecifier
     * @return
     */
    private static CallSite createCallSite(
            Expression callExpr,
            String calleeName,
            String receiverExpr,
            String receiverType,
            List<String> argumentTypes,
            List<String> argumentExpr,
            String returnType,
            String calleeSignature,
            boolean isStaticCall,
            boolean isConstructorCall,
            CRUDOperation crudOperation,
            CRUDQuery crudQuery,
            AccessSpecifier accessSpecifier) {
        CallSite callSite = new CallSite();

        com.ibm.cldk.entities.Comment comment = new com.ibm.cldk.entities.Comment();
        callExpr.findAncestor(Node.class).ifPresent(stmt -> {
                    stmt.getComment().ifPresent(c -> {
                        comment.setContent(c.getContent());
                        c.getRange().ifPresent(range -> {
                            comment.setStartLine(range.begin.line);
                            comment.setEndLine(range.end.line);
                            comment.setStartColumn(range.begin.column);
                            comment.setEndColumn(range.end.column);
                        });
                        callSite.setComment(comment);
                    });
        });
        callSite.setMethodName(calleeName);
        callSite.setReceiverExpr(receiverExpr);
        callSite.setReceiverType(receiverType);
        callSite.setArgumentTypes(argumentTypes);
        callSite.setArgumentExpr(argumentExpr);
        callSite.setReturnType(returnType);
        callSite.setCalleeSignature(calleeSignature);
        callSite.setStaticCall(isStaticCall);
        callSite.setConstructorCall(isConstructorCall);
        callSite.setPrivate(accessSpecifier.equals(AccessSpecifier.PRIVATE));
        callSite.setPublic(accessSpecifier.equals(AccessSpecifier.PUBLIC));
        callSite.setProtected(accessSpecifier.equals(AccessSpecifier.PROTECTED));
        callSite.setUnspecified(accessSpecifier.equals(AccessSpecifier.NONE));
        callSite.setCrudOperation(crudOperation);
        callSite.setCrudQuery(crudQuery);
        if (callExpr.getRange().isPresent()) {
            callSite.setStartLine(callExpr.getRange().get().begin.line);
            callSite.setStartColumn(callExpr.getRange().get().begin.column);
            callSite.setEndLine(callExpr.getRange().get().end.line);
            callSite.setEndColumn(callExpr.getRange().get().end.column);
        } else {
            callSite.setStartLine(-1);
            callSite.setStartColumn(-1);
            callSite.setEndLine(-1);
            callSite.setEndColumn(-1);
        }
        return callSite;
    }

    /**
     * Calculates type for the given expression and returns the resolved type
     * name, or empty string if exception occurs during type resolution.
     *
     * @param expression Expression to be resolved
     * @return Resolved type name or empty string if type resolution fails
     */
    private static String resolveExpression(Expression expression) {
        // perform expression resolution if resolution of this expression did not fail
        // previously
        if (!unresolvedExpressions.contains(expression.toString())) {
            try {
                ResolvedType resolvedType = javaSymbolSolver.calculateType(expression);
                if (resolvedType.isReferenceType() || resolvedType.isUnionType()) {
                    return resolvedType.describe();
                }
            } catch (Throwable exception) {
                Log.debug("Could not resolve expression: " + expression + ": " + exception.getMessage());
                unresolvedExpressions.add(expression.toString());
            }
        }
        return "";
    }

    /**
     * Resolves the given type and returns string representation of the resolved
     * type. If type resolution fails, returns string representation (name) of
     * the type.
     *
     * @param type Type to be resolved
     * @return Resolved (qualified) type name
     */
    private static String resolveType(Type type) {
        // perform type resolution if resolution of this type did not fail previously
        if (!unresolvedTypes.contains(type.asString())) {
            try {
                return type.resolve().describe();
            } catch (Throwable e) {
                Log.warn("Could not resolve type: " + type.asString() + ": " + e.getMessage());
                unresolvedTypes.add(type.asString());
            }
        }
        return type.asString();
    }

    /**
     * Collects all source roots (e.g., "src/main/java", "src/test/java") under
     * the given project root path using the symbol solver collection strategy.
     * Parses all source files under each source root and returns the complete
     * symbol table as map of file path and java compilation unit pairs.
     *
     * @param projectRootPath root path of the project to be analyzed
     * @return Pair of extracted symbol table map and parse problems map for
     *         project
     * @throws IOException
     */
    private static final String[] EXCLUDED_SOURCE_ROOTS = {
            Paths.get("src", "test", "resources").toString(),
            Paths.get("src", "it", "resources").toString(),
            Paths.get("src", "xdocs-examples").toString()
    };
    private static boolean excludeSourceRoot(Path sourceRoot) {
        for (String excludedSrcRoot : EXCLUDED_SOURCE_ROOTS) {
            if (Pattern.compile(excludedSrcRoot).matcher(sourceRoot.toString()).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets up lexical preserving printer for the given compilation unit in a safe manner by checking
     * whether any node in the unit is missing ranges, which can result in exception.
     *
     * @param compilationUnit Compilation unit to be set with lexical preserving printer
     * @return compilation unit set up with lexical preserving printer or the original compilation
     *         unit if the unit contains range-missing nodes
     */
    private static CompilationUnit safeLexicalPreservingPrinterSetup(CompilationUnit compilationUnit) {
        // setup lexical-preserving printer only if CU has no missing-range nodes
        boolean hasNodeWithMissingRange = compilationUnit.findAll(Node.class).stream()
                .anyMatch(n -> !n.getRange().isPresent());
        if (!hasNodeWithMissingRange) {
            return LexicalPreservingPrinter.setup(compilationUnit);
        }
        return compilationUnit;
    }

    public static Pair<Map<String, JavaCompilationUnit>, Map<String, List<Problem>>> extractAll(Path projectRootPath)
            throws IOException {
        ParserConfiguration config = new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        SymbolSolverCollectionStrategy symbolSolverCollectionStrategy = new SymbolSolverCollectionStrategy(config);
        ProjectRoot projectRoot = symbolSolverCollectionStrategy.collect(projectRootPath);
        javaSymbolSolver = (JavaSymbolSolver) symbolSolverCollectionStrategy.getParserConfiguration()
                .getSymbolResolver().get();
        Map<String, JavaCompilationUnit> symbolTable = new LinkedHashMap<>();
        Map<String, List<Problem>> parseProblems = new HashMap<>();
        for (SourceRoot sourceRoot : projectRoot.getSourceRoots()) {
            if (excludeSourceRoot(sourceRoot.getRoot())) {
                continue;
            }
            sourceRoot.setParserConfiguration(config);
            for (ParseResult<CompilationUnit> parseResult : sourceRoot.tryToParse()) {
                if (parseResult.isSuccessful()) {
                    CompilationUnit compilationUnit = safeLexicalPreservingPrinterSetup(parseResult.getResult().get());
                    symbolTable.put(compilationUnit.getStorage().get().getPath().toString(),
                            processCompilationUnit(compilationUnit));
                } else {
                    parseProblems.put(sourceRoot.getRoot().toString(), parseResult.getProblems());
                }
            }
        }
        return Pair.of(symbolTable, parseProblems);
    }

    public static Pair<Map<String, JavaCompilationUnit>, Map<String, List<Problem>>> extractSingle(String code)
            throws IOException {
        Map symbolTable = new LinkedHashMap<String, JavaCompilationUnit>();
        Map parseProblems = new HashMap<String, List<Problem>>();
        // Setting up symbol solvers
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());

        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        parserConfiguration.setSymbolResolver(new JavaSymbolSolver(combinedTypeSolver));

        JavaParser javaParser = new JavaParser(parserConfiguration);
        ParseResult<CompilationUnit> parseResult = javaParser.parse(code);
        if (parseResult.isSuccessful()) {
            CompilationUnit compilationUnit = safeLexicalPreservingPrinterSetup(parseResult.getResult().get());
            Log.debug("Successfully parsed code. Now processing compilation unit");
            symbolTable.put("<pseudo-path>", processCompilationUnit(compilationUnit));
        } else {
            Log.error(parseResult.getProblems().toString());
            parseProblems.put("code", parseResult.getProblems());
        }
        return Pair.of(symbolTable, parseProblems);
    }

    /**
     * Parses the given set of Java source files from the given project and
     * constructs the symbol table.
     *
     * @param projectRootPath
     * @param javaFilePaths
     * @return
     * @throws IOException
     */
    public static Pair<Map<String, JavaCompilationUnit>, Map<String, List<Problem>>> extract(Path projectRootPath,
            List<Path> javaFilePaths) throws IOException {

        // create symbol solver and parser configuration
        SymbolSolverCollectionStrategy symbolSolverCollectionStrategy = new SymbolSolverCollectionStrategy();
        ProjectRoot projectRoot = symbolSolverCollectionStrategy.collect(projectRootPath);
        javaSymbolSolver = (JavaSymbolSolver) symbolSolverCollectionStrategy.getParserConfiguration()
                .getSymbolResolver().get();
        Log.info("Setting parser language level to JAVA_21");
        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        parserConfiguration.setSymbolResolver(javaSymbolSolver);

        // create java parser with the configuration
        JavaParser javaParser = new JavaParser(parserConfiguration);

        Map symbolTable = new LinkedHashMap<String, JavaCompilationUnit>();
        Map parseProblems = new HashMap<String, List<Problem>>();

        // parse all given files and return pair of symbol table and parse problems
        for (Path javaFilePath : javaFilePaths) {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(javaFilePath);
            if (parseResult.isSuccessful()) {
                CompilationUnit compilationUnit = safeLexicalPreservingPrinterSetup(parseResult.getResult().get());
                System.out.println("Successfully parsed file: " + javaFilePath.toString());
                symbolTable.put(compilationUnit.getStorage().get().getPath().toString(),
                        processCompilationUnit(compilationUnit));
            } else {
                Log.error(parseResult.getProblems().toString());
                parseProblems.put(javaFilePath.toString(), parseResult.getProblems());
            }
        }
        return Pair.of(symbolTable, parseProblems);
    }

    public static void main(String[] args) throws IOException {
        extractAll(Paths.get(args[0]));
    }

}
