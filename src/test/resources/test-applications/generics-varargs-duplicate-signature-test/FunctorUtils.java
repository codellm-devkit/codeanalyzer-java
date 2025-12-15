import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.commons.collections4.Predicate;

final class FunctorUtils {

    private static <T> T[] clone(final T... array) {
        return array != null ? array.clone() : null;
    }

    static <R extends java.util.function.Predicate<T>, P extends java.util.function.Predicate<? super T>, T> R coerce(final P predicate) {
        return (R) predicate;
    }

    static <R extends Function<I, O>, P extends Function<? super I, ? extends O>, I, O> R coerce(final P transformer) {
        return (R) transformer;
    }

    static <T extends Consumer<?>> T[] copy(final T... consumers) {
        return clone(consumers);
    }

    static <T extends java.util.function.Predicate<?>> T[] copy(final T... predicates) {
        return clone(predicates);
    }

    static <T extends Function<?, ?>> T[] copy(final T... transformers) {
        return clone(transformers);
    }

    static <T> Predicate<? super T>[] validate(final Collection<? extends java.util.function.Predicate<? super T>> predicates) {
        Objects.requireNonNull(predicates, "predicates");
        // convert to array like this to guarantee iterator() ordering
        @SuppressWarnings("unchecked") // OK
        final Predicate<? super T>[] preds = new Predicate[predicates.size()];
        int i = 0;
        for (final java.util.function.Predicate<? super T> predicate : predicates) {
            preds[i] = (Predicate<? super T>) predicate;
            if (preds[i] == null) {
                throw new NullPointerException("predicates[" + i + "]");
            }
            i++;
        }
        return preds;
    }

    static void validate(final Consumer<?>... consumers) {
        Objects.requireNonNull(consumers, "consumers");
        for (int i = 0; i < consumers.length; i++) {
            if (consumers[i] == null) {
                throw new NullPointerException("closures[" + i + "]");
            }
        }
    }

    static void validate(final Function<?, ?>... functions) {
        Objects.requireNonNull(functions, "functions");
        for (int i = 0; i < functions.length; i++) {
            if (functions[i] == null) {
                throw new NullPointerException("functions[" + i + "]");
            }
        }
    }

    static void validate(final java.util.function.Predicate<?>... predicates) {
        Objects.requireNonNull(predicates, "predicates");
        for (int i = 0; i < predicates.length; i++) {
            if (predicates[i] == null) {
                throw new NullPointerException("predicates[" + i + "]");
            }
        }
    }

}
