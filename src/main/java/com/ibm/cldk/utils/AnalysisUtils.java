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
package com.ibm.cldk.utils;

import static com.ibm.cldk.SymbolTable.declaredMethodsAndConstructors;

import com.ibm.cldk.entities.Callable;
import com.ibm.cldk.entities.Comment;
import com.ibm.cldk.entities.ParameterInCallable;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.Type;

/**
 * The type Analysis utils.
 */
public class AnalysisUtils {

    /**
     * The constant classAttr.
     */
    public static Map<String, String> createAndPutNewCallableInSymbolTable(IMethod method) {
        // Get the class name, with a . representation.
        String declaringClassSignature = method.getDeclaringClass().getName().toString().substring(1).replace("/", ".").replace("$", ".");

        // Get the method arguments, use a . notation for types.
        List<String> arguments = Arrays.stream(Type.getMethodType(method.getDescriptor().toString()).getArgumentTypes()).map(Type::getClassName).collect(Collectors.toList());

        String methodName = method.getName().toString();

        // Get the method signature.
        String methodSignature = String.join("", methodName, "(", String.join(", ", Optional.of(arguments).orElseGet(Collections::emptyList)), ")");

        Callable newCallable = new Callable();
        newCallable.setFilePath("");
        newCallable.setImplicit(true);
        newCallable.setConstructor(methodName.contains("<init>"));
        newCallable.setComments(new ArrayList<>());
        newCallable.setModifiers(Stream.of(method.isPublic() ? "public" : null, method.isProtected() ? "protected" : null, method.isPrivate() ? "private" : null, method.isAbstract() ? "abstract" : null, method.isStatic() ? "static" : null, method.isFinal() ? "final" : null, method.isSynchronized() ? "synchronized" : null, method.isNative() ? "native" : null, method.isSynthetic() ? "synthetic" : null, method.isBridge() ? "bridge" : null).filter(Objects::nonNull).collect(Collectors.toList()));
        newCallable.setCode("");
        newCallable.setSignature(methodSignature);
        newCallable.setDeclaration(methodSignature);
        newCallable.setEndLine(-1);
        newCallable.setStartLine(-1);
        newCallable.setParameters(Arrays.stream(Type.getMethodType(method.getDescriptor().toString()).getArgumentTypes()).map(param -> {
            ParameterInCallable parameter = new ParameterInCallable();
            parameter.setType(param.getClassName());
            parameter.setName(null);
            parameter.setModifiers(Collections.emptyList());
            parameter.setAnnotations(Collections.emptyList());
            return parameter;
        }).collect(Collectors.toList()));
        newCallable.setReferencedTypes(Collections.emptyList());
        newCallable.setAnnotations(method.getAnnotations().stream().map(annotation -> annotation.toString().replace("[", "(").replace("]", ")").replace("Annotation type ", "@")).collect(Collectors.toList()));

        declaredMethodsAndConstructors.put(declaringClassSignature, methodSignature, newCallable);
        String signature = newCallable.getSignature();
        if (signature.contains("<init>")) {
            signature = signature.replace("<init>", declaringClassSignature.substring(declaringClassSignature.lastIndexOf(".") + 1));
        } else if (signature.contains("<clinit>")) {
            signature = signature.replace("<clinit>", declaringClassSignature.substring(declaringClassSignature.lastIndexOf(".") + 1));
        }
        return Map.ofEntries(
                Map.entry("typeDeclaration", declaringClassSignature),
                Map.entry("filePath", "<<implicit>>"),
                Map.entry("signature", signature),
                Map.entry("callableDeclaration", newCallable.getDeclaration())
        );
    }

    /**
     * Computes and returns cyclomatic complexity for the given IR (for a method
     * or constructor).
     *
     * @param ir IR for method or constructor
     * @return int Cyclomatic complexity for method/constructor
     */
    public static int getCyclomaticComplexity(IR ir) {
        if (ir == null) {
            return 0;
        }
        int conditionalBranchCount = (int) Arrays.stream(ir.getInstructions())
                .filter(inst -> inst instanceof SSAConditionalBranchInstruction)
                .count();
        int switchBranchCount = Arrays.stream(ir.getInstructions())
                .filter(inst -> inst instanceof SSASwitchInstruction)
                .map(inst -> ((SSASwitchInstruction) inst).getCasesAndLabels().length).reduce(0, Integer::sum);
        Iterable<ISSABasicBlock> iterableBasicBlocks = ir::getBlocks;
        int catchBlockCount = (int) StreamSupport.stream(iterableBasicBlocks.spliterator(), false)
                .filter(ISSABasicBlock::isCatchBlock)
                .count();
        return conditionalBranchCount + switchBranchCount + catchBlockCount + 1;
    }

    public static Map<String, String> getCallableFromSymbolTable(IMethod method) {

        // Get the class name, with a . representation.
        String declaringClassSignature = method.getDeclaringClass().getName().toString().substring(1).replace("/", ".").replace("$", ".");

        // Get the method arguments, use a . notation for types.
        List<String> arguments = Arrays.stream(Type.getMethodType(method.getDescriptor().toString()).getArgumentTypes()).map(Type::getClassName).collect(Collectors.toList());

        // Get the method signature.
        String methodSignature = String.join("", method.getName().toString(), "(", String.join(", ", Optional.of(arguments).orElseGet(Collections::emptyList)), ")");
        Callable callable = declaredMethodsAndConstructors.get(declaringClassSignature, methodSignature);

        if (callable == null) {
            return null;
        } else {
            String signature = callable.getSignature();
            if (signature.contains("<init>")) {
                signature = signature.replace("<init>", declaringClassSignature.substring(declaringClassSignature.lastIndexOf(".") + 1));
            } else if (signature.contains("<clinit>")) {
                signature = signature.replace("<clinit>", declaringClassSignature.substring(declaringClassSignature.lastIndexOf(".") + 1));
            }
            return Map.ofEntries(
                    Map.entry("typeDeclaration", declaringClassSignature),
                    Map.entry("filePath", callable.getFilePath()),
                    Map.entry("signature", signature),
                    Map.entry("callableDeclaration", callable.getSignature())
            );
        }
    }

    public static Pair<String, Callable> getCallableObjectFromSymbolTable(IMethod method) {

        // Get the class name, with a . representation.
        String declaringClassSignature = method.getDeclaringClass().getName().toString().substring(1).replace("/", ".").replace("$", ".");

        // Get the method arguments, use a . notation for types.
        List<String> arguments = Arrays.stream(Type.getMethodType(method.getDescriptor().toString()).getArgumentTypes()).map(Type::getClassName).collect(Collectors.toList());

        // Get the method signature.
        String methodSignature = String.join("", method.getName().toString(), "(", String.join(", ", Optional.of(arguments).orElseGet(Collections::emptyList)), ")");

        return Pair.of(declaringClassSignature, declaredMethodsAndConstructors.get(declaringClassSignature, methodSignature));
    }


    /**
     * Verfy if a class is an application class.
     *
     * @param _class the class
     * @return Boolean boolean
     */
    public static Boolean isApplicationClass(IClass _class) {
        return _class.getClassLoader().getReference().equals(ClassLoaderReference.Application);
    }

    /**
     * Gets number of application classes.
     *
     * @param cha the cha
     * @return the number of application classes
     */
    public static long getNumberOfApplicationClasses(IClassHierarchy cha) {
        return StreamSupport.stream(cha.spliterator(), false).filter(AnalysisUtils::isApplicationClass).count();
    }

    /**
     * Use all public methods of all application classes as entrypoints.
     *
     * @param cha the cha
     * @return Iterable<Entrypoint> entry points
     */
    public static Iterable<Entrypoint> getEntryPoints(IClassHierarchy cha) {
        // Sequential, not parallel: the class hierarchy's spliterator is unordered, so collecting it in
        // parallel yields a different entrypoint order per run, and RTA reaches a slightly different set
        // from a different order — which made the call graph irreproducible (on daytrader8, edge counts
        // varying 1875/1876 and the declared/rta provenance split swinging across ~14 edges between runs).
        // The parallel flag bought no measurable time here; entrypoint construction is a sliver of the
        // total, and the whole analysis measured the same wall clock either way.
        List<Entrypoint> entrypoints = StreamSupport.stream(cha.spliterator(), false).filter(AnalysisUtils::isApplicationClass).flatMap(c -> {
            try {
                return c.getDeclaredMethods().stream();
            } catch (NullPointerException nullPointerException) {
                Log.error(c.getSourceFileName());
                System.exit(1);
                return Stream.empty();
            }
        }).map(method -> new DefaultEntrypoint(method, cha)).collect(Collectors.toList());
        // We're assuming that all methods are potential entrypoints. May revisit this later if the assumption is incorrect.
        Log.info("Registered " + entrypoints.size() + " entrypoints.");
        return entrypoints;
    }
}
