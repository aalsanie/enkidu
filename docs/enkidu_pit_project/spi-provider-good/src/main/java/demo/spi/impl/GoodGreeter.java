package demo.spi.impl;

import demo.spi.Greeter;

public final class GoodGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "hi " + name;
    }
}
