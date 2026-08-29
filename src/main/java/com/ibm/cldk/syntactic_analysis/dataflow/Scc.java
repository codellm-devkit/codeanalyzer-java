package com.ibm.cldk.syntactic_analysis.dataflow;

import com.ibm.cldk.schema.JCallEdge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Tarjan SCC condensation of the call graph: the bottom-up (callees-first) processing order the L4
 * summary pass (Task 9) needs, since a function's summary can only be computed once its callees'
 * are known, and mutual recursion must be solved together as one group. Tarjan is iterative — an
 * explicit-stack DFS, not the textbook recursive one — because a real call graph is deep enough to
 * overflow the JVM stack. Node visitation and component membership are both sorted so the schedule
 * is independent of the caller's iteration order over {@code nodes}.
 */
public final class Scc {

    private Scc() {}

    /** Components in reverse-topological (bottom-up, callees-first) order; ids sorted within each. */
    public static List<List<String>> condense(Collection<String> nodes, List<JCallEdge> edges) {
        Map<String, List<String>> adjacency = buildAdjacency(nodes, edges);

        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowlink = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        List<List<String>> components = new ArrayList<>();
        int[] counter = {0};

        for (String root : adjacency.keySet()) {
            if (!index.containsKey(root)) {
                strongConnect(root, adjacency, index, lowlink, onStack, stack, components, counter);
            }
        }
        return components;
    }

    /** {@code nodes} sorted, each mapped to its edges into other {@code nodes} (external targets dropped). */
    private static Map<String, List<String>> buildAdjacency(Collection<String> nodes, List<JCallEdge> edges) {
        // TreeSet per node: dedups (src, dst) pairs and sorts successors in one step, so a cycle
        // reached via more than one edge can't leak the caller's edge-list order into which member
        // Tarjan happens to root the component on.
        Map<String, TreeSet<String>> bySource = new TreeMap<>();
        for (String n : nodes) {
            bySource.put(n, new TreeSet<>());
        }
        for (JCallEdge e : edges) {
            TreeSet<String> successors = bySource.get(e.getSrc());
            if (successors != null && bySource.containsKey(e.getDst())) {
                successors.add(e.getDst());
            }
        }
        Map<String, List<String>> adjacency = new TreeMap<>();
        for (Map.Entry<String, TreeSet<String>> entry : bySource.entrySet()) {
            adjacency.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return adjacency;
    }

    /**
     * One frame of the explicit-stack DFS: a node plus how far its successor list has been
     * consumed, standing in for the recursive call's local variables and program counter.
     */
    private static final class Frame {
        final String node;
        int nextSuccessor;

        Frame(String node) {
            this.node = node;
        }
    }

    private static void strongConnect(
            String root,
            Map<String, List<String>> adjacency,
            Map<String, Integer> index,
            Map<String, Integer> lowlink,
            Set<String> onStack,
            Deque<String> stack,
            List<List<String>> components,
            int[] counter) {
        Deque<Frame> work = new ArrayDeque<>();
        work.push(new Frame(root));
        index.put(root, counter[0]);
        lowlink.put(root, counter[0]);
        counter[0]++;
        stack.push(root);
        onStack.add(root);

        while (!work.isEmpty()) {
            Frame frame = work.peek();
            List<String> successors = adjacency.get(frame.node);
            if (frame.nextSuccessor < successors.size()) {
                String succ = successors.get(frame.nextSuccessor++);
                if (!index.containsKey(succ)) {
                    index.put(succ, counter[0]);
                    lowlink.put(succ, counter[0]);
                    counter[0]++;
                    stack.push(succ);
                    onStack.add(succ);
                    work.push(new Frame(succ));
                } else if (onStack.contains(succ)) {
                    lowlink.put(frame.node, Math.min(lowlink.get(frame.node), index.get(succ)));
                }
                continue;
            }

            // All of frame.node's successors are explored; if it's a component root, pop the
            // component off the Tarjan stack, then fold its lowlink into its caller's (the next
            // frame down) exactly as the recursive version does on return.
            work.pop();
            if (lowlink.get(frame.node).equals(index.get(frame.node))) {
                List<String> component = new ArrayList<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(frame.node));
                Collections.sort(component);
                components.add(component);
            }
            if (!work.isEmpty()) {
                Frame caller = work.peek();
                lowlink.put(caller.node, Math.min(lowlink.get(caller.node), lowlink.get(frame.node)));
            }
        }
    }
}
