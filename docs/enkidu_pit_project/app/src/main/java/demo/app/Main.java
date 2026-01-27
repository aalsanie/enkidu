package demo.app;

import demo.lib.Lib;
import demo.spi.Greeter;

import java.util.ServiceLoader;

/**
 * Tiny app used only as a *bytecode target* for Enkidu.
 *
 * It intentionally:
 * - calls demo.lib.Lib#foo() (compiled against lib-api-v1)
 * - uses ServiceLoader to load demo.spi.Greeter providers
 */
public final class Main {
    public static void main(String[] args) {
        Lib lib = new Lib();

        // Compiled against lib-api-v1: public void foo().
        // Some runtime classpaths in this pit-project will change this signature or its visibility.
        lib.foo();

        // SPI: scenarios include providers that are missing or have type mismatch.
        for (Greeter g : ServiceLoader.load(Greeter.class)) {
            System.out.println(g.greet("enkidu"));
        }
    }
}
