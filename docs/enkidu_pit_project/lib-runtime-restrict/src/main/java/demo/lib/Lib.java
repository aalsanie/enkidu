package demo.lib;

/**
 * Runtime API variant used to trigger IllegalAccessError-style failures.
 *
 * This keeps foo(), but makes it package-private (no modifier).
 */
public class Lib {
    void foo() {
        // no-op
    }
}
