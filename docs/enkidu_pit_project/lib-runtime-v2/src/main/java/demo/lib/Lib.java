package demo.lib;

/**
 * Runtime API (v2) used to trigger signature mismatch / missing method.
 *
 * Note: foo() was removed and replaced with foo(int).
 */
public class Lib {
    public void foo(int x) {
        // no-op
    }
}
