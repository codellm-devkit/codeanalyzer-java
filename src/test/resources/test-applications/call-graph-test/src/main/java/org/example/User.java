package org.example;

import org.example.greeting.Greeter;

public class User {

    private String name;
    public User(String name)
    {
        this.name = name;
    }

    private void loglog()
    {
        this.name += " (logged in x2)";
    }

    private void log()
    {
        this.name += " (logged in)";
        loglog();
    }

    public String getName()
    {
        return this.name;
    }

    String helloString()
    {
        log();
        return Greeter.greet(this.getName());
    }
}
