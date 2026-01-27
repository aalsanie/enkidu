package demo.lib;

/**
 * Shadowed copy that actually contains the method the app expects.
 *
 * In the shadowing scenario, this jar is SECOND on the classpath,
 * so it is ignored at runtime even though it would make the app work.
 */
public class Lib {
    public void foo() {
        // no-op
    }
}
