package it.unibz.inf.ontop.query.translation.path;

public final class PathQueryContext {

    private static final ThreadLocal<PathQuerySupport> CURRENT = new ThreadLocal<>();

    private PathQueryContext() {
    }

    public static void set(PathQuerySupport support) {
        CURRENT.set(support);
    }

    public static PathQuerySupport current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
